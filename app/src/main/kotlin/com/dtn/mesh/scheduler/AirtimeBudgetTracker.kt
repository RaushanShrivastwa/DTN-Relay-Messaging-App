package com.dtn.mesh.scheduler

/**
 * Token-bucket rate limiter for LoRa airtime management.
 *
 * Enforces two limits:
 * - Per-encounter: max sends to any single peer in one encounter window
 * - Aggregate: max total sends per time window across all peers
 *
 * Refills at a constant rate. Thread-safe via synchronised access
 * (called only from DtnOrchestrator's single-threaded dispatcher,
 * but synchronized defensively).
 */
class AirtimeBudgetTracker(
    /** Max sends to a single peer per encounter. */
    private val maxSendsPerEncounter: Int = 5,
    /** Max aggregate sends per refill window. */
    private val maxSendsPerWindow: Int = 10,
    /** Refill window duration (ms). Default: 1 minute. */
    private val windowDurationMs: Long = 60_000L,
) {
    private var aggregateTokens: Int = maxSendsPerWindow
    private var lastRefillMs: Long = System.currentTimeMillis()

    /** Per-encounter counters: reset when a new encounter starts. */
    private val encounterCounters: MutableMap<String, Int> = mutableMapOf()

    @Synchronized
    fun canSend(peerId: String): Boolean {
        refillIfNeeded()
        val peerCount = encounterCounters.getOrDefault(peerId, 0)
        return aggregateTokens > 0 && peerCount < maxSendsPerEncounter
    }

    @Synchronized
    fun recordSend(peerId: String) {
        refillIfNeeded()
        aggregateTokens = (aggregateTokens - 1).coerceAtLeast(0)
        encounterCounters[peerId] = (encounterCounters[peerId] ?: 0) + 1
    }

    @Synchronized
    fun resetEncounter(peerId: String) {
        encounterCounters.remove(peerId)
    }

    @Synchronized
    fun remainingBudget(): Int {
        refillIfNeeded()
        return aggregateTokens
    }

    private fun refillIfNeeded() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastRefillMs
        if (elapsed >= windowDurationMs) {
            aggregateTokens = maxSendsPerWindow
            lastRefillMs = now
        }
    }
}
