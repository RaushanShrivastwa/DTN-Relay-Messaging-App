package com.dtn.mesh.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.dtn.mesh.database.entity.ContactEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for peer contact/routing state.
 *
 * Provides operations needed by:
 * - PRoPHET: delivery predictability updates, aging, transitivity
 * - MaxProp: path likelihood normalisation
 * - Q-learning: encounter frequency, duration, success rate
 * - UI: displaying known peers and their metrics
 */
@Dao
interface ContactDao {

    // ──────────────────────────────────────────────────────────────────────
    // Insert / Update
    // ──────────────────────────────────────────────────────────────────────

    /** Insert a new contact or ignore if already exists (first encounter). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(contact: ContactEntity): Long

    /** Full entity update after encounter processing. */
    @Update
    suspend fun update(contact: ContactEntity)

    // ──────────────────────────────────────────────────────────────────────
    // Encounter updates (atomic operations)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Record a new encounter with a peer: increment encounter count, update timestamps,
     * signal metrics, and inter-contact time.
     *
     * This is called transactionally from the encounter processor.
     */
    @Query("""
        UPDATE contacts SET
            total_encounters = total_encounters + 1,
            last_encounter_ms = :encounterTimeMs,
            first_encounter_ms = CASE WHEN first_encounter_ms = 0 THEN :encounterTimeMs ELSE first_encounter_ms END,
            total_encounter_duration_ms = total_encounter_duration_ms + :durationMs,
            total_inter_contact_time_ms = total_inter_contact_time_ms + 
                CASE WHEN last_encounter_ms > 0 THEN (:encounterTimeMs - last_encounter_ms) ELSE 0 END,
            best_rssi = MAX(best_rssi, :rssi),
            best_snr = MAX(best_snr, :snr),
            last_rssi = :rssi,
            last_snr = :snr,
            is_online = 1
        WHERE node_id = :nodeId
    """)
    suspend fun recordEncounter(
        nodeId: String,
        encounterTimeMs: Long,
        durationMs: Long,
        rssi: Int,
        snr: Float,
    )

    // ──────────────────────────────────────────────────────────────────────
    // PRoPHET operations
    // ──────────────────────────────────────────────────────────────────────

    /** Update delivery predictability for a specific peer. */
    @Query("""
        UPDATE contacts 
        SET delivery_probability = :probability, last_aged_at_ms = :agedAtMs 
        WHERE node_id = :nodeId
    """)
    suspend fun updateDeliveryProbability(nodeId: String, probability: Double, agedAtMs: Long)

    /** Bulk update delivery probabilities after aging (all contacts). */
    @Query("UPDATE contacts SET delivery_probability = delivery_probability * :agingFactor, last_aged_at_ms = :nowMs")
    suspend fun ageAllProbabilities(agingFactor: Double, nowMs: Long)

    // ──────────────────────────────────────────────────────────────────────
    // MaxProp operations
    // ──────────────────────────────────────────────────────────────────────

    /** Update path likelihood for a peer. */
    @Query("UPDATE contacts SET path_likelihood = :likelihood WHERE node_id = :nodeId")
    suspend fun updatePathLikelihood(nodeId: String, likelihood: Double)

    /** Sum of all path likelihoods (for normalisation). */
    @Query("SELECT COALESCE(SUM(path_likelihood), 0.0) FROM contacts")
    suspend fun getTotalPathLikelihood(): Double

    // ──────────────────────────────────────────────────────────────────────
    // Delivery tracking
    // ──────────────────────────────────────────────────────────────────────

    /** Increment delivery attempt count when we forward to this peer. */
    @Query("UPDATE contacts SET delivery_attempt_count = delivery_attempt_count + 1 WHERE node_id = :nodeId")
    suspend fun incrementDeliveryAttempt(nodeId: String)

    /** Increment delivery success count when we get an ACK for a message forwarded to this peer. */
    @Query("UPDATE contacts SET delivery_success_count = delivery_success_count + 1 WHERE node_id = :nodeId")
    suspend fun incrementDeliverySuccess(nodeId: String)

    // ──────────────────────────────────────────────────────────────────────
    // Online/offline state
    // ──────────────────────────────────────────────────────────────────────

    /** Mark peer as offline. */
    @Query("UPDATE contacts SET is_online = 0 WHERE node_id = :nodeId")
    suspend fun markOffline(nodeId: String)

    /**
     * Mark peer as online. Used unconditionally on every ONLINE encounter so a peer that
     * comes back after being marked offline flips green immediately, without depending on
     * the "isNew encounter" branch in the orchestrator.
     */
    @Query("UPDATE contacts SET is_online = 1 WHERE node_id = :nodeId")
    suspend fun markOnline(nodeId: String)

    /** Mark all peers as offline (e.g. on our own disconnect — we can no longer prove liveness). */
    @Query("UPDATE contacts SET is_online = 0")
    suspend fun markAllOffline()

    // ──────────────────────────────────────────────────────────────────────
    // Queries
    // ──────────────────────────────────────────────────────────────────────

    /** Get a single contact by node ID. */
    @Query("SELECT * FROM contacts WHERE node_id = :nodeId")
    suspend fun getByNodeId(nodeId: String): ContactEntity?

    /** All known contacts, ordered by last encounter (most recent first). */
    @Query("SELECT * FROM contacts ORDER BY last_encounter_ms DESC")
    suspend fun getAll(): List<ContactEntity>

    /** Reactive flow of all contacts for UI display. */
    @Query("SELECT * FROM contacts ORDER BY last_encounter_ms DESC")
    fun observeAll(): Flow<List<ContactEntity>>

    /** Contacts currently online. */
    @Query("SELECT * FROM contacts WHERE is_online = 1 ORDER BY delivery_probability DESC")
    suspend fun getOnlineContacts(): List<ContactEntity>

    /** Reactive flow of online contacts. */
    @Query("SELECT * FROM contacts WHERE is_online = 1 ORDER BY delivery_probability DESC")
    fun observeOnlineContacts(): Flow<List<ContactEntity>>

    /** Top N contacts by delivery probability (for forwarding candidate selection). */
    @Query("""
        SELECT * FROM contacts 
        WHERE is_online = 1 
        ORDER BY delivery_probability DESC 
        LIMIT :limit
    """)
    suspend fun getTopByDeliveryProbability(limit: Int): List<ContactEntity>

    /** All contacts for research export. */
    @Query("SELECT * FROM contacts ORDER BY node_id ASC")
    suspend fun getAllForExport(): List<ContactEntity>

    /** Count of known contacts. */
    @Query("SELECT COUNT(*) FROM contacts")
    fun observeContactCount(): Flow<Int>

    // ──────────────────────────────────────────────────────────────────────
    // Metadata updates
    // ──────────────────────────────────────────────────────────────────────

    /** Update human-readable node info from Meshtastic NODE_CHANGE broadcast. */
    @Query("""
        UPDATE contacts 
        SET long_name = :longName, short_name = :shortName, hw_model = :hwModel 
        WHERE node_id = :nodeId
    """)
    suspend fun updateNodeInfo(nodeId: String, longName: String?, shortName: String?, hwModel: String?)

    /** Set (or clear when [name] is null/blank) the user-provided nickname for a peer. */
    @Query("UPDATE contacts SET custom_name = :name WHERE node_id = :nodeId")
    suspend fun updateCustomName(nodeId: String, name: String?)

    /**
     * Refresh the most recent signal metrics for a peer. Lighter-weight than
     * [recordEncounter] which also increments the encounter counter; used on every online
     * event so the RSSI-based signal bars in the UI track continuously, not only on the
     * first encounter of each contact window.
     */
    @Query("UPDATE contacts SET last_rssi = :rssi, last_snr = :snr WHERE node_id = :nodeId")
    suspend fun updateSignal(nodeId: String, rssi: Int, snr: Float)
}
