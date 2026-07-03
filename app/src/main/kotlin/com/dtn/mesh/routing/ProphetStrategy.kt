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
 * PRoPHET (Probabilistic Routing Protocol using History of Encounters and Transitivity)
 * routing strategy implementation.
 *
 * Reference: Lindgren et al., "Probabilistic Routing in Intermittently Connected Networks",
 * ACM SIGMOBILE Mobile Computing and Communications Review, 2003.
 *
 * ## Key parameters
 * - P_ENCOUNTER: initial/boost probability on direct contact
 * - GAMMA_AGING: multiplicative decay per aging interval
 * - BETA_TRANSITIVITY: weight for transitive probability updates
 *
 * ## Behaviour
 * - On encounter: P(a,b) = P(a,b)_old + (1 - P(a,b)_old) * P_ENCOUNTER
 * - Aging: P(a,b) = P(a,b) * GAMMA^k (k = intervals elapsed since last age)
 * - Transitivity: P(a,c) = P(a,c)_old + (1 - P(a,c)_old) * P(a,b) * P(b,c) * BETA
 *
 * Zero Android dependencies. Pure Kotlin, independently testable.
 */
class ProphetStrategy(
    private val config: ProphetConfig = ProphetConfig(),
) : RoutingStrategy {

    override val name: String = "PROPHET"

    /**
     * Delivery predictability table: P(local_node, peer).
     * Keys are peer NodeId strings.
     */
    private val pTable: MutableMap<String, Double> = mutableMapOf()

    /** Timestamp of last aging pass (epoch ms). */
    private var lastAgedAtMs: Long = System.currentTimeMillis()

    // ──────────────────────────────────────────────────────────────────────
    // Encounter handling
    // ──────────────────────────────────────────────────────────────────────

    override fun onEncounter(peerId: NodeId, contactRecord: ContactRecord) {
        val key = peerId.value
        val oldP = pTable.getOrDefault(key, 0.0)
        // PRoPHET encounter update: P(a,b) = P_old + (1 - P_old) * P_ENCOUNTER
        val newP = oldP + (1.0 - oldP) * config.pEncounter
        pTable[key] = newP.coerceIn(0.0, 1.0)
    }

    override fun onRoutingSummaryReceived(peerId: NodeId, summary: RoutingSummary) {
        if (summary.strategyTag != "PROPHET") return

        // Decode peer's P-vector and apply transitivity
        val peerVector = decodePVector(summary.payload)
        val pAB = pTable.getOrDefault(peerId.value, 0.0)

        for ((destKey, pBC) in peerVector) {
            if (destKey == peerId.value) continue // skip self-reference
            val oldPAC = pTable.getOrDefault(destKey, 0.0)
            // Transitivity: P(a,c) = P(a,c) + (1 - P(a,c)) * P(a,b) * P(b,c) * BETA
            val newPAC = oldPAC + (1.0 - oldPAC) * pAB * pBC * config.betaTransitivity
            pTable[destKey] = newPAC.coerceIn(0.0, 1.0)
        }
    }

    override fun buildRoutingSummary(): RoutingSummary {
        return RoutingSummary(
            strategyTag = "PROPHET",
            payload = encodePVector(pTable),
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // Aging
    // ──────────────────────────────────────────────────────────────────────

    override fun onPeriodicAge() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastAgedAtMs
        if (elapsed < config.agingIntervalMs) return

        val intervals = (elapsed / config.agingIntervalMs).toInt()
        if (intervals <= 0) return

        // P(a,b) = P(a,b) * gamma^k
        val decayFactor = Math.pow(config.gammaAging, intervals.toDouble())
        val iterator = pTable.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val aged = entry.value * decayFactor
            if (aged < config.pMinThreshold) {
                iterator.remove() // Prune negligible entries to bound memory
            } else {
                entry.setValue(aged)
            }
        }
        lastAgedAtMs = now
    }

    // ──────────────────────────────────────────────────────────────────────
    // Forwarding / Dropping
    // ──────────────────────────────────────────────────────────────────────

    override fun rankForForwarding(candidates: List<DtnMessage>, peerId: NodeId): List<ForwardCandidate> {
        // Our delivery probability TO the peer (proxy for peer's reachability)
        val peerP = pTable.getOrDefault(peerId.value, 0.0)

        return candidates.map { msg ->
            if (msg.destinationNodeId.value == peerId.value) {
                // Direct delivery — always forward with highest priority
                val ttlFraction = msg.remainingTtlMs().toDouble() /
                    max(msg.ttlMs, 1L).toDouble()
                ForwardCandidate(message = msg, priority = 1.0 * ttlFraction)
            } else {
                // PRoPHET forwarding rule: forward if peer is a better relay than us.
                // peerP = our confidence in reaching the peer (encounter-based).
                // ourP = our direct probability of reaching the destination.
                // Forward if peerP > ourP (peer is more likely to encounter dest than us).
                val ourP = pTable.getOrDefault(msg.destinationNodeId.value, 0.0)
                val shouldForward = peerP > ourP

                val ttlFraction = msg.remainingTtlMs().toDouble() /
                    max(msg.ttlMs, 1L).toDouble()
                val priority = if (shouldForward) peerP * ttlFraction else -1.0

                ForwardCandidate(message = msg, priority = priority)
            }
        }
            .filter { it.priority > 0.0 }
            .sortedByDescending { it.priority }
    }

    override fun rankForDrop(candidates: List<DtnMessage>, bytesToFree: Long): List<DtnMessage> {
        // Drop messages with lowest delivery probability first, then oldest
        return candidates.sortedWith(
            compareBy<DtnMessage> { pTable.getOrDefault(it.destinationNodeId.value, 0.0) }
                .thenBy { it.remainingTtlMs() }
        )
    }

    override fun getDeliveryProbability(peerId: NodeId): Double =
        pTable.getOrDefault(peerId.value, 0.0)

    // ──────────────────────────────────────────────────────────────────────
    // State persistence
    // ──────────────────────────────────────────────────────────────────────

    override fun exportState(): ByteArray = encodePVector(pTable)

    override fun importState(data: ByteArray) {
        pTable.clear()
        pTable.putAll(decodePVector(data))
    }

    // ──────────────────────────────────────────────────────────────────────
    // P-vector serialisation (compact binary: [count][key_len][key_bytes][double]...)
    // ──────────────────────────────────────────────────────────────────────

    private fun encodePVector(table: Map<String, Double>): ByteArray {
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

    private fun decodePVector(data: ByteArray): Map<String, Double> {
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
 * PRoPHET tunable parameters.
 */
data class ProphetConfig(
    /** Initial probability boost on direct encounter. RFC 6693 default: 0.75 */
    val pEncounter: Double = 0.75,
    /** Aging factor per interval. RFC 6693 default: 0.98 */
    val gammaAging: Double = 0.98,
    /** Transitivity scaling factor. RFC 6693 default: 0.25 */
    val betaTransitivity: Double = 0.25,
    /** Aging interval (ms). How often aging is applied. Default: 60s. */
    val agingIntervalMs: Long = 60_000L,
    /** Minimum P-value threshold; below this, entries are pruned. */
    val pMinThreshold: Double = 0.01,
)
