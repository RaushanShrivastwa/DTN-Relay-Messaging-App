package com.dtn.mesh.model

/**
 * Discriminator for DTN wire message types.
 * All share port PRIVATE_APP (256) but carry different payload semantics.
 *
 * Encoded as a single byte (offset 19) in the 24-byte DTN wire header.
 *
 * ## Wire Header Layout (24 bytes total)
 * ```
 * Byte offset | Size | Field
 * ──────────────────────────────────────────
 *  0          | 16   | messageId (UUID raw bytes)
 * 16          |  2   | ttlMinutes (UShort, big-endian)
 * 18          |  1   | hopCount
 * 19          |  1   | messageType (this enum)
 * 20          |  1   | channel
 * 21          |  1   | fragmentIndex (0 = complete, 1..N for fragments)
 * 22          |  1   | fragmentTotal (1 = no fragmentation)
 * 23          |  1   | reserved (must be 0x00)
 * ```
 *
 * Usable payload after header: 237 - 24 = 213 bytes per LoRa frame.
 */
enum class DtnMessageType(val wireValue: Byte) {
    /** User-generated store-carry-forward message content. */
    DATA(0x00),

    /** Serialised routing state summary exchanged on peer encounter. */
    ROUTING_SUMMARY(0x01),

    /** Lightweight acknowledgement that a routing summary was received and processed. */
    ROUTING_ACK(0x02),

    /**
     * Bundle offer: list of message IDs we hold and are willing to forward.
     * Sent after routing summary exchange to enable selective pull.
     */
    BUNDLE_OFFER(0x03),

    /**
     * Bundle request: list of message IDs we want the peer to transmit to us.
     * Sent in response to a BUNDLE_OFFER.
     */
    BUNDLE_REQUEST(0x04);

    companion object {
        /** Decode a wire byte to the enum, defaulting to DATA for unknown values. */
        fun fromWire(value: Byte): DtnMessageType =
            entries.firstOrNull { it.wireValue == value } ?: DATA

        /** Whether this message type carries user data (vs control plane). */
        fun isControlPlane(type: DtnMessageType): Boolean = type != DATA

        /**
         * Control-plane types that should NOT be persisted in the message buffer.
         * They are processed in-memory and discarded.
         */
        val EPHEMERAL_TYPES: Set<DtnMessageType> = setOf(
            ROUTING_SUMMARY,
            ROUTING_ACK,
            BUNDLE_OFFER,
            BUNDLE_REQUEST,
        )
    }

    /** Whether this type is ephemeral (not stored in the forwarding buffer). */
    val isEphemeral: Boolean get() = this in EPHEMERAL_TYPES

    /** Whether this type carries user payload that participates in store-carry-forward. */
    val isUserData: Boolean get() = this == DATA

    /** Whether this type is part of the encounter handshake protocol. */
    val isHandshake: Boolean get() = this == ROUTING_SUMMARY || this == ROUTING_ACK

    /** Whether this type is part of the bundle exchange protocol. */
    val isBundleExchange: Boolean get() = this == BUNDLE_OFFER || this == BUNDLE_REQUEST
}
