package com.dtn.mesh.service

import android.util.Log
import com.dtn.mesh.database.dao.ContactDao
import com.dtn.mesh.database.dao.EncounterDao
import com.dtn.mesh.database.dao.ForwardingDecisionDao
import com.dtn.mesh.database.entity.ContactEntity
import com.dtn.mesh.database.entity.EncounterEntity
import com.dtn.mesh.database.entity.ForwardingDecisionEntity
import com.dtn.mesh.learning.DoubleQLearningEngine
import com.dtn.mesh.learning.ForwardingState
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.DtnMessageType
import com.dtn.mesh.model.ForwardingAction
import com.dtn.mesh.model.NodeId
import com.dtn.mesh.queue.MessageQueueManager
import com.dtn.mesh.receiver.DeliveryOutcome
import com.dtn.mesh.receiver.DeliveryStatusEvent
import com.dtn.mesh.receiver.InboundPacket
import com.dtn.mesh.receiver.MeshTransport
import com.dtn.mesh.receiver.NodeEncounterEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DTN Orchestrator — implements store-carry-forward with:
 *
 * 1. DIRECT DELIVERY: send to destination if in range
 * 2. CUSTODY TRANSFER: hand off to mule peers who relay toward the destination
 * 3. DELIVERY RECEIPTS: propagate "message X reached destination" so all carriers
 *    clear their buffers for that message.
 *
 * ## Receipt wire format
 * A receipt bundle is a normal DtnMessage with messageType = ROUTING_ACK and payload
 * containing UUID bytes (16 bytes each) concatenated. The wire header carries a fresh
 * random UUID as the bundle id; the payload is the LIST of message ids we've confirmed
 * delivered. If more receipts exist than fit in one bundle, we send multiple bundles.
 */
@Singleton
class DtnOrchestrator @Inject constructor(
    private val transport: MeshTransport,
    private val queueManager: MessageQueueManager,
    private val receiptStore: DeliveryReceiptStore,
    private val qEngine: DoubleQLearningEngine,
    private val contactDao: ContactDao,
    private val encounterDao: EncounterDao,
    private val decisionDao: ForwardingDecisionDao,
) {
    companion object {
        private const val TAG = "DtnOrchestrator"
        /** Each UUID = 16 bytes; max receipts per bundle = MAX_SINGLE_PACKET_PAYLOAD / 16. */
        private const val UUID_BYTES = 16
        private const val MAX_RECEIPTS_PER_BUNDLE = DtnMessage.MAX_SINGLE_PACKET_PAYLOAD / UUID_BYTES // 13
    }

    private sealed class OrchestratorEvent {
        data class Encounter(val event: NodeEncounterEvent) : OrchestratorEvent()
        data class Message(val packet: InboundPacket) : OrchestratorEvent()
        data class Status(val event: DeliveryStatusEvent) : OrchestratorEvent()
        data class QUpdateBatch(val decisions: List<ForwardingDecisionEntity>) : OrchestratorEvent()
        data object FlushBuffer : OrchestratorEvent()
    }

    private val eventChannel = Channel<OrchestratorEvent>(capacity = Channel.BUFFERED)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    /** Tracks which peers already received which messages (echo prevention). */
    private val sentTo = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    private fun markSentTo(msgId: String, peer: String) {
        sentTo.getOrPut(msgId) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(peer)
    }
    private fun alreadySentTo(msgId: String, peer: String): Boolean =
        sentTo[msgId]?.contains(peer) == true

    // ── Lifecycle ────────────────────────────────────────────────────────

    fun start() {
        if (isRunning) return
        isRunning = true
        Log.i(TAG, "Started")
        scope.launch { transport.nodeEvents.collect { eventChannel.send(OrchestratorEvent.Encounter(it)) } }
        scope.launch { transport.inboundMessages.collect { eventChannel.send(OrchestratorEvent.Message(it)) } }
        scope.launch { transport.deliveryStatus.collect { eventChannel.send(OrchestratorEvent.Status(it)) } }
        scope.launch {
            for (ev in eventChannel) {
                try {
                    when (ev) {
                        is OrchestratorEvent.Encounter -> onPeerEvent(ev.event)
                        is OrchestratorEvent.Message -> onMessageReceived(ev.packet)
                        is OrchestratorEvent.Status -> onDeliveryStatus(ev.event)
                        is OrchestratorEvent.QUpdateBatch -> processBatchQUpdates(ev.decisions)
                        is OrchestratorEvent.FlushBuffer -> flushToOnlinePeers()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Handler error for $ev", e)
                }
            }
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        eventChannel.close()
        scope.cancel()
        Log.i(TAG, "Stopped")
    }

    suspend fun enqueueBatchQUpdates(d: List<ForwardingDecisionEntity>) {
        if (isRunning) eventChannel.send(OrchestratorEvent.QUpdateBatch(d))
    }

    suspend fun triggerFlush() {
        if (isRunning) eventChannel.send(OrchestratorEvent.FlushBuffer)
    }

    // ══════════════════════════════════════════════════════════════════════
    // ENCOUNTER
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun onPeerEvent(event: NodeEncounterEvent) {
        val peerId = event.nodeId

        if (!event.isOnline) {
            contactDao.markOffline(peerId.value)
            encounterDao.getMostRecentEncounter(peerId.value)?.let { enc ->
                if (enc.endTimeMs == 0L) encounterDao.closeEncounter(enc.id, System.currentTimeMillis())
            }
            queueManager.revertForwardingForPeer(peerId)
            Log.d(TAG, "OFFLINE: ${peerId.value.takeLast(8)}")
            return
        }

        contactDao.insertIfNew(ContactEntity(nodeId = peerId.value))
        if (event.longName != null || event.shortName != null) {
            contactDao.updateNodeInfo(peerId.value, event.longName, event.shortName, event.hwModel)
        }

        val now = System.currentTimeMillis()
        val lastEnc = encounterDao.getMostRecentEncounter(peerId.value)
        val isNew = lastEnc == null || lastEnc.endTimeMs != 0L ||
            (now - lastEnc.startTimeMs) > EncounterEntity.ENCOUNTER_GAP_THRESHOLD_MS
        if (isNew) {
            encounterDao.insert(EncounterEntity(
                peerNodeId = peerId.value, startTimeMs = now,
                bestRssi = event.rssi, bestSnr = event.snr,
            ))
            contactDao.recordEncounter(peerId.value, now, 0, event.rssi, event.snr)
        }

        Log.d(TAG, "ONLINE: ${peerId.value.takeLast(8)}")

        // Send our delivery receipts to the peer (so their buffer can clear).
        sendDeliveryReceipts(peerId)

        // Then attempt to send our buffered messages to this peer.
        sendBufferedTo(peerId)
    }

    /**
     * Send buffered messages to all currently-online peers.
     *
     * Snapshot the buffer once so a broadcast reaches every peer (marking a message
     * delivered mid-loop would remove it from the buffer before later peers get it).
     */
    private suspend fun flushToOnlinePeers() {
        val peers = contactDao.getOnlineContacts()
        if (peers.isEmpty()) return

        val snapshot = queueManager.getAllBuffered()
        if (snapshot.isEmpty()) return

        // Track which broadcast messages we successfully fanned out — clear them at end.
        val broadcastsToClear = mutableSetOf<String>()

        for (peer in peers) {
            sendToSingle(NodeId(peer.nodeId), snapshot, broadcastsToClear)
        }

        // Broadcasts: after fanning out to every online peer this cycle, clear from buffer.
        for (msgId in broadcastsToClear) {
            queueManager.markDelivered(msgId)
            Log.d(TAG, "BROADCAST cleared: $msgId (sent to ${peers.size} peers)")
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // SEND: deliver or relay buffered messages
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Single-peer send (called on peer-online event). Delegates to the full flush so
     * broadcast state is tracked consistently across all online peers.
     */
    private suspend fun sendBufferedTo(peerId: NodeId) {
        flushToOnlinePeers()
    }

    /**
     * Send applicable messages from [snapshot] to a single peer.
     * - Direct delivery (dest == peer): send + mark delivered immediately (receipt generated).
     * - Broadcast: send. Outer loop clears once fanned out to every online peer.
     * - Custody transfer (dest is someone else): send to mule, keep our copy for receipt.
     */
    private suspend fun sendToSingle(
        peerId: NodeId,
        snapshot: List<DtnMessage>,
        broadcastsToClear: MutableSet<String>,
    ) {
        var sentCount = 0
        for (msg in snapshot) {
            // Skip if we know this msg has already been delivered somewhere (via receipt)
            if (receiptStore.isDelivered(msg.id) && msg.destinationNodeId != NodeId.BROADCAST) {
                queueManager.markDelivered(msg.id)
                continue
            }
            if (msg.originNodeId.value == peerId.value) continue
            if (alreadySentTo(msg.id, peerId.value)) continue

            val isBroadcast = msg.destinationNodeId == NodeId.BROADCAST
            val isDirectDelivery = msg.destinationNodeId.value == peerId.value

            val ok = transport.sendMessage(msg, peerId)
            if (ok == null) {
                Log.w(TAG, "FAIL: ${peerId.value.takeLast(8)} unreachable")
                break
            }

            markSentTo(msg.id, peerId.value)
            sentCount++

            when {
                isDirectDelivery -> {
                    queueManager.markDelivered(msg.id)
                    receiptStore.markDelivered(msg.id)
                    Log.d(TAG, "DELIVERED: ${msg.id.take(8)} → ${peerId.value.takeLast(8)}")
                }
                isBroadcast -> {
                    // Just fan out — outer loop clears after every peer got it.
                    broadcastsToClear.add(msg.id)
                    Log.d(TAG, "BCAST → ${peerId.value.takeLast(8)}: ${msg.id.take(8)}")
                }
                else -> {
                    Log.d(TAG, "RELAYED: ${msg.id.take(8)} → mule ${peerId.value.takeLast(8)}")
                }
            }
        }
        if (sentCount > 0) Log.d(TAG, "$sentCount msgs sent to ${peerId.value.takeLast(8)}")
    }

    // ══════════════════════════════════════════════════════════════════════
    // DELIVERY RECEIPTS: propagate confirmation
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Send our delivery receipt list to a peer, chunked into multiple bundles if needed.
     * Each bundle carries up to MAX_RECEIPTS_PER_BUNDLE (13) UIDs as raw 16-byte UUIDs.
     */
    private suspend fun sendDeliveryReceipts(peerId: NodeId) {
        val receipts = receiptStore.getAllReceipts()
        if (receipts.isEmpty()) return

        // Convert receipt UIDs to their raw 16-byte UUID form.
        // Skip any UID that isn't a valid UUID (e.g. legacy ids from earlier builds).
        val uuidBytes = receipts.mapNotNull { uid ->
            try {
                val u = UUID.fromString(uid)
                val bb = java.nio.ByteBuffer.allocate(UUID_BYTES)
                bb.putLong(u.mostSignificantBits)
                bb.putLong(u.leastSignificantBits)
                bb.array()
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        if (uuidBytes.isEmpty()) return

        // Chunk into batches that fit in a single DTN bundle payload.
        val chunks = uuidBytes.chunked(MAX_RECEIPTS_PER_BUNDLE)
        val myId = transport.localNodeId ?: NodeId.LOCAL

        for (chunk in chunks) {
            val payload = ByteArray(chunk.size * UUID_BYTES)
            for ((i, b) in chunk.withIndex()) {
                System.arraycopy(b, 0, payload, i * UUID_BYTES, UUID_BYTES)
            }
            val receiptBundle = DtnMessage(
                id = UUID.randomUUID().toString(), // fresh valid UUID for the bundle itself
                originNodeId = myId,
                destinationNodeId = peerId,
                payloadBytes = payload,
                createdAtMs = System.currentTimeMillis(),
                ttlMs = 300_000L, // 5 min — receipts are short-lived control traffic
                messageType = DtnMessageType.ROUTING_ACK,
            )
            transport.sendMessage(receiptBundle, peerId)
        }
        Log.d(TAG, "Sent ${uuidBytes.size} receipts to ${peerId.value.takeLast(8)} in ${chunks.size} bundle(s)")
    }

    /**
     * Parse a receipt bundle payload — a concatenation of 16-byte UUIDs.
     * For each UID: mark it delivered locally and clear from buffer if present.
     */
    private suspend fun processInboundReceipts(msg: DtnMessage) {
        val payload = msg.payloadBytes
        if (payload.size < UUID_BYTES) return
        val count = payload.size / UUID_BYTES

        var cleared = 0
        for (i in 0 until count) {
            val off = i * UUID_BYTES
            val bb = java.nio.ByteBuffer.wrap(payload, off, UUID_BYTES)
            val uuid = UUID(bb.long, bb.long).toString()
            receiptStore.markDelivered(uuid)
            if (queueManager.existsInBuffer(uuid)) {
                queueManager.markDelivered(uuid)
                cleared++
            }
        }
        if (cleared > 0) Log.d(TAG, "RECEIPTS-IN: $cleared msgs cleared from buffer")
    }

    // ══════════════════════════════════════════════════════════════════════
    // RECEIVE: inbound message handling
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun onMessageReceived(packet: InboundPacket) {
        val msg = packet.message

        // Delivery receipt bundle — handle and return.
        if (msg.messageType == DtnMessageType.ROUTING_ACK) {
            processInboundReceipts(msg)
            return
        }
        if (!msg.messageType.isUserData) return

        val myId = transport.localNodeId?.value
        val isForMe = myId != null && msg.destinationNodeId.value == myId
        val isBroadcast = msg.destinationNodeId == NodeId.BROADCAST

        if (isForMe || isBroadcast) {
            // MESSAGE IS FOR US — ingest and mark delivered locally.
            val stored = queueManager.ingest(msg)
            if (stored) {
                queueManager.markDelivered(msg.id)
                receiptStore.markDelivered(msg.id) // we now have a receipt to propagate
                Log.d(TAG, "RX-MINE: ${msg.id.take(8)} from ${msg.originNodeId.value.takeLast(8)}")
            }
            return
        }

        // MESSAGE FOR SOMEONE ELSE — we're a mule.
        // If we already have a receipt saying it's delivered, ignore.
        if (receiptStore.isDelivered(msg.id)) {
            Log.d(TAG, "RX-MULE ignored (already delivered): ${msg.id.take(8)}")
            return
        }
        val stored = queueManager.ingest(msg)
        if (stored) {
            Log.d(TAG, "RX-MULE: ${msg.id.take(8)} → dest ${msg.destinationNodeId.value.takeLast(8)}")
            // Try to deliver onward immediately if destination is online.
            flushToOnlinePeers()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // DELIVERY STATUS (from transport layer)
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun onDeliveryStatus(event: DeliveryStatusEvent) {
        if (event.status == DeliveryOutcome.DELIVERED) {
            queueManager.markDeliveredByPacketId(event.meshPacketId)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Q-ENGINE BATCH UPDATES (research only)
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun processBatchQUpdates(decisions: List<ForwardingDecisionEntity>) {
        for (d in decisions) {
            val state = ForwardingState(
                d.stateRssiNorm, d.stateSnrNorm, d.stateEncounterFrequency,
                d.stateEncounterDuration, d.stateDeliveryProbability, d.stateRemainingTtlFraction,
                d.stateBufferOccupancy, d.stateHistoricalSuccessRate, d.stateDuplicateCountNorm,
            )
            val reward = d.reward ?: continue
            qEngine.update(state, ForwardingAction.valueOf(d.action), reward, nextState = null)
        }
    }
}
