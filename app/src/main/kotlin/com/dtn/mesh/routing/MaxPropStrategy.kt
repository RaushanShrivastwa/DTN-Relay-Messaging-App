package com.dtn.mesh.routing

import com.dtn.mesh.model.ContactRecord
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.NodeId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.max

/**
 * MaxProp routing strategy implementation.
 *
 * Reference: Burgess et al., "MaxProp: Routing for Vehicle-Based Disruption-
 * Tolerant Networks", IEEE INFOCOM 2006.
 *
 * ## Core concepts
 * - **Path likelihoods f(i,j):** estimated probability of meeting peer j,
 *   normalised across all known peers (sum to 1.0).
 * - **Cost estimation:** shortest-path cost to destination via known peers,
 *   computed from path likelihoods (lower f = higher cost = less likely path).
 * - **TTL-aware prioritised drop:** under buffer pressure, drop messages with
 *   highest estimated delivery cost first (least likely to be deliverable).
 * - **Complementary to PRoPHET:** MaxProp reasons about path costs while
 *   PRoPHET reasons about encounter probability.
 *
 * ## Simplification for LoRa DTN
 * Full MaxProp uses Dijkstra over the entire network graph. We simplify to
 * direct-delivery likelihood (single-hop cost) since LoRa mesh encounters
 * are typically 1-2 hops deep in practice. This makes the strategy tractable
 * for resource-constrained Android devices.
 *
 * Zero Android dependencies. Pure Kotlin.
 */
class MaxPropStrategy(
    val config: MaxPropConfig = MaxPropConfig(),
) : RoutingStrategy {

    override val name: String = "MAXPROP"

    /**
     * Path likelihood table: f(local, peer_i).
     * Updated on each encounter. Normalised to sum = 1.0 across all peers.
     */
    private val fTable: MutableMap<String, Double> = mutableMapOf()

    /** Raw encounter counts per peer — used to compute path likelihoods. */
    private val encounterCounts: MutableMap<String, Int> = mutableMapOf()

    /** Total encounters observed (denominator for likelihood). */
    private var totalEncounters: Int = 0

    // ──────────────────────────────────────────────────────────────────────
    // Encounter handling
    // ──────────────────────────────────────────────────────────────────────

    override fun onEncounter(peerId: NodeId, contactRecord: ContactRecord) {
        val key = peerId.value
        encounterCounts[key] = (encounterCounts[key] ?: 0) + 1
        totalEncounters++
        recomputeLikelihoods()
    }

    override fun onRoutingSummaryReceived(peerId: NodeId, summary: RoutingSummary) {
        if (summary.strategyTag != "MAXPROP") return

        // Integrate peer's likelihood vector to improve our cost estimates.
        // MaxProp propagation: augment our view with peer's encounter distribution.
        val peerLikelihoods = decodeLikelihoodVector(summary.payload)
        for ((destKey, peerF) in peerLikelihoods) {
            if (destKey == peerId.value) continue
            // If we have no data on this destination, seed from peer's estimate (discounted)
            if (destKey !in fTable) {
                fTable[destKey] = peerF * config.transitivityDiscount
            }
        }
        // Re-normalise after integration
        normalise()
    }

    override fun buildRoutingSummary(): RoutingSummary {
        return RoutingSummary(
            strategyTag = "MAXPROP",
            payload = encodeLikelihoodVector(fTable),
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // Likelihood computation
    // ──────────────────────────────────────────────────────────────────────

    private fun recomputeLikelihoods() {
        if (totalEncounters == 0) return
        for ((key, count) in encounterCounts) {
            fTable[key] = count.toDouble() / totalEncounters.toDouble()
        }
        normalise()
    }

    private fun normalise() {
        val sum = fTable.values.sum()
        if (sum <= 0.0) return
        for (key in fTable.keys) {
            fTable[key] = (fTable[key] ?: 0.0) / sum
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Forwarding / Dropping
    // ──────────────────────────────────────────────────────────────────────

    override fun rankForForwarding(candidates: List<DtnMessage>, peerId: NodeId): List<ForwardCandidate> {
        val peerF = fTable.getOrDefault(peerId.value, 0.0)

        return candidates.map { msg ->
            val destKey = msg.destinationNodeId.value

            // Cost to destination via this peer: lower f = higher cost.
            // Priority = f(peer, dest) for direct delivery, or f(peer) as proxy.
            val deliveryCost = if (destKey == peerId.value) {
                1.0 // Direct delivery — maximum priority
            } else {
                // Simplified: use peer's likelihood as proxy for reachability
                peerF
            }

            val ttlFraction = msg.remainingTtlMs().toDouble() / max(msg.ttlMs, 1L).toDouble()
            val priority = deliveryCost * ttlFraction

            ForwardCandidate(message = msg, priority = priority)
        }
            .filter { it.priority > 0.0 }
            .sortedByDescending { it.priority }
    }

    override fun rankForDrop(candidates: List<DtnMessage>, bytesToFree: Long): List<DtnMessage> {
        // MaxProp drop policy: highest delivery cost first (least likely to succeed).
        // Cost = 1 - f(dest). Messages to rarely-seen destinations are dropped first.
        // Tie-break: shortest remaining TTL dropped first (least time to recover).
        return candidates.sortedWith(
            compareByDescending<DtnMessage> { deliveryCost(it) }
                .thenBy { it.remainingTtlMs() }
        )
    }

    /**
     * Estimated delivery cost for a message.
     * Cost = 1 - f(destination). Higher cost = less likely to deliver.
     */
    private fun deliveryCost(msg: DtnMessage): Double {
        val destF = fTable.getOrDefault(msg.destinationNodeId.value, 0.0)
        return 1.0 - destF
    }

    override fun getDeliveryProbability(peerId: NodeId): Double =
        fTable.getOrDefault(peerId.value, 0.0)

    // ──────────────────────────────────────────────────────────────────────
    // Aging
    // ──────────────────────────────────────────────────────────────────────

    override fun onPeriodicAge() {
        // MaxProp doesn't explicitly age like PRoPHET.
        // Likelihoods naturally dilute as totalEncounters grows (frequency-based).
        // Optional: apply a recency window by decaying old encounter counts.
        if (!config.enableRecencyDecay) return

        val iterator = encounterCounts.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val decayed = (entry.value * config.recencyDecayFactor).toInt()
            if (decayed <= 0) {
                iterator.remove()
            } else {
                entry.setValue(decayed)
            }
        }
        totalEncounters = encounterCounts.values.sum()
        recomputeLikelihoods()
    }

    // ──────────────────────────────────────────────────────────────────────
    // State persistence
    // ──────────────────────────────────────────────────────────────────────

    override fun exportState(): ByteArray {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        // Write encounter counts
        dos.writeInt(encounterCounts.size)
        for ((key, count) in encounterCounts) {
            dos.writeUTF(key)
            dos.writeInt(count)
        }
        dos.writeInt(totalEncounters)
        // Write f-table
        dos.writeInt(fTable.size)
        for ((key, value) in fTable) {
            dos.writeUTF(key)
            dos.writeDouble(value)
        }
        dos.flush()
        return bos.toByteArray()
    }

    override fun importState(data: ByteArray) {
        if (data.isEmpty()) return
        val dis = DataInputStream(ByteArrayInputStream(data))

        encounterCounts.clear()
        val countSize = dis.readInt()
        repeat(countSize) {
            val key = dis.readUTF()
            val count = dis.readInt()
            encounterCounts[key] = count
        }
        totalEncounters = dis.readInt()

        fTable.clear()
        val fSize = dis.readInt()
        repeat(fSize) {
            val key = dis.readUTF()
            val value = dis.readDouble()
            fTable[key] = value
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Serialisation helpers for routing summary exchange
    // ──────────────────────────────────────────────────────────────────────

    private fun encodeLikelihoodVector(table: Map<String, Double>): ByteArray {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        dos.writeInt(table.size)
        for ((key, value) in table) {
            dos.writeUTF(key)
            dos.writeDouble(value)
        }
        dos.flush()
        return bos.toByteArray()
    }

    private fun decodeLikelihoodVector(data: ByteArray): Map<String, Double> {
        if (data.isEmpty()) return emptyMap()
        val dis = DataInputStream(ByteArrayInputStream(data))
        val count = dis.readInt()
        val map = mutableMapOf<String, Double>()
        repeat(count) {
            val key = dis.readUTF()
            val value = dis.readDouble()
            map[key] = value
        }
        return map
    }
}

/**
 * MaxProp tunable parameters.
 */
data class MaxPropConfig(
    /** Discount factor applied when integrating peer's likelihood estimates. */
    val transitivityDiscount: Double = 0.5,
    /** Whether to enable recency-weighted decay of encounter counts. */
    val enableRecencyDecay: Boolean = true,
    /** Multiplicative factor for decaying old encounter counts each aging cycle. */
    val recencyDecayFactor: Double = 0.9,
)
