package com.dtn.mesh.service

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks which message UIDs have been confirmed as delivered to their final destination.
 *
 * When a message reaches its intended recipient, the UID is added here.
 * When we encounter any peer, we exchange receipt lists:
 * - We send our receipts → peer clears those from its buffer.
 * - Peer sends their receipts → we clear those from our buffer.
 *
 * This solves the mule problem: mule carries a message, delivers it to C,
 * then when mule meets A (original sender), the receipt propagates and A clears its buffer.
 */
@Singleton
class DeliveryReceiptStore @Inject constructor() {

    /** Set of message UIDs confirmed delivered to their final destination. */
    private val deliveredUids = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Record that a message reached its final destination. */
    fun markDelivered(msgId: String) {
        deliveredUids.add(msgId)
    }

    /** Check if a message has been confirmed delivered. */
    fun isDelivered(msgId: String): Boolean = deliveredUids.contains(msgId)

    /** Get all delivery receipts (for sharing with peers during encounter). */
    fun getAllReceipts(): Set<String> = deliveredUids.toSet()

    /** Import receipts from a peer (they told us these messages were delivered). */
    fun importReceipts(receipts: Set<String>) {
        deliveredUids.addAll(receipts)
    }

    /** Total number of receipts stored. */
    val size: Int get() = deliveredUids.size
}
