package com.dtn.mesh.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity recording each forwarding decision made by the Q-learning engine.
 *
 * This is a **research instrumentation table** — not required for operational correctness,
 * but critical for offline experiment analysis. Each row captures:
 * - The state vector that was input to the Q-engine
 * - The action selected (STORE / FORWARD / WAIT / DROP)
 * - The Q-values at decision time
 * - The eventual reward signal (backfilled when outcome is known)
 *
 * This table can grow large. A retention policy (configurable via settings) controls purging.
 */
@Entity(
    tableName = "forwarding_decisions",
    indices = [
        Index(value = ["timestamp_ms"]),
        Index(value = ["message_id"]),
        Index(value = ["action"]),
    ]
)
data class ForwardingDecisionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** When this decision was made (epoch ms). */
    @ColumnInfo(name = "timestamp_ms")
    val timestampMs: Long,

    /** The DTN message this decision pertains to. */
    @ColumnInfo(name = "message_id")
    val messageId: String,

    /** The peer being evaluated as a forwarding candidate. */
    @ColumnInfo(name = "peer_node_id")
    val peerNodeId: String,

    // ── State vector (ForwardingState) ──

    @ColumnInfo(name = "state_rssi_norm") val stateRssiNorm: Double,
    @ColumnInfo(name = "state_snr_norm") val stateSnrNorm: Double,
    @ColumnInfo(name = "state_encounter_frequency") val stateEncounterFrequency: Double,
    @ColumnInfo(name = "state_encounter_duration") val stateEncounterDuration: Double,
    @ColumnInfo(name = "state_delivery_probability") val stateDeliveryProbability: Double,
    @ColumnInfo(name = "state_remaining_ttl_fraction") val stateRemainingTtlFraction: Double,
    @ColumnInfo(name = "state_buffer_occupancy") val stateBufferOccupancy: Double,
    @ColumnInfo(name = "state_historical_success_rate") val stateHistoricalSuccessRate: Double,
    @ColumnInfo(name = "state_duplicate_count_norm") val stateDuplicateCountNorm: Double,

    // ── Decision output ──

    /** Action selected: STORE, FORWARD, WAIT, or DROP. */
    @ColumnInfo(name = "action")
    val action: String,

    /** Q-value of selected action at decision time. */
    @ColumnInfo(name = "q_value_selected")
    val qValueSelected: Double,

    /** Epsilon at decision time (exploration rate). */
    @ColumnInfo(name = "epsilon")
    val epsilon: Double,

    /** Whether this was an exploratory action (random) vs exploitative (greedy). */
    @ColumnInfo(name = "was_exploratory")
    val wasExploratory: Boolean,

    /** Which routing strategy was active (PROPHET / MAXPROP). */
    @ColumnInfo(name = "routing_strategy")
    val routingStrategy: String,

    // ── Reward (backfilled) ──

    /** Reward signal assigned after outcome is known. Null until backfilled. */
    @ColumnInfo(name = "reward")
    val reward: Double? = null,

    /** Whether this decision has been used in a Q-update step. */
    @ColumnInfo(name = "update_applied")
    val updateApplied: Boolean = false,
)
