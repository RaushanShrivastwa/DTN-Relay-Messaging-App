package com.dtn.mesh.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity recording each individual encounter (contact window) with a peer.
 *
 * This table provides the raw time-series data for:
 * - Computing encounter frequency and inter-contact time distributions
 * - Feeding the Q-learning engine's encounterFrequency/encounterDuration state features
 * - Research data export (CSV/JSON of contact history for offline analysis)
 *
 * One row per encounter. An encounter starts when we first receive a packet from a peer
 * after a gap exceeding [ENCOUNTER_GAP_THRESHOLD_MS] and ends when the gap is exceeded again.
 *
 * Foreign key to [ContactEntity] ensures referential integrity.
 */
@Entity(
    tableName = "encounters",
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["node_id"],
            childColumns = ["peer_node_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index(value = ["peer_node_id"]),
        Index(value = ["start_time_ms"]),
    ]
)
data class EncounterEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** The peer node ID ("!aabbccdd") this encounter was with. */
    @ColumnInfo(name = "peer_node_id")
    val peerNodeId: String,

    /** When the encounter window began (epoch ms). */
    @ColumnInfo(name = "start_time_ms")
    val startTimeMs: Long,

    /** When the encounter window ended (epoch ms). 0 if still ongoing. */
    @ColumnInfo(name = "end_time_ms")
    val endTimeMs: Long = 0,

    /** Duration of the encounter (ms). Computed as endTimeMs - startTimeMs when finalised. */
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long = 0,

    /** Number of packets exchanged during this encounter. */
    @ColumnInfo(name = "packet_count")
    val packetCount: Int = 1,

    /** Best RSSI observed during this encounter window. */
    @ColumnInfo(name = "best_rssi")
    val bestRssi: Int = -200,

    /** Best SNR observed during this encounter window. */
    @ColumnInfo(name = "best_snr")
    val bestSnr: Float = -100f,

    /** Average RSSI across all packets in this encounter. */
    @ColumnInfo(name = "avg_rssi")
    val avgRssi: Float = -200f,

    /** Average SNR across all packets in this encounter. */
    @ColumnInfo(name = "avg_snr")
    val avgSnr: Float = -100f,

    /** Number of messages forwarded to this peer during this encounter. */
    @ColumnInfo(name = "messages_forwarded")
    val messagesForwarded: Int = 0,

    /** Number of messages received from this peer during this encounter. */
    @ColumnInfo(name = "messages_received")
    val messagesReceived: Int = 0,

    /** Whether a routing summary was exchanged during this encounter. */
    @ColumnInfo(name = "routing_summary_exchanged")
    val routingSummaryExchanged: Boolean = false,
) {
    companion object {
        /**
         * Minimum gap (ms) between packets from the same peer to consider them
         * separate encounters rather than a continuation.
         * Default: 5 minutes. Tunable for research experiments.
         */
        const val ENCOUNTER_GAP_THRESHOLD_MS: Long = 5 * 60 * 1000L
    }
}
