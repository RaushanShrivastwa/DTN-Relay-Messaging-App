package com.dtn.mesh.queue

import com.dtn.mesh.database.dao.MessageDao
import com.dtn.mesh.database.entity.MessageEntity
import com.dtn.mesh.database.entity.MessageStatus
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.NodeId
import com.dtn.mesh.routing.RoutingStrategy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the DTN message buffer: ingest, deduplication, TTL expiry,
 * buffer pressure enforcement, and drop policy execution.
 *
 * Sits between the transport adapter (inbound) and the forwarding scheduler (outbound).
 * All persistence goes through [MessageDao].
 */
@Singleton
class MessageQueueManager @Inject constructor(
    private val messageDao: MessageDao,
    private val config: BufferConfig,
) {

    // ──────────────────────────────────────────────────────────────────────
    // Ingest
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Store an inbound DTN message. Handles deduplication and buffer pressure.
     * @return true if stored (new message), false if duplicate or rejected.
     */
    suspend fun ingest(message: DtnMessage): Boolean {
        // Deduplication: check if we already have this message ID
        if (messageDao.existsById(message.id)) {
            messageDao.incrementDuplicateCount(message.id)
            return false
        }

        // TTL check: reject already-expired messages
        if (message.isExpired()) return false

        // Buffer pressure: enforce drop policy if needed before inserting
        enforceBufferPressure(null)

        val entity = toEntity(message)
        val rowId = messageDao.insert(entity)
        return rowId != -1L
    }

    // ──────────────────────────────────────────────────────────────────────
    // Buffer pressure & drop policy
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Check buffer occupancy and invoke drop policy if above threshold.
     * @param strategy The active routing strategy (for rankForDrop). Null = use TTL-only drop.
     */
    suspend fun enforceBufferPressure(strategy: RoutingStrategy?) {
        val currentBytes = messageDao.getTotalBufferedBytes()
        val threshold = (config.maxBufferBytes * config.pressureThreshold).toLong()

        if (currentBytes <= threshold) return

        val bytesToFree = currentBytes - (config.maxBufferBytes * 0.7).toLong() // Free down to 70%
        val candidates = messageDao.getBufferedMessages()

        val toDrop = if (strategy != null) {
            strategy.rankForDrop(candidates.map { fromEntity(it) }, bytesToFree)
        } else {
            // Fallback: drop shortest remaining TTL first
            candidates
                .sortedBy { it.expiresAtMs - System.currentTimeMillis() }
                .map { fromEntity(it) }
        }

        // Drop enough messages to free the required bytes
        var freed = 0L
        val dropIds = mutableListOf<String>()
        for (msg in toDrop) {
            if (freed >= bytesToFree) break
            dropIds.add(msg.id)
            freed += msg.payloadBytes.size + DtnMessage.DTN_HEADER_BYTES
        }

        if (dropIds.isNotEmpty()) {
            messageDao.markDropped(dropIds)
        }
    }

    /**
     * Get current buffer occupancy as a fraction [0.0, 1.0].
     */
    suspend fun getBufferOccupancy(): Double {
        val current = messageDao.getTotalBufferedBytes()
        return (current.toDouble() / config.maxBufferBytes).coerceIn(0.0, 1.0)
    }

    // ──────────────────────────────────────────────────────────────────────
    // TTL management
    // ──────────────────────────────────────────────────────────────────────

    /** Mark all expired messages and return how many were expired. */
    suspend fun expireMessages(): Int = messageDao.markExpiredMessages()

    /** Purge old terminal-state messages beyond retention period. */
    suspend fun purgeOldMessages(): Int {
        val threshold = System.currentTimeMillis() - config.retentionPeriodMs
        return messageDao.purgeOldMessages(threshold)
    }

    /** Revert FORWARDING messages that timed out waiting for ACK back to BUFFERED. */
    suspend fun revertTimedOutForwards(): Int {
        val threshold = System.currentTimeMillis() - config.forwardingTimeoutMs
        messageDao.revertTimedOutForwards(threshold)
        // Room doesn't return affected row count for UPDATE; return 0 as placeholder
        return 0
    }

    // ──────────────────────────────────────────────────────────────────────
    // Query helpers for scheduler
    // ──────────────────────────────────────────────────────────────────────

    /** Get all messages eligible for forwarding to a specific peer. */
    suspend fun getCandidatesForPeer(peerId: NodeId): List<DtnMessage> =
        messageDao.getCandidatesForPeer(peerId.value).map { fromEntity(it) }

    /**
     * Get ALL buffered messages for epidemic sync (data mule mode).
     * Used during cold start to forward everything to any reachable peer.
     * Excludes messages already forwarded to this specific peer to avoid echo.
     */
    suspend fun getAllCandidatesExcluding(peerId: NodeId): List<DtnMessage> =
        messageDao.getAllBufferedExcludingPeer(peerId.value).map { fromEntity(it) }

    /** Get all currently buffered messages. */
    suspend fun getAllBuffered(): List<DtnMessage> =
        messageDao.getBufferedMessages().map { fromEntity(it) }

    /** Mark a message as currently being forwarded. */
    suspend fun markForwarding(messageId: String, peerId: NodeId, meshPacketId: Int) {
        messageDao.markForwarding(messageId, peerId.value, meshPacketId)
    }

    /** Revert all FORWARDING messages targeted at a specific peer back to BUFFERED.
     *  Called when a peer goes offline mid-transfer. */
    suspend fun revertForwardingForPeer(peerId: NodeId) {
        messageDao.revertForwardingForPeer(peerId.value)
    }

    /** Manually clear the buffer — mark all active messages DROPPED. Returns count cleared. */
    suspend fun clearBuffer(): Int = messageDao.clearAllActive()

    /** Check if a message exists in BUFFERED state. */
    suspend fun existsInBuffer(messageId: String): Boolean {
        val entity = messageDao.getById(messageId) ?: return false
        return entity.status == MessageStatus.BUFFERED
    }

    /** Mark a message as delivered (ACK received). */
    suspend fun markDelivered(messageId: String) {
        messageDao.markDelivered(messageId)
    }

    /** Mark delivered by Meshtastic packet ID (from MESSAGE_STATUS broadcast). */
    suspend fun markDeliveredByPacketId(meshPacketId: Int) {
        messageDao.markDeliveredByPacketId(meshPacketId)
    }

    /** Find which message corresponds to a Meshtastic packet ID. */
    suspend fun getByMeshPacketId(meshPacketId: Int): MessageEntity? =
        messageDao.getByMeshPacketId(meshPacketId)

    /** Reactive count of currently BUFFERED messages (for UI). */
    fun observeBufferedCount(): kotlinx.coroutines.flow.Flow<Int> =
        messageDao.observeBufferedCount()

    // ──────────────────────────────────────────────────────────────────────
    // Entity ↔ DTO conversion
    // ──────────────────────────────────────────────────────────────────────

    private fun toEntity(msg: DtnMessage): MessageEntity = MessageEntity(
        id = msg.id,
        originNodeId = msg.originNodeId.value,
        destinationNodeId = msg.destinationNodeId.value,
        payload = msg.payloadBytes,
        portNum = msg.portNum,
        channel = msg.channel,
        createdAtMs = msg.createdAtMs,
        expiresAtMs = msg.createdAtMs + msg.ttlMs,
        ttlMs = msg.ttlMs,
        hopCount = msg.hopCount,
        forwardCount = msg.forwardCount,
        duplicateCount = msg.duplicateCount,
        lastRssi = msg.lastRssi,
        lastSnr = msg.lastSnr,
        status = MessageStatus.BUFFERED,
        messageType = msg.messageType.name,
    )

    private fun fromEntity(entity: MessageEntity): DtnMessage = DtnMessage(
        id = entity.id,
        originNodeId = NodeId(entity.originNodeId),
        destinationNodeId = NodeId(entity.destinationNodeId),
        payloadBytes = entity.payload,
        portNum = entity.portNum,
        createdAtMs = entity.createdAtMs,
        ttlMs = entity.ttlMs,
        hopCount = entity.hopCount,
        channel = entity.channel,
        lastRssi = entity.lastRssi,
        lastSnr = entity.lastSnr,
        forwardCount = entity.forwardCount,
        duplicateCount = entity.duplicateCount,
    )
}
