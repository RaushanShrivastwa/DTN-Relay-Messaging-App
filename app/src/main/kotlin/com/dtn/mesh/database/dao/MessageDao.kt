package com.dtn.mesh.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.dtn.mesh.database.entity.MessageEntity
import com.dtn.mesh.database.entity.MessageStatus
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for DTN message buffer operations.
 *
 * All suspend functions run within Room's IO dispatcher.
 * Flow-returning queries provide reactive updates for the UI and scheduler.
 */
@Dao
interface MessageDao {

    // ──────────────────────────────────────────────────────────────────────
    // Insert / Upsert
    // ──────────────────────────────────────────────────────────────────────

    /** Insert a new message. IGNORE on conflict = deduplication by UUID primary key. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity): Long

    /** Insert multiple messages (batch store from encounter exchange). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(messages: List<MessageEntity>): List<Long>

    /** Full entity update (e.g. increment forwardCount, update status). */
    @Update
    suspend fun update(message: MessageEntity)

    // ──────────────────────────────────────────────────────────────────────
    // Query: Buffer management
    // ──────────────────────────────────────────────────────────────────────

    /** All messages currently buffered (not expired, not dropped, not delivered). */
    @Query("""
        SELECT * FROM messages 
        WHERE status = :status 
        AND expires_at_ms > :nowMs
        ORDER BY created_at_ms ASC
    """)
    suspend fun getBufferedMessages(
        nowMs: Long = System.currentTimeMillis(),
        status: String = MessageStatus.BUFFERED,
    ): List<MessageEntity>

    /** Reactive flow of buffered message count (for UI / buffer pressure monitoring). */
    @Query("SELECT COUNT(*) FROM messages WHERE status = '${MessageStatus.BUFFERED}'")
    fun observeBufferedCount(): Flow<Int>

    /** Total payload bytes currently buffered — for buffer occupancy calculation. */
    @Query("""
        SELECT COALESCE(SUM(payload_size_bytes), 0) FROM messages 
        WHERE status = '${MessageStatus.BUFFERED}'
    """)
    suspend fun getTotalBufferedBytes(): Long

    /** Reactive flow of total buffered bytes. */
    @Query("""
        SELECT COALESCE(SUM(payload_size_bytes), 0) FROM messages 
        WHERE status = '${MessageStatus.BUFFERED}'
    """)
    fun observeTotalBufferedBytes(): Flow<Long>

    // ──────────────────────────────────────────────────────────────────────
    // Query: Forwarding decisions
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Messages eligible for forwarding to a specific peer:
     * - Buffered status
     * - Not expired
     * - Destination is either the target peer OR broadcast
     * - Not last forwarded to this same peer (avoid immediate echo)
     */
    @Query("""
        SELECT * FROM messages
        WHERE status = '${MessageStatus.BUFFERED}'
        AND expires_at_ms > :nowMs
        AND (destination_node_id = :peerId OR destination_node_id = '^all')
        AND (last_forwarded_to IS NULL OR last_forwarded_to != :peerId)
        ORDER BY created_at_ms ASC
    """)
    suspend fun getCandidatesForPeer(peerId: String, nowMs: Long = System.currentTimeMillis()): List<MessageEntity>

    /**
     * ALL buffered messages except those last forwarded to this peer (epidemic/data-mule sync).
     */
    @Query("""
        SELECT * FROM messages
        WHERE status = '${MessageStatus.BUFFERED}'
        AND expires_at_ms > :nowMs
        AND (last_forwarded_to IS NULL OR last_forwarded_to != :peerId)
        ORDER BY created_at_ms ASC
    """)
    suspend fun getAllBufferedExcludingPeer(peerId: String, nowMs: Long = System.currentTimeMillis()): List<MessageEntity>

    /** Get a single message by ID. */
    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getById(messageId: String): MessageEntity?

    /** Check if a message ID already exists (deduplication check without full insert). */
    @Query("SELECT COUNT(*) FROM messages WHERE id = :messageId")
    suspend fun existsById(messageId: String): Boolean

    // ──────────────────────────────────────────────────────────────────────
    // Status transitions
    // ──────────────────────────────────────────────────────────────────────

    /** Mark a message as currently being forwarded, recording the target peer and packet ID. */
    @Query("""
        UPDATE messages 
        SET status = '${MessageStatus.FORWARDING}', 
            last_forwarded_to = :peerId,
            last_forwarded_at_ms = :nowMs,
            mesh_packet_id = :meshPacketId,
            forward_count = forward_count + 1
        WHERE id = :messageId
    """)
    suspend fun markForwarding(messageId: String, peerId: String, meshPacketId: Int, nowMs: Long = System.currentTimeMillis())

    /** Mark as delivered (ACK received). */
    @Query("UPDATE messages SET status = '${MessageStatus.DELIVERED}' WHERE id = :messageId")
    suspend fun markDelivered(messageId: String)

    /** Mark as delivered by Meshtastic packet ID (used when MESSAGE_STATUS ACK arrives). */
    @Query("UPDATE messages SET status = '${MessageStatus.DELIVERED}' WHERE mesh_packet_id = :meshPacketId")
    suspend fun markDeliveredByPacketId(meshPacketId: Int)

    /**
     * Transition FORWARDING messages back to BUFFERED if no ACK arrived within timeout.
     * Called periodically by the scheduler.
     */
    @Query("""
        UPDATE messages 
        SET status = '${MessageStatus.BUFFERED}'
        WHERE status = '${MessageStatus.FORWARDING}'
        AND last_forwarded_at_ms < :timeoutThresholdMs
    """)
    suspend fun revertTimedOutForwards(timeoutThresholdMs: Long)

    /** Revert FORWARDING messages targeted at a specific peer back to BUFFERED (peer went offline). */
    @Query("""
        UPDATE messages 
        SET status = '${MessageStatus.BUFFERED}', last_forwarded_to = NULL, mesh_packet_id = NULL
        WHERE status = '${MessageStatus.FORWARDING}'
        AND last_forwarded_to = :peerId
    """)
    suspend fun revertForwardingForPeer(peerId: String)

    /** Manually clear the buffer: mark all active (BUFFERED/FORWARDING) messages as DROPPED. */
    @Query("""
        UPDATE messages
        SET status = '${MessageStatus.DROPPED}'
        WHERE status = '${MessageStatus.BUFFERED}' OR status = '${MessageStatus.FORWARDING}'
    """)
    suspend fun clearAllActive(): Int

    /** Increment duplicate count for an existing message. */
    @Query("UPDATE messages SET duplicate_count = duplicate_count + 1 WHERE id = :messageId")
    suspend fun incrementDuplicateCount(messageId: String)

    // ──────────────────────────────────────────────────────────────────────
    // TTL / Garbage collection
    // ──────────────────────────────────────────────────────────────────────

    /** Mark all expired messages. */
    @Query("""
        UPDATE messages 
        SET status = '${MessageStatus.EXPIRED}' 
        WHERE status IN ('${MessageStatus.BUFFERED}', '${MessageStatus.FORWARDING}')
        AND expires_at_ms <= :nowMs
    """)
    suspend fun markExpiredMessages(nowMs: Long = System.currentTimeMillis()): Int

    /** Permanently delete messages that have been expired/dropped for longer than retention period. */
    @Query("""
        DELETE FROM messages 
        WHERE status IN ('${MessageStatus.EXPIRED}', '${MessageStatus.DROPPED}', '${MessageStatus.DELIVERED}')
        AND received_at_ms < :retentionThresholdMs
    """)
    suspend fun purgeOldMessages(retentionThresholdMs: Long): Int

    /** Bulk mark messages as dropped (by ID list, used by drop policy). */
    @Query("""
        UPDATE messages 
        SET status = '${MessageStatus.DROPPED}' 
        WHERE id IN (:messageIds)
    """)
    suspend fun markDropped(messageIds: List<String>)

    // ──────────────────────────────────────────────────────────────────────
    // Research / export
    // ──────────────────────────────────────────────────────────────────────

    /** All messages (all statuses) for research export. */
    @Query("SELECT * FROM messages ORDER BY created_at_ms ASC")
    suspend fun getAllForExport(): List<MessageEntity>

    /** Messages in a time range (for incremental export). */
    @Query("SELECT * FROM messages WHERE received_at_ms BETWEEN :fromMs AND :toMs ORDER BY created_at_ms ASC")
    suspend fun getInTimeRange(fromMs: Long, toMs: Long): List<MessageEntity>

    /** Count of messages by status (for dashboard / telemetry). */
    @Query("SELECT status, COUNT(*) as count FROM messages GROUP BY status")
    suspend fun getStatusCounts(): List<StatusCount>

    /** Find the message currently associated with a Meshtastic packet ID (for ACK correlation). */
    @Query("SELECT * FROM messages WHERE mesh_packet_id = :meshPacketId LIMIT 1")
    suspend fun getByMeshPacketId(meshPacketId: Int): MessageEntity?
}

/** Projection for status count aggregation. */
data class StatusCount(
    val status: String,
    val count: Int,
)
