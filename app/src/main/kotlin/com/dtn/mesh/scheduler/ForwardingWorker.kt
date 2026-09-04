package com.dtn.mesh.scheduler

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dtn.mesh.database.dao.ForwardingDecisionDao
import com.dtn.mesh.queue.MessageQueueManager
import com.dtn.mesh.routing.StrategySelector
import com.dtn.mesh.service.DtnOrchestrator
import com.dtn.mesh.service.LifecycleEvent
import com.dtn.mesh.service.MessageLifecycleLog
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Periodic WorkManager job — HOUSEKEEPING ONLY.
 *
 * Runs every 15 minutes (WorkManager minimum) and performs:
 * 1. TTL expiry & garbage collection
 * 2. Revert timed-out FORWARDING → BUFFERED
 * 3. Periodic routing aging (both strategies)
 * 4. Collect pending Q-updates and route to orchestrator (for thread safety)
 * 5. Buffer pressure enforcement / drop policy
 *
 * Real-time forwarding is handled by [DtnOrchestrator] on encounter events.
 * This worker NEVER calls transport.sendMessage() or qEngine.update() directly.
 */
@HiltWorker
class ForwardingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val queueManager: MessageQueueManager,
    private val strategySelector: StrategySelector,
    private val decisionDao: ForwardingDecisionDao,
    private val orchestrator: DtnOrchestrator,
    private val lifecycle: MessageLifecycleLog,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ForwardingWorker"
        const val WORK_NAME = "dtn_housekeeping_cycle"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Housekeeping cycle starting")

        // Phase 1: TTL expiry. Log each message about to expire BEFORE the DB marks it, so the
        // lifecycle feed / buffer-history show why it left the buffer (previously these were
        // only Log.d and never surfaced in-app).
        runCatching {
            queueManager.getAllBuffered()
                .filter { it.remainingTtlMs() <= 0L }
                .forEach { m ->
                    lifecycle.log(LifecycleEvent.EXPIRED, m.id,
                        origin = m.originNodeId.value, dest = m.destinationNodeId.value,
                        hopCount = m.hopCount, extra = "TTL reached")
                }
        }
        val expired = queueManager.expireMessages()
        if (expired > 0) Log.d(TAG, "Expired $expired messages")

        // Phase 2: Revert timed-out forwards
        queueManager.revertTimedOutForwards()

        // Phase 3: Purge old terminal messages
        queueManager.purgeOldMessages()

        // Phase 4: Periodic routing aging (both strategies)
        strategySelector.onPeriodicAge()

        // Phase 5: Collect pending Q-updates and route to orchestrator
        // (Q-engine mutations ONLY happen on orchestrator's single-threaded dispatcher)
        val pendingUpdates = decisionDao.getPendingUpdates()
        if (pendingUpdates.isNotEmpty()) {
            orchestrator.enqueueBatchQUpdates(pendingUpdates)
            Log.d(TAG, "Routed ${pendingUpdates.size} Q-updates to orchestrator")
        }

        // Phase 6: Buffer pressure enforcement
        queueManager.enforceBufferPressure(strategySelector)

        // Phase 7: Drop buffered messages whose destination's P-value has decayed below
        // threshold. If we can't reasonably expect to encounter the destination or a mule
        // that can reach them, there's no point holding the message. Grace period of 2
        // minutes so freshly-created messages aren't killed before we've learned P.
        val prophet = strategySelector.active as? com.dtn.mesh.routing.ProphetStrategy
        if (prophet != null) {
            val buffered = queueManager.getAllBuffered()
            val now = System.currentTimeMillis()
            val dropIds = mutableListOf<String>()
            for (msg in buffered) {
                if (msg.destinationNodeId == com.dtn.mesh.model.NodeId.BROADCAST) continue
                val age = now - msg.createdAtMs
                if (age < 120_000L) continue // grace period: 2 minutes
                val p = prophet.getDeliveryProbability(msg.destinationNodeId)
                if (p < prophet.config.pMinThreshold) {
                    dropIds.add(msg.id)
                    lifecycle.log(LifecycleEvent.DROPPED, msg.id,
                        origin = msg.originNodeId.value, dest = msg.destinationNodeId.value,
                        hopCount = msg.hopCount,
                        extra = "low reachability P=${"%.3f".format(p)} < ${prophet.config.pMinThreshold}")
                }
            }
            if (dropIds.isNotEmpty()) {
                queueManager.dropMessages(dropIds)
                Log.d(TAG, "P-threshold drop: ${dropIds.size} messages (P below ${prophet.config.pMinThreshold})")
            }
        }

        Log.d(TAG, "Housekeeping cycle complete")
        return Result.success()
    }
}
