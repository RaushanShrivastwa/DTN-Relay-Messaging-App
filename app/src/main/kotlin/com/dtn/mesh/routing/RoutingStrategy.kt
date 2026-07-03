package com.dtn.mesh.routing

import com.dtn.mesh.model.ContactRecord
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.NodeId

/**
 * Pluggable DTN routing strategy interface.
 *
 * Implementations (PRoPHET, MaxProp) must be pure Kotlin — no Android framework
 * dependencies — so they are unit-testable in isolation with synthetic data.
 *
 * The active strategy is selected at runtime via [StrategySelector].
 */
interface RoutingStrategy {

    /** Human-readable name for logging and UI. */
    val name: String

    /**
     * Called when a direct encounter with [peerId] is observed.
     * Update delivery predictability / path likelihood tables.
     */
    fun onEncounter(peerId: NodeId, contactRecord: ContactRecord)

    /**
     * Called with a peer's routing summary received during encounter handshake.
     * PRoPHET uses this for transitivity; MaxProp for path-likelihood propagation.
     */
    fun onRoutingSummaryReceived(peerId: NodeId, summary: RoutingSummary)

    /** Produce our own routing summary to share with a newly-encountered peer. */
    fun buildRoutingSummary(): RoutingSummary

    /**
     * Rank buffered messages for forwarding to [peerId].
     * Returns ordered list: highest priority first.
     */
    fun rankForForwarding(candidates: List<DtnMessage>, peerId: NodeId): List<ForwardCandidate>

    /**
     * Under buffer pressure, rank messages for dropping (worst-first).
     * @param bytesToFree How many bytes of buffer to reclaim.
     */
    fun rankForDrop(candidates: List<DtnMessage>, bytesToFree: Long): List<DtnMessage>

    /** Periodic aging — called each scheduler cycle. */
    fun onPeriodicAge()

    /** Get delivery predictability for a specific peer. */
    fun getDeliveryProbability(peerId: NodeId): Double

    /** Serialise internal state for persistence across restarts. */
    fun exportState(): ByteArray

    /** Restore internal state from a previous [exportState] snapshot. */
    fun importState(data: ByteArray)
}
