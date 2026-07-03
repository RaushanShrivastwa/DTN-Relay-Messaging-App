package com.dtn.mesh.learning

/**
 * Observation/feature vector fed into the Q-engine at each forwarding decision point.
 * All values are normalised to [0.0, 1.0] before discretisation into state keys.
 *
 * Zero Android framework dependencies — independently testable.
 */
data class ForwardingState(
    /** Normalised RSSI: (rssi - minRssi) / (maxRssi - minRssi). */
    val rssiNorm: Double,
    /** Normalised SNR. */
    val snrNorm: Double,
    /** Encounter frequency with the candidate peer: contacts per hour, normalised. */
    val encounterFrequency: Double,
    /** Average encounter duration, normalised [0,1]. */
    val encounterDuration: Double,
    /** Delivery predictability from the active RoutingStrategy (e.g. PRoPHET P-value). */
    val deliveryProbability: Double,
    /** Remaining TTL as fraction of original: remainingTtl / maxTtl. */
    val remainingTtlFraction: Double,
    /** Current buffer occupancy fraction: usedBytes / maxBytes. */
    val bufferOccupancy: Double,
    /** Historical delivery success rate for messages sent through this peer. */
    val historicalSuccessRate: Double,
    /** Duplicate count (same message ID seen): normalised. */
    val duplicateCountNorm: Double,
)
