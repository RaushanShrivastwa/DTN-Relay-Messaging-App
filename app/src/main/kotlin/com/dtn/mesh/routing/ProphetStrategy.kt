package com.dtn.mesh.routing

import com.dtn.mesh.model.ContactRecord
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.NodeId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.max
import kotlin.math.pow

/**
 * PRoPHET routing strategy — the app's implementation of the design notes.
 *
 * Reference: Lindgren et al., "Probabilistic Routing in Intermittently Connected Networks",
 * ACM SIGMOBILE Mobile Computing and Communications Review, 2003.
 *
 * The variant implemented here is the version specified by the project notes:
 *
 * 1. **Direct encounter update** (distance-aware — Page 1 of the notes):
 *    P(a,b) = P(a,b)_old + (1 − P(a,b)_old) · P_init + ε^d
 *    where `d` is the estimated distance to the peer. If `d` is unknown (no RSSI) the
 *    distance term is skipped and the update degenerates to standard PROPHET.
 *
 * 2. **Aging** (when time passes with no encounter):
 *    P(a,b) = P(a,b)_old · γ^k    where k = intervals elapsed
 *
 * 3. **Transitivity** (learned via routing-summary exchange):
 *    P(a,c) = P(a,c)_old + (1 − P(a,c)_old) · P(a,b) · P(b,c) · β
 *
 * 4. **Two-stage routing** (Page 3 §4):
 *    - Stage 1 (Epidemic) while `forwardCount ≤ N_m` AND `hopCount ≤ H_m`: copy to any
 *      candidate peer. This ensures broad early spread.
 *    - Stage 2 (Smart Spread) once either limit is exceeded: copy to peer `j` only if
 *      j's own P(j, dest) is strictly greater than our P(i, dest). If we haven't yet
 *      received j's routing summary we conservatively don't forward under Stage 2.
 *
 * All state is in-memory and pure-Kotlin — no Android dependencies, unit-testable.
 */
class ProphetStrategy(
    val config: ProphetConfig = ProphetConfig(),
) : RoutingStrategy {

    override val name: String = "PROPHET"

    /**
     * P(local, peer) — our own delivery predictability vector.
     * Key is peer NodeId string. Values are always in [0, 1].
     */
    private val pTable: MutableMap<String, Double> = mutableMapOf()

    /**
     * Cached P-vectors reported *by* each peer during routing-summary exchange.
     * Structure: `peerPTables[peer_j][dest_D] = P(j, D)`. Consulted during Stage 2
     * forwarding to decide whether peer j is a better carrier for a given destination
     * than we are. Entries stay until overwritten by a fresher summary from j.
     */
    private val peerPTables: MutableMap<String, MutableMap<String, Double>> = mutableMapOf()

    /** Timestamp of last aging pass (epoch ms). */
    private var lastAgedAtMs: Long = System.currentTimeMillis()

    // ══════════════════════════════════════════════════════════════════════
    // Encounter handling
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Direct-encounter update — Eq. 2 from Page 1 of the notes.
     *
     * Adds `ε^d` on top of the classic PROPHET boost when the contact record carries a
     * valid distance estimate. Missing distance (`distanceMeters < 0`) skips the boost
     * so a noisy or unavailable RSSI reading never poisons the P-table.
     */
    override fun onEncounter(peerId: NodeId, contactRecord: ContactRecord) {
        val key = peerId.value
        val oldP = pTable.getOrDefault(key, 0.0)
        val classic = oldP + (1.0 - oldP) * config.pEncounter
        val distanceBoost = distanceBoost(contactRecord.distanceMeters)
        val newP = (classic + distanceBoost).coerceIn(0.0, 1.0)
        pTable[key] = newP
    }

    /**
     * Compute the `ε^d` distance term. Returns 0 if `d` is unknown (sentinel < 0) so
     * the formula gracefully degrades to standard PROPHET.
     */
    private fun distanceBoost(distanceMeters: Double): Double {
        if (distanceMeters < 0.0) return 0.0
        val eps = config.epsilonDistance.coerceIn(0.0, 0.9999)
        // eps^0 = 1 (infinite boost for zero distance) — guard against that with a floor.
        val d = distanceMeters.coerceAtLeast(0.5)
        val term = eps.pow(d)
        return if (term.isFinite()) term else 0.0
    }

    override fun onRoutingSummaryReceived(peerId: NodeId, summary: RoutingSummary) {
        if (summary.strategyTag != TAG) return

        // Decode peer's P-vector once, use it for BOTH transitivity and for the peer-P cache.
        val peerVector = decodePVector(summary.payload)

        // Empty payloads (e.g. LoRa-hub keep-alive heartbeats) must NOT wipe out a real
        // P-vector we've previously cached for this peer. Treat empty as a no-op learning
        // packet — the caller has already touched last-seen via the encounter path.
        if (peerVector.isEmpty()) return

        // Cache peer's reported P-values for Stage 2 forwarding decisions.
        peerPTables[peerId.value] = peerVector.toMutableMap()

        // Transitivity: P(a,c) = P(a,c)_old + (1 − P(a,c)_old) · P(a,b) · P(b,c) · β
        val pAB = pTable.getOrDefault(peerId.value, 0.0)
        for ((destKey, pBC) in peerVector) {
            if (destKey == peerId.value) continue // ignore self-reference
            val oldPAC = pTable.getOrDefault(destKey, 0.0)
            val newPAC = oldPAC + (1.0 - oldPAC) * pAB * pBC * config.betaTransitivity
            pTable[destKey] = newPAC.coerceIn(0.0, 1.0)
        }
    }

    override fun buildRoutingSummary(): RoutingSummary =
        RoutingSummary(strategyTag = TAG, payload = encodePVector(pTable))

    // ══════════════════════════════════════════════════════════════════════
    // Aging
    // ══════════════════════════════════════════════════════════════════════

    override fun onPeriodicAge() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastAgedAtMs
        if (elapsed < config.agingIntervalMs) return

        val intervals = (elapsed / config.agingIntervalMs).toInt()
        if (intervals <= 0) return

        // P(a,b) = P(a,b) · γ^k
        val decayFactor = config.gammaAging.pow(intervals)
        val iterator = pTable.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val aged = entry.value * decayFactor
            if (aged < config.pMinThreshold) {
                iterator.remove() // Prune negligible entries to bound memory.
            } else {
                entry.setValue(aged)
            }
        }
        lastAgedAtMs = now
    }

    // ══════════════════════════════════════════════════════════════════════
    // Live topology: multi-hop path finding
    // ══════════════════════════════════════════════════════════════════════

    /**
     * BFS over the known live topology to find if a multi-hop path exists to [dest]
     * through currently-online peers.
     *
     * Graph construction:
     * - Node "me" has edges to every peer in [onlinePeers] (1-hop neighbors).
     * - For each peer j whose routing summary we've cached, j has edges to every node k
     *   where `P(j, k) >= config.pFloorPath` (meaning j is a plausible carrier toward k,
     *   including transitive/multi-hop beliefs, not just strong direct links).
     *
     * Returns the **first hop** (the online peer I should forward to), or null if no
     * multi-hop path exists. This keeps the search O(peers²) which is fast for <20 nodes.
     */
    fun findNextHopToward(dest: String, onlinePeers: Set<String>): String? {
        if (dest in onlinePeers) return dest // Tier 1: direct — but caller usually handles this

        // Build adjacency: node → set of reachable neighbors
        val adj = mutableMapOf<String, MutableSet<String>>()
        val ME = "__self__"
        adj[ME] = onlinePeers.toMutableSet()

        for (peer in onlinePeers) {
            val peerNeighbors = adj.getOrPut(peer) { mutableSetOf() }
            // From the peer's P-vector we know who THEY can likely reach.
            val peerVector = peerPTables[peer] ?: continue
            for ((target, p) in peerVector) {
                // Use the configurable path floor instead of a hard 0.5 so that transitive
                // predictability (typically ~0.14 for a solid 2-hop link) still forms an edge.
                if (p >= config.pFloorPath && target != peer) {
                    peerNeighbors.add(target)
                }
            }
        }

        // BFS from ME to dest
        val queue: ArrayDeque<String> = ArrayDeque()
        val visited = mutableSetOf(ME)
        val parent = mutableMapOf<String, String>() // child → parent for path reconstruction
        queue.add(ME)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (neighbor in adj[current] ?: emptySet()) {
                if (neighbor in visited) continue
                visited.add(neighbor)
                parent[neighbor] = current
                if (neighbor == dest) {
                    // Reconstruct: find the first hop (child of ME on the path to dest)
                    var node = dest
                    while (parent[node] != ME) {
                        node = parent[node] ?: return null
                    }
                    // `node` is the first hop after ME
                    return if (node in onlinePeers) node else null
                }
                queue.add(neighbor)
            }
        }
        return null // No path found
    }

    // ══════════════════════════════════════════════════════════════════════
    // Forwarding decision — 3-tier
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Rank buffered messages for forwarding to [peerId] using the 3-tier rule:
     *
     * Tier 1: Direct delivery (dest == peer) → always forward, top priority.
     * Tier 2: Multi-hop path exists through online peers → forward only if [peerId] is
     *         the next hop on that path.
     * Tier 3: No live path → probability-based relay (PROPHET Stage 1/2).
     *
     * [onlinePeers] is the set of all currently-online peer node IDs (passed in by the
     * orchestrator so we don't depend on Android framework here).
     */
    fun rankForForwardingWithTopology(
        candidates: List<DtnMessage>,
        peerId: NodeId,
        onlinePeers: Set<String>,
    ): List<ForwardCandidate> {
        val myPToDest = { dest: String -> pTable.getOrDefault(dest, 0.0) }
        val peerPToDest = { dest: String -> peerPTables[peerId.value]?.get(dest) ?: 0.0 }

        return candidates.mapNotNull { msg ->
            val destKey = msg.destinationNodeId.value
            val ttlFraction = msg.remainingTtlMs().toDouble() / max(msg.ttlMs, 1L).toDouble()

            when {
                // Guard: hop limit
                msg.hopCount >= config.maxHops -> null

                // Tier 1: Direct delivery
                destKey == peerId.value -> ForwardCandidate(msg, priority = 1.0 * ttlFraction)

                // Broadcast: always fan out
                msg.destinationNodeId == NodeId.BROADCAST ->
                    ForwardCandidate(msg, priority = 0.9 * ttlFraction)

                else -> {
                    // Tier 2: Check if a live multi-hop path exists and this peer is the next hop
                    val nextHop = findNextHopToward(destKey, onlinePeers)
                    if (nextHop != null && nextHop == peerId.value) {
                        // This peer IS the next hop on the live path — high priority
                        ForwardCandidate(msg, priority = 0.95 * ttlFraction)
                    } else if (nextHop != null && nextHop != peerId.value) {
                        // A live path exists but through a DIFFERENT peer — skip this one
                        null
                    } else {
                        // Tier 3: No live path → probability relay
                        if (msg.forwardCount <= config.maxForwards) {
                            // Stage 1: Epidemic spread (within forward limit)
                            val peerP = peerPToDest(destKey)
                            ForwardCandidate(msg, priority = (peerP + 0.1) * ttlFraction)
                        } else {
                            // Stage 2: Smart spread (P(j,D) > P(i,D))
                            val peerP = peerPToDest(destKey)
                            val myP = myPToDest(destKey)
                            when {
                                // Peer is a strictly better carrier — the normal Stage 2 rule.
                                peerP > myP -> ForwardCandidate(msg, priority = peerP * ttlFraction)
                                // Zero-gradient fallback: neither of us has ANY route belief for
                                // the destination (common in a sparse test net, or before any
                                // routing summary has propagated). The strict `>` rule would veto
                                // this outright and strand the bundle forever. Instead allow a
                                // low-priority exploratory relay so it keeps moving; the
                                // orchestrator's rate-limit, previous-hop suppression, and hop
                                // limit bound how far it spreads.
                                peerP < config.pMinThreshold && myP < config.pMinThreshold ->
                                    ForwardCandidate(msg, priority = config.relayBaseFloor * ttlFraction)
                                // Peer is no better and we DO have a belief — hold for a better carrier.
                                else -> null
                            }
                        }
                    }
                }
            }
        }.sortedByDescending { it.priority }
    }

    override fun rankForForwarding(candidates: List<DtnMessage>, peerId: NodeId): List<ForwardCandidate> {
        // Fallback without topology info — used by tests and MaxProp. Uses empty onlinePeers
        // which means Tier 2 never fires and behavior falls through to Tier 3 (probability).
        return rankForForwardingWithTopology(candidates, peerId, emptySet())
    }

    /** Expose the P-table for UI display (Network tab shows probability per peer). */
    fun getPTable(): Map<String, Double> = pTable.toMap()

    override fun rankForDrop(candidates: List<DtnMessage>, bytesToFree: Long): List<DtnMessage> {
        // Drop messages that we're least confident of ever delivering — low P first,
        // then shortest remaining TTL as tie-break.
        return candidates.sortedWith(
            compareBy<DtnMessage> { pTable.getOrDefault(it.destinationNodeId.value, 0.0) }
                .thenBy { it.remainingTtlMs() }
        )
    }

    override fun getDeliveryProbability(peerId: NodeId): Double =
        pTable.getOrDefault(peerId.value, 0.0)

    /** Peer's own belief `P(peer, dest)`. Zero if we've never heard a summary from them. */
    fun peerDeliveryProbability(peer: NodeId, dest: NodeId): Double =
        peerPTables[peer.value]?.get(dest.value) ?: 0.0

    // ══════════════════════════════════════════════════════════════════════
    // State persistence
    // ══════════════════════════════════════════════════════════════════════

    override fun exportState(): ByteArray = encodePVector(pTable)

    override fun importState(data: ByteArray) {
        pTable.clear()
        pTable.putAll(decodePVector(data))
    }

    // ══════════════════════════════════════════════════════════════════════
    // Wire helpers — compact binary [count][utf key][double]...
    // ══════════════════════════════════════════════════════════════════════

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

    companion object {
        const val TAG = "PROPHET"
    }
}

/**
 * Tunable parameters. Defaults follow RFC 6693 where applicable; the new fields
 * (`epsilonDistance`, `maxForwards`, `maxHops`) come from the design notes.
 */
data class ProphetConfig(
    /**
     * `P_init` in the notes — additive predictability boost on direct encounter.
     * Named `pEncounter` here for backwards compatibility with existing call sites.
     * RFC 6693 default is 0.75.
     */
    val pEncounter: Double = 0.75,
    /** Aging factor per interval (`γ`). RFC 6693 default: 0.98. */
    val gammaAging: Double = 0.98,
    /** Transitivity scaling factor (`β`). RFC 6693 default: 0.25. */
    val betaTransitivity: Double = 0.25,
    /** Distance-boost base (`ε`). Must be in [0, 1). Smaller ε → shorter reach of the boost. */
    val epsilonDistance: Double = 0.5,
    /** Aging cadence in ms. Below this elapsed time, [onPeriodicAge] is a no-op. */
    val agingIntervalMs: Long = 60_000L,
    /** Minimum P-value before an entry is pruned from the table. */
    val pMinThreshold: Double = 0.01,
    /** `N_m` — how many copies of any message this node will make before switching to Stage 2. */
    val maxForwards: Int = 6,
    /** `H_m` — hard hop limit; a message with `hopCount ≥ maxHops` is never forwarded again. */
    val maxHops: Int = 10,
    /**
     * Minimum edge probability for the live-path finder ([findNextHopToward]) to treat a peer
     * as able to reach a target. The old hard-coded `0.5` cutoff discarded all *transitive*
     * predictability — e.g. `P(B,D) = P(B,C)·P(C,D)·β ≈ 0.14` for a healthy 2-hop link — so
     * multi-hop paths were invisible. A lower floor (kept above [pMinThreshold] so pruned noise
     * doesn't create phantom edges) makes transitive reachability usable while still ignoring
     * near-zero beliefs.
     */
    val pFloorPath: Double = 0.1,
    /**
     * Last-resort relay priority used in Stage 2 when NEITHER this node nor the candidate peer
     * has any route belief for the destination (`P ≈ 0` on both sides). Without it, the strict
     * `P(peer) > P(me)` gate evaluates `0 > 0 == false` and the message is vetoed outright —
     * the classic dead-end in a sparse test network. A small positive floor lets the bundle
     * keep spreading exploratorily; the orchestrator's per-peer rate-limit, previous-hop
     * suppression, and [maxHops] bound the spread.
     */
    val relayBaseFloor: Double = 0.02,
)
