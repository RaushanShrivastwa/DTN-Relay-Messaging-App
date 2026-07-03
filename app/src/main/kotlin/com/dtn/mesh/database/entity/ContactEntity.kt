package com.dtn.mesh.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing cumulative contact/routing state for a known peer node.
 *
 * This is the persistent record updated on each encounter. It stores:
 * - PRoPHET delivery predictability (P-value) and aging state
 * - MaxProp path likelihood running averages
 * - Aggregate encounter statistics for Q-learning state features
 * - Per-peer delivery tracking for historicalSuccessRate
 *
 * One row per peer node. Updated transactionally on each encounter event.
 */
@Entity(
    tableName = "contacts",
    indices = [
        Index(value = ["last_encounter_ms"]),
        Index(value = ["delivery_probability"], orders = [Index.Order.DESC]),
    ]
)
data class ContactEntity(
    /** Meshtastic node ID string ("!aabbccdd"). */
    @PrimaryKey
    @ColumnInfo(name = "node_id")
    val nodeId: String,

    // ──────────────────────────────────────────────────────────────────────
    // PRoPHET state
    // ──────────────────────────────────────────────────────────────────────

    /** PRoPHET delivery predictability P(local, this_peer) ∈ [0, 1]. */
    @ColumnInfo(name = "delivery_probability")
    val deliveryProbability: Double = 0.0,

    /** Timestamp of last PRoPHET aging calculation (epoch ms). */
    @ColumnInfo(name = "last_aged_at_ms")
    val lastAgedAtMs: Long = 0,

    // ──────────────────────────────────────────────────────────────────────
    // MaxProp state
    // ──────────────────────────────────────────────────────────────────────

    /**
     * MaxProp path likelihood f(local → this_peer) ∈ [0, 1].
     * Represents estimated probability of meeting this peer in the near future.
     * Normalised across all known contacts.
     */
    @ColumnInfo(name = "path_likelihood")
    val pathLikelihood: Double = 0.0,

    // ──────────────────────────────────────────────────────────────────────
    // Encounter statistics (inputs to Q-learning ForwardingState)
    // ──────────────────────────────────────────────────────────────────────

    /** Total number of direct encounters (contact windows). */
    @ColumnInfo(name = "total_encounters")
    val totalEncounters: Int = 0,

    /** Cumulative encounter duration (ms). avgDuration = totalDurationMs / totalEncounters. */
    @ColumnInfo(name = "total_encounter_duration_ms")
    val totalEncounterDurationMs: Long = 0,

    /** Timestamp of the very first encounter (epoch ms). */
    @ColumnInfo(name = "first_encounter_ms")
    val firstEncounterMs: Long = 0,

    /** Timestamp of the most recent encounter (epoch ms). */
    @ColumnInfo(name = "last_encounter_ms")
    val lastEncounterMs: Long = 0,

    /** Best (highest / least negative) RSSI ever observed from this peer. */
    @ColumnInfo(name = "best_rssi")
    val bestRssi: Int = -200,

    /** Best SNR ever observed from this peer. */
    @ColumnInfo(name = "best_snr")
    val bestSnr: Float = -100f,

    /** Most recent RSSI from this peer. */
    @ColumnInfo(name = "last_rssi")
    val lastRssi: Int = -200,

    /** Most recent SNR from this peer. */
    @ColumnInfo(name = "last_snr")
    val lastSnr: Float = -100f,

    // ──────────────────────────────────────────────────────────────────────
    // Delivery tracking (per-peer historicalSuccessRate)
    // ──────────────────────────────────────────────────────────────────────

    /** Number of messages we forwarded TO this peer that were eventually delivered. */
    @ColumnInfo(name = "delivery_success_count")
    val deliverySuccessCount: Int = 0,

    /** Number of messages we attempted to forward TO this peer (regardless of outcome). */
    @ColumnInfo(name = "delivery_attempt_count")
    val deliveryAttemptCount: Int = 0,

    // ──────────────────────────────────────────────────────────────────────
    // Inter-contact time tracking
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Cumulative inter-contact time (ms).
     * avgInterContactTime = totalInterContactTimeMs / (totalEncounters - 1).
     * Only valid when totalEncounters >= 2.
     */
    @ColumnInfo(name = "total_inter_contact_time_ms")
    val totalInterContactTimeMs: Long = 0,

    // ──────────────────────────────────────────────────────────────────────
    // Metadata
    // ──────────────────────────────────────────────────────────────────────

    /** Human-readable long name from Meshtastic NodeInfo, if known. */
    @ColumnInfo(name = "long_name")
    val longName: String? = null,

    /** Short name from Meshtastic NodeInfo. */
    @ColumnInfo(name = "short_name")
    val shortName: String? = null,

    /** Hardware model string, if known. */
    @ColumnInfo(name = "hw_model")
    val hwModel: String? = null,

    /** Whether this peer is currently reachable (last NODE_CHANGE showed online). */
    @ColumnInfo(name = "is_online")
    val isOnline: Boolean = false,
)
