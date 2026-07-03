package com.dtn.mesh.model

/**
 * Snapshot of per-peer routing metrics maintained by the DTN layer.
 * Used by [RoutingStrategy] and the Q-learning engine to make forwarding decisions.
 */
data class RoutingMetrics(
    /** The peer these metrics apply to. */
    val peerId: NodeId,
    /** PRoPHET delivery predictability P(a,b) ∈ [0,1]. */
    val deliveryProbability: Double = 0.0,
    /** Total number of encounters with this peer. */
    val totalEncounters: Int = 0,
    /** Average encounter duration (ms). */
    val avgEncounterDurationMs: Long = 0,
    /** Average inter-contact time (ms). */
    val avgInterContactTimeMs: Long = 0,
    /** Last encounter timestamp (epoch ms). */
    val lastEncounterMs: Long = 0,
    /** Historical delivery success rate: delivered / attempted for this peer. */
    val historicalSuccessRate: Double = 0.0,
    /** Messages successfully delivered via this peer. */
    val deliverySuccessCount: Int = 0,
    /** Messages attempted (forwarded) to this peer. */
    val deliveryAttemptCount: Int = 0,
)
