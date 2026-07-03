package com.dtn.mesh.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.dtn.mesh.database.entity.ForwardingDecisionEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for forwarding decision research logs.
 * Optimised for append-heavy writes and batch export reads.
 */
@Dao
interface ForwardingDecisionDao {

    @Insert
    suspend fun insert(decision: ForwardingDecisionEntity): Long

    @Insert
    suspend fun insertAll(decisions: List<ForwardingDecisionEntity>)

    /** Backfill reward for a decision (when message delivery outcome becomes known). */
    @Query("UPDATE forwarding_decisions SET reward = :reward, update_applied = 0 WHERE id = :decisionId")
    suspend fun setReward(decisionId: Long, reward: Double)

    /**
     * Backfill reward for ALL decisions associated with a message.
     * Used as a first pass — [RewardResolver] then applies differentiated credit
     * per-action by calling [setDifferentiatedReward] for FORWARD decisions
     * that targeted the delivering peer.
     */
    @Query("""
        UPDATE forwarding_decisions 
        SET reward = :reward, update_applied = 0 
        WHERE message_id = :messageId AND reward IS NULL
    """)
    suspend fun setRewardByMessageId(messageId: String, reward: Double)

    /**
     * Differentiated reward: override reward for FORWARD decisions that targeted
     * the specific peer credited with delivery. Called after [setRewardByMessageId]
     * applies the baseline reward to all rows.
     */
    @Query("""
        UPDATE forwarding_decisions 
        SET reward = :reward, update_applied = 0 
        WHERE message_id = :messageId 
        AND action = 'FORWARD' 
        AND peer_node_id = :deliveryPeerId
    """)
    suspend fun setDifferentiatedReward(messageId: String, deliveryPeerId: String, reward: Double)

    /**
     * Get decisions for a message that have not yet received a reward.
     * Used by RewardResolver to inspect which actions/peers were involved.
     */
    @Query("""
        SELECT * FROM forwarding_decisions 
        WHERE message_id = :messageId 
        ORDER BY timestamp_ms ASC
    """)
    suspend fun getDecisionsForMessage(messageId: String): List<ForwardingDecisionEntity>

    /** Get pending decisions (reward assigned but Q-update not yet applied). */
    @Query("""
        SELECT * FROM forwarding_decisions 
        WHERE reward IS NOT NULL AND update_applied = 0 
        ORDER BY timestamp_ms ASC
    """)
    suspend fun getPendingUpdates(): List<ForwardingDecisionEntity>

    /** Research export: all decisions in a time range. */
    @Query("""
        SELECT * FROM forwarding_decisions 
        WHERE timestamp_ms BETWEEN :fromMs AND :toMs 
        ORDER BY timestamp_ms ASC
    """)
    suspend fun getInTimeRange(fromMs: Long, toMs: Long): List<ForwardingDecisionEntity>

    /** Full export. */
    @Query("SELECT * FROM forwarding_decisions ORDER BY timestamp_ms ASC")
    suspend fun getAllForExport(): List<ForwardingDecisionEntity>

    /** Count of decisions (for dashboard). */
    @Query("SELECT COUNT(*) FROM forwarding_decisions")
    fun observeDecisionCount(): Flow<Int>

    /** Action distribution (for real-time monitoring). */
    @Query("SELECT action, COUNT(*) as count FROM forwarding_decisions GROUP BY action")
    suspend fun getActionDistribution(): List<ActionCount>

    /** Purge old decisions beyond retention period. */
    @Query("DELETE FROM forwarding_decisions WHERE timestamp_ms < :thresholdMs")
    suspend fun purgeOlderThan(thresholdMs: Long): Int
}

data class ActionCount(
    val action: String,
    val count: Int,
)
