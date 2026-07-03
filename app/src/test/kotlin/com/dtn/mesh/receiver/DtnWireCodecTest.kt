package com.dtn.mesh.receiver

import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.DtnMessageType
import com.dtn.mesh.model.NodeId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class DtnWireCodecTest {

    private fun makeMessage(
        payload: ByteArray = "hello DTN".toByteArray(),
        ttlMs: Long = 3_600_000L,
        messageType: DtnMessageType = DtnMessageType.DATA,
        origin: NodeId = NodeId("!aabb0001"),
        destination: NodeId = NodeId("!aabb0099"),
    ) = DtnMessage(
        id = UUID.randomUUID().toString(),
        originNodeId = origin,
        destinationNodeId = destination,
        payloadBytes = payload,
        createdAtMs = System.currentTimeMillis(),
        ttlMs = ttlMs,
        hopCount = 2,
        channel = 1,
        messageType = messageType,
        fragmentIndex = 0,
        fragmentTotal = 1,
    )

    @Test
    fun `encode produces correct header size`() {
        val msg = makeMessage()
        val wire = DtnWireCodec.encode(msg)
        assertEquals(32, DtnWireCodec.HEADER_SIZE)
        assertEquals(DtnWireCodec.HEADER_SIZE + msg.payloadBytes.size, wire.size)
    }

    @Test
    fun `decode reverses encode`() {
        val original = makeMessage()
        val wire = DtnWireCodec.encode(original)

        val decoded = DtnWireCodec.decode(wireBytes = wire, rssi = -65, snr = 8.5f)

        assertNotNull(decoded)
        decoded!!
        assertEquals(original.id, decoded.id)
        assertArrayEquals(original.payloadBytes, decoded.payloadBytes)
        assertEquals(original.hopCount, decoded.hopCount)
        assertEquals(original.channel, decoded.channel)
        assertEquals(original.messageType, decoded.messageType)
        assertEquals(original.fragmentIndex, decoded.fragmentIndex)
        assertEquals(original.fragmentTotal, decoded.fragmentTotal)
        assertEquals(-65, decoded.lastRssi)
        assertEquals(8.5f, decoded.lastSnr)
    }

    @Test
    fun `origin and destination round-trip through the self-contained header`() {
        val original = makeMessage(origin = NodeId("!12345678"), destination = NodeId("!90abcdef"))
        val wire = DtnWireCodec.encode(original)
        val decoded = DtnWireCodec.decode(wire)!!
        assertEquals(NodeId("!12345678"), decoded.originNodeId)
        assertEquals(NodeId("!90abcdef"), decoded.destinationNodeId)
    }

    @Test
    fun `non-hex node ids map to a stable 32-bit value on the wire`() {
        // A legacy/arbitrary id hashes to a stable uint32; decode yields its canonical hex form.
        val origin = NodeId("!p2p_pixel7")
        val expected = NodeId.fromNodeNum(origin.toNodeNum32())
        val msg = makeMessage(origin = origin)
        val decoded = DtnWireCodec.decode(DtnWireCodec.encode(msg))!!
        assertEquals(expected, decoded.originNodeId)
    }

    @Test
    fun `TTL encodes as minutes and round-trips within 1 minute accuracy`() {
        val ttlMs = 7_200_000L // 2 hours = 120 minutes
        val msg = makeMessage(ttlMs = ttlMs)
        val wire = DtnWireCodec.encode(msg)
        val decoded = DtnWireCodec.decode(wire)!!
        // TTL encoded as minutes → decoded back to ms: should be within 60s
        assertEquals(ttlMs.toDouble(), decoded.ttlMs.toDouble(), 60_000.0)
    }

    @Test
    fun `messageType discriminator round-trips correctly`() {
        for (type in DtnMessageType.entries) {
            val msg = makeMessage(messageType = type)
            val wire = DtnWireCodec.encode(msg)
            val decoded = DtnWireCodec.decode(wire)!!
            assertEquals(type, decoded.messageType)
        }
    }

    @Test
    fun `decode returns null for data shorter than header`() {
        val tooShort = ByteArray(10)
        assertNull(DtnWireCodec.decode(tooShort))
    }

    @Test
    fun `max payload fits in single frame`() {
        val maxPayload = ByteArray(DtnMessage.MAX_SINGLE_PACKET_PAYLOAD) { 0x42 }
        val msg = makeMessage(payload = maxPayload)
        val wire = DtnWireCodec.encode(msg)
        assertEquals(DtnMessage.LORA_MAX_FRAME_BYTES, wire.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `oversized payload without fragmentation throws`() {
        val tooLarge = ByteArray(DtnMessage.MAX_SINGLE_PACKET_PAYLOAD + 1)
        val msg = makeMessage(payload = tooLarge)
        DtnWireCodec.encode(msg) // should throw
    }
}
