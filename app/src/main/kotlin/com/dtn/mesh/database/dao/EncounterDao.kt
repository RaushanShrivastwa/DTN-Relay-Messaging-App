package com.dtn.mesh.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.dtn.mesh.database.entity.EncounterEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for encounter (contact window) time-series records.
 *
 * Provides:
 * - Encounter lifecycle management (open/close encounter windows)
 * - Statistics queries for Q-learning state features
 * - Research data export (full encounter history for offline analysis)
 */
@Dao
interface EncounterDao {

    // ──────────────────────────────────────────────────────────────────────
    // Insert / Update
    // ──────────────────────────────────────────────────────────────────────

    /** Insert a new encounter record (when a new contact window opens). */
    @Insert
    suspend fun insert(encounter: EncounterEntity): Long

    /** Update an encounter (e.g. close the window, update packet counts). */
    @Update
    suspend fun update(encounter: EncounterEntity)

    // ──────────────────────────────────────────────────────────────────────
    // Active encounter management
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Get the most recent encounter with a peer.
     * Used to determine if we should extend an existing encounter window
     * or start a new one (based on gap threshold).
     */
    @Query("""
        SELECT * FROM encounters 
        WHERE peer_node_id = :peerNodeId 
        ORDER BY start_time_ms DESC 
        LIMIT 1
    """)
    suspend fun getMostRecentEncounter(peerNodeId: String): EncounterEntity?

    /**
     * Get encounters still "open" (end_time_ms == 0).
     * Used for cleanup if the app is killed mid-encounter.
     */
    @Query("SELECT * FROM encounters WHERE end_time_ms = 0")
    suspend fun getOpenEncounters(): List<EncounterEntity>

    /**
     * Close an encounter window: set end time and duration.
     */
    @Query("""
        UPDATE encounters 
        SET end_time_ms = :endTimeMs, 
            duration_ms = :endTimeMs - start_time_ms 
        WHERE id = :encounterId AND end_time_ms = 0
    """)
    suspend fun closeEncounter(encounterId: Long, endTimeMs: Long)

    /**
     * Close all open encounters (called on Meshtastic disconnect or app shutdown).
     */
    @Query("""
        UPDATE encounters 
        SET end_time_ms = :nowMs, 
            duration_ms = :nowMs - start_time_ms 
        WHERE end_time_ms = 0
    """)
    suspend fun closeAllOpenEncounters(nowMs: Long = System.currentTimeMillis())

    // ──────────────────────────────────────────────────────────────────────
    // Statistics queries (for Q-learning state computation)
    // ──────────────────────────────────────────────────────────────────────

    /** Count of encounters with a specific peer within a time window (for frequency calculation). */
    @Query("""
        SELECT COUNT(*) FROM encounters 
        WHERE peer_node_id = :peerNodeId 
        AND start_time_ms >= :sinceMs
    """)
    suspend fun getEncounterCountSince(peerNodeId: String, sinceMs: Long): Int

    /** Average encounter duration with a specific peer (ms). */
    @Query("""
        SELECT COALESCE(AVG(duration_ms), 0) FROM encounters 
        WHERE peer_node_id = :peerNodeId 
        AND duration_ms > 0
    """)
    suspend fun getAverageEncounterDuration(peerNodeId: String): Long

    /** Average inter-contact time with a specific peer (ms). Computed from ContactEntity aggregate. */
    @Query("""
        SELECT CASE 
            WHEN total_encounters > 1 
            THEN total_inter_contact_time_ms / (total_encounters - 1) 
            ELSE 0 
        END 
        FROM contacts 
        WHERE node_id = :peerNodeId
    """)
    suspend fun getAverageInterContactTime(peerNodeId: String): Long

    /** All encounters with a peer, ordered chronologically. */
    @Query("SELECT * FROM encounters WHERE peer_node_id = :peerNodeId ORDER BY start_time_ms ASC")
    suspend fun getAllForPeer(peerNodeId: String): List<EncounterEntity>

    // ──────────────────────────────────────────────────────────────────────
    // Research / export
    // ──────────────────────────────────────────────────────────────────────

    /** All encounters for research export, ordered by time. */
    @Query("SELECT * FROM encounters ORDER BY start_time_ms ASC")
    suspend fun getAllForExport(): List<EncounterEntity>

    /** Encounters in a time range (for incremental export). */
    @Query("""
        SELECT * FROM encounters 
        WHERE start_time_ms BETWEEN :fromMs AND :toMs 
        ORDER BY start_time_ms ASC
    """)
    suspend fun getInTimeRange(fromMs: Long, toMs: Long): List<EncounterEntity>

    /** Total encounter count (for dashboard). */
    @Query("SELECT COUNT(*) FROM encounters")
    fun observeTotalEncounterCount(): Flow<Int>

    /** Count of unique peers encountered. */
    @Query("SELECT COUNT(DISTINCT peer_node_id) FROM encounters")
    suspend fun getUniquePeersEncountered(): Int

    // ──────────────────────────────────────────────────────────────────────
    // Cleanup
    // ──────────────────────────────────────────────────────────────────────

    /** Delete encounters older than a retention threshold (disk management). */
    @Query("DELETE FROM encounters WHERE start_time_ms < :thresholdMs")
    suspend fun purgeOlderThan(thresholdMs: Long): Int
}
