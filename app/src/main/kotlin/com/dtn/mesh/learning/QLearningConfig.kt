package com.dtn.mesh.learning

/**
 * Hyperparameter bundle for the Double Q-Learning engine.
 * Injectable/configurable for ablation studies.
 *
 * ## State space sizing rationale
 * DTN encounters are sparse: a typical research deployment generates ~3,000 forwarding
 * decisions. Per-feature bucket counts are tuned so that the effective state space
 * (~10,800 states) allows meaningful convergence in common scenarios while the long
 * tail of rare states defaults to conservative STORE behaviour (optimistic init at 0).
 *
 * ## Per-feature granularity
 * - Fine (5 buckets): deliveryProbability, remainingTtlFraction — high info value
 * - Medium (3 buckets): bufferOccupancy, historicalSuccessRate, encounterFrequency
 * - Coarse (2 buckets / binary): rssi, snr, encounterDuration, duplicateCount
 *
 * Effective state space: 5×5×3×3×3×2×2×2×2 = 10,800
 */
data class QLearningConfig(
    /** Step size for Q-value updates. */
    val learningRate: Double = 0.1,
    /** Discount factor γ for future rewards. */
    val discountFactor: Double = 0.9,
    /** Initial exploration probability. */
    val epsilonInitial: Double = 1.0,
    /** Minimum exploration probability (floor). */
    val epsilonMin: Double = 0.05,
    /** Multiplicative decay applied to epsilon after each update. */
    val epsilonDecay: Double = 0.995,

    // ── Per-feature discretisation granularity ──────────────────────────

    /** Buckets for deliveryProbability — core routing signal, fine resolution. */
    val bucketsDeliveryProbability: Int = 5,
    /** Buckets for remainingTtlFraction — urgency drives forward/wait/drop. */
    val bucketsRemainingTtl: Int = 5,
    /** Buckets for bufferOccupancy — low/medium/high pressure. */
    val bucketsBufferOccupancy: Int = 3,
    /** Buckets for historicalSuccessRate — low/medium/high reliability. */
    val bucketsHistoricalSuccess: Int = 3,
    /** Buckets for encounterFrequency — rare/occasional/frequent. */
    val bucketsEncounterFrequency: Int = 3,
    /** Buckets for rssiNorm — good/poor signal (binary). */
    val bucketsRssi: Int = 2,
    /** Buckets for snrNorm — good/poor (binary). */
    val bucketsSnr: Int = 2,
    /** Buckets for encounterDuration — brief/sustained (binary). */
    val bucketsEncounterDuration: Int = 2,
    /** Buckets for duplicateCountNorm — novel/already-spread (binary). */
    val bucketsDuplicateCount: Int = 2,
) {
    /**
     * Effective state space size with current bucket configuration.
     * Default: 5×5×3×3×3×2×2×2×2 = 10,800
     */
    val effectiveStateSpace: Int
        get() = bucketsDeliveryProbability * bucketsRemainingTtl *
            bucketsBufferOccupancy * bucketsHistoricalSuccess * bucketsEncounterFrequency *
            bucketsRssi * bucketsSnr * bucketsEncounterDuration * bucketsDuplicateCount
}
