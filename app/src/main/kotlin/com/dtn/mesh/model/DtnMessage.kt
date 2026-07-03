package com.dtn.mesh.model

/**
 * Canonical in-flight DTN message representation.
 *
 * This DTO lives above both Room and Meshtastic — it is the lingua franca passed between
 * the transport adapter, routing strategies, queue manager, and Q-learning engine.
 *
 * ## Payload budget
 * Independent of Meshtastic, the over-the-air limit is now the LoRa PHY frame size. SX126x radios
 * (e.g. Heltec WiFi LoRa 32 V3 hubs) allow up to 255-byte single frames. Our **self-contained**
 * DTN header carries origin/destination inside the bundle (no outer envelope on the hub-to-hub
 * LoRa link), so the fixed header is 32 bytes:
 *   - messageId:         16 bytes (UUID as raw bytes)
 *   - originNodeId:       4 bytes (uint32, big-endian)
 *   - destinationNodeId:  4 bytes (uint32, big-endian)
 *   - ttlMinutes:         2 bytes (UShort big-endian, max ~45 days)
 *   - hopCount:           1 byte
 *   - messageType:        1 byte  (DtnMessageType discriminator)
 *   - channel:            1 byte
 *   - fragmentIndex:      1 byte  (0 = complete single frame, 1..N for fragments)
 *   - fragmentTotal:      1 byte  (1 = no fragmentation)
 *   - reserved:           1 byte  (must be 0x00)
 *
 * Usable user payload: 255 - 32 = **223 bytes** per frame (keep ≲ 200 to limit LoRa airtime).
 * If a message exceeds this, fragmentation is required. Fragmentation logic
 * will live in `queue/` and is flagged as a future requirement.
 */
data class DtnMessage(
    /** Globally unique message identifier (UUID string). */
    val id: String,
    /** Node that originally created this message. */
    val originNodeId: NodeId,
    /** Intended final destination; [NodeId.BROADCAST] for epidemic flooding. */
    val destinationNodeId: NodeId,
    /** Raw user payload bytes (max 213 bytes without fragmentation). */
    val payloadBytes: ByteArray,
    /** Meshtastic PortNum value. Always 256 (PRIVATE_APP) for our DTN traffic. */
    val portNum: Int = PORT_NUM_PRIVATE_APP,
    /** Creation timestamp (epoch milliseconds). */
    val createdAtMs: Long,
    /** Time-to-live from creation (milliseconds). Message is dropped when TTL expires. */
    val ttlMs: Long,
    /** Number of hops this message has traversed so far. */
    val hopCount: Int = 0,
    /** Meshtastic channel index (0 = primary). */
    val channel: Int = 0,
    /** RSSI observed on the last receive hop. */
    val lastRssi: Int = 0,
    /** SNR observed on the last receive hop. */
    val lastSnr: Float = 0f,
    /** How many times we have forwarded this message. */
    val forwardCount: Int = 0,
    /** Number of duplicate receptions of this message. */
    val duplicateCount: Int = 0,
    /** Wire message type discriminator. Determines payload semantics. */
    val messageType: DtnMessageType = DtnMessageType.DATA,
    /** Fragment index (0 = complete single-frame message, 1..N for multi-frame). */
    val fragmentIndex: Int = 0,
    /** Total number of fragments (1 = no fragmentation). */
    val fragmentTotal: Int = 1,
) {
    companion object {
        /**
         * Legacy application port discriminator retained for compatibility of the [portNum]
         * field. No longer tied to any Meshtastic PortNum now that the LoRa path is independent.
         */
        const val PORT_NUM_PRIVATE_APP = 256

        /**
         * Maximum raw payload in a single LoRa frame (SX126x). This is the hard ceiling for a
         * single, unfragmented bundle transmitted over the hub-to-hub LoRa backbone.
         */
        const val LORA_MAX_FRAME_BYTES = 255

        /** Our DTN wire header overhead (fixed 32 bytes — includes origin + destination). */
        const val DTN_HEADER_BYTES = 32

        /** Maximum user payload bytes per single frame (no fragmentation). */
        const val MAX_SINGLE_PACKET_PAYLOAD = LORA_MAX_FRAME_BYTES - DTN_HEADER_BYTES // 223

        /** Whether [payloadBytes] requires fragmentation to fit in a single LoRa frame. */
        fun requiresFragmentation(payloadSize: Int): Boolean = payloadSize > MAX_SINGLE_PACKET_PAYLOAD
    }

    /** Whether this message's TTL has expired. */
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - createdAtMs > ttlMs

    /** Remaining TTL in milliseconds (clamped to 0). */
    fun remainingTtlMs(nowMs: Long = System.currentTimeMillis()): Long =
        (ttlMs - (nowMs - createdAtMs)).coerceAtLeast(0L)

    // ByteArray-aware equals/hashCode
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DtnMessage) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
