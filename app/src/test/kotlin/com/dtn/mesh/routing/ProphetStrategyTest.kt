package com.dtn.mesh.routing

import com.dtn.mesh.model.ContactRecord
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.NodeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProphetStrategyTest {

    private lateinit var prophet: ProphetStrategy

    @Before
    fun setup() {
        prophet = ProphetStrategy(ProphetConfig(
            pEncounter = 0.75,
            gammaAging = 0.98,
            betaTransitivity = 0.25,
            agingIntervalMs = 1000L, // 1s for fast test aging
            pMinThreshold = 0.01,
        ))
    }

    private fun contact(peer: String) = ContactRecord(
        peerId = NodeId(peer), startTimeMs = System.currentTimeMillis(),
        rssi = -60, snr = 10f,
    )

    @Test
    fun `encounter boosts delivery probability`() {
        val peer = NodeId("!aabb0001")
        assertEquals(0.0, prophet.getDeliveryProbability(peer), 0.001)

        prophet.onEncounter(peer, contact(peer.value))
        assertEquals(0.75, prophet.getDeliveryProbability(peer), 0.001)
    }

    @Test
    fun `repeated encounters increase P but never exceed 1`() {
        val peer = NodeId("!aabb0001")
        repeat(20) { prophet.onEncounter(peer, contact(peer.value)) }
        assertTrue(prophet.getDeliveryProbability(peer) <= 1.0)
        assertTrue(prophet.getDeliveryProbability(peer) > 0.99)
    }

    @Test
    fun `aging reduces probability`() {
        val peer = NodeId("!aabb0001")
        prophet.onEncounter(peer, contact(peer.value))
        val before = prophet.getDeliveryProbability(peer)

        Thread.sleep(1100) // exceed aging interval
        prophet.onPeriodicAge()

        assertTrue(prophet.getDeliveryProbability(peer) < before)
    }

    @Test
    fun `transitivity updates from routing summary`() {
        val peerB = NodeId("!aabb0002")
        val peerC = NodeId("!aabb0003")

        // We've met B directly
        prophet.onEncounter(peerB, contact(peerB.value))

        // B shares their summary: B has high P for C
        val summary = RoutingSummary("PROPHET", encodeFakePVector(mapOf(peerC.value to 0.9)))
        prophet.onRoutingSummaryReceived(peerB, summary)

        // We should now have non-zero P for C via transitivity
        assertTrue(prophet.getDeliveryProbability(peerC) > 0.0)
    }

    @Test
    fun `rankForForwarding only includes messages where peer has higher P`() {
        val peer = NodeId("!aabb0001")
        val dest = NodeId("!aabb0099")

        // Peer has met dest (via summary), we haven't
        prophet.onEncounter(peer, contact(peer.value))
        val summary = RoutingSummary("PROPHET", encodeFakePVector(mapOf(dest.value to 0.8)))
        prophet.onRoutingSummaryReceived(peer, summary)

        val msg = DtnMessage(
            id = "test-msg-1", originNodeId = NodeId("!local"),
            destinationNodeId = dest, payloadBytes = ByteArray(10),
            createdAtMs = System.currentTimeMillis(), ttlMs = 3_600_000,
        )

        val ranked = prophet.rankForForwarding(listOf(msg), peer)
        assertTrue(ranked.isNotEmpty())
        assertTrue(ranked[0].priority > 0.0)
    }

    @Test
    fun `export and import state preserves P-table`() {
        val peer = NodeId("!aabb0001")
        prophet.onEncounter(peer, contact(peer.value))
        val pBefore = prophet.getDeliveryProbability(peer)

        val state = prophet.exportState()
        val restored = ProphetStrategy()
        restored.importState(state)

        assertEquals(pBefore, restored.getDeliveryProbability(peer), 0.0001)
    }

    // Helper: encode a P-vector matching ProphetStrategy's internal format
    private fun encodeFakePVector(entries: Map<String, Double>): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(bos)
        dos.writeInt(entries.size)
        for ((k, v) in entries) { dos.writeUTF(k); dos.writeDouble(v) }
        dos.flush()
        return bos.toByteArray()
    }
}
