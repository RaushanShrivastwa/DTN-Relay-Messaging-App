package com.dtn.mesh.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing a DTN message stored in the local buffer.
 *
 * This is the persistence layer for store-carry-forward. Messages remain here until:
 * - Successfully delivered (confirmed via MESSAGE_STATUS ACK from Meshtastic)
 * - TTL expires and they are garbage-collected
 * - Buffer pressure causes the drop policy to evict them
 *
 * Indices:
 * - [destinationNodeId]: fast lookup when selecting messages to forward to a specific peer
 * - [expiresAtMs]: efficient TTL-based pruning queries
 * - [status]: filter by delivery state
 */
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["destination_node_id"]),
        Index(value = ["expires_at_ms"]),
        Index(value = ["status"]),
        Index(value = ["origin_node_id"]),
    ]
)
data class MessageEntity(
    /** Globally unique message ID (UUID string). Also serves as the deduplication key. */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** Node that originally created this message. */
    @ColumnInfo(name = "origin_node_id")
    val originNodeId: String,

    /** Intended final destination; "^all" for broadcast/epidemic. */
    @ColumnInfo(name = "destination_node_id")
    val destinationNodeId: String,

    /** Raw user payload bytes. */
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB)
    val payload: ByteArray,

    /** Meshtastic PortNum (always 256 for our DTN traffic). */
    @ColumnInfo(name = "port_num")
    val portNum: Int = 256,

    /** Meshtastic channel index. */
    @ColumnInfo(name = "channel")
    val channel: Int = 0,

    /** Creation time of this message at origin (epoch ms). */
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,

    /** Absolute expiration time = createdAtMs + ttlMs (epoch ms). Pre-computed for efficient queries. */
    @ColumnInfo(name = "expires_at_ms")
    val expiresAtMs: Long,

    /** Original TTL value (ms) as specified by the origin node. */
    @ColumnInfo(name = "ttl_ms")
    val ttlMs: Long,

    /** Number of hops this message has traversed. */
    @ColumnInfo(name = "hop_count")
    val hopCount: Int = 0,

    /** How many times we have forwarded this message. */
    @ColumnInfo(name = "forward_count")
    val forwardCount: Int = 0,

    /** Number of duplicate receptions we've observed. */
    @ColumnInfo(name = "duplicate_count")
    val duplicateCount: Int = 0,

    /** RSSI from the last incoming hop. */
    @ColumnInfo(name = "last_rssi")
    val lastRssi: Int = 0,

    /** SNR from the last incoming hop. */
    @ColumnInfo(name = "last_snr")
    val lastSnr: Float = 0f,

    /**
     * Current delivery status.
     * Values: BUFFERED, FORWARDING, DELIVERED, EXPIRED, DROPPED
     */
    @ColumnInfo(name = "status")
    val status: String = MessageStatus.BUFFERED,

    /** Timestamp when this entity was first stored locally (epoch ms). */
    @ColumnInfo(name = "received_at_ms")
    val receivedAtMs: Long = System.currentTimeMillis(),

    /** The last peer we forwarded this message to (node ID string), or null. */
    @ColumnInfo(name = "last_forwarded_to")
    val lastForwardedTo: String? = null,

    /** Timestamp of last forward attempt (epoch ms). */
    @ColumnInfo(name = "last_forwarded_at_ms")
    val lastForwardedAtMs: Long? = null,

    /** Meshtastic packet ID assigned on last send (for MESSAGE_STATUS tracking). */
    @ColumnInfo(name = "mesh_packet_id")
    val meshPacketId: Int? = null,

    /**
     * Wire message type discriminator. Stored as the string name of [DtnMessageType].
     * Only DATA messages participate in store-carry-forward; control-plane types
     * (ROUTING_SUMMARY, BUNDLE_OFFER, etc.) are ephemeral and never persisted here,
     * but we keep the column for completeness and debugging.
     */
    @ColumnInfo(name = "message_type")
    val messageType: String = "DATA",

    /** Payload size in bytes (denormalised for buffer-pressure calculations). */
    @ColumnInfo(name = "payload_size_bytes")
    val payloadSizeBytes: Int = payload.size,
) {
    // ByteArray-aware equals/hashCode (identity by id)
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

/**
 * Message lifecycle states.
 */
object MessageStatus {
    /** Stored locally, awaiting forwarding opportunity. */
    const val BUFFERED = "BUFFERED"
    /** Currently being transmitted (awaiting Meshtastic ACK). */
    const val FORWARDING = "FORWARDING"
    /** Delivery confirmed (ACK received from destination or relay). */
    const val DELIVERED = "DELIVERED"
    /** TTL expired; pending garbage collection. */
    const val EXPIRED = "EXPIRED"
    /** Explicitly dropped by drop policy under buffer pressure. */
    const val DROPPED = "DROPPED"
}
