package com.dtn.mesh.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dtn.mesh.database.dao.ContactDao
import com.dtn.mesh.database.dao.MessageDao
import com.dtn.mesh.export.FileExportHelper
import com.dtn.mesh.export.ResearchExporter
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.DtnMessageType
import com.dtn.mesh.model.NodeId
import com.dtn.mesh.queue.MessageQueueManager
import com.dtn.mesh.receiver.MeshConnectionState
import com.dtn.mesh.receiver.MeshTransport
import com.dtn.mesh.routing.StrategySelector
import com.dtn.mesh.service.DtnOrchestrator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class DtnViewModel @Inject constructor(
    application: Application,
    private val transport: MeshTransport,
    private val orchestrator: DtnOrchestrator,
    private val queueManager: MessageQueueManager,
    private val strategySelector: StrategySelector,
    private val exporter: ResearchExporter,
    private val contactDao: ContactDao,
    private val messageDao: MessageDao,
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "DtnViewModel"
        /** Sentinel string used to represent the "broadcast to everyone" chat in the UI. */
        const val BROADCAST_CHAT_ID = "__broadcast__"
    }

    val connectionState: StateFlow<MeshConnectionState> = transport.connectionState

    private val _localNodeId = MutableStateFlow<String?>(null)
    val localNodeId: StateFlow<String?> = _localNodeId.asStateFlow()

    private val _logEntries = MutableStateFlow<List<String>>(emptyList())
    val logEntries: StateFlow<List<String>> = _logEntries.asStateFlow()

    /** Reactive buffered count straight from Room — always accurate. */
    val bufferedCount: StateFlow<Int> = queueManager.observeBufferedCount()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val _exportPath = MutableStateFlow<String?>(null)
    val exportPath: StateFlow<String?> = _exportPath.asStateFlow()

    private val _activeStrategy = MutableStateFlow("PROPHET")
    val activeStrategy: StateFlow<String> = _activeStrategy.asStateFlow()

    /**
     * Discovered peers with online state, driven by the Room `contacts` table. This means:
     * - Offline peers stay in the list (so we can still target them; the message goes to buffer).
     * - Peers survive app restarts (Task 3).
     * - Custom nicknames (Task 4) flow through automatically.
     */
    val peerList: StateFlow<List<PeerInfo>> = contactDao.observeAll()
        .map { contacts ->
            contacts.map { c ->
                PeerInfo(
                    nodeId = c.nodeId,
                    isOnline = c.isOnline,
                    lastSeenMs = c.lastEncounterMs,
                    customName = c.customName,
                    longName = c.longName,
                    lastRssi = c.lastRssi,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Backward-compatibility shim — just node ids. */
    val discoveredPeers: StateFlow<List<String>> = peerList
        .map { list -> list.map { it.nodeId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Currently selected chat.
     *   null  → chat list screen (no chat open)
     *   [BROADCAST_CHAT_ID] → the broadcast conversation
     *   any other value → the 1:1 conversation with that node id
     */
    private val _selectedChat = MutableStateFlow<String?>(null)
    val selectedChat: StateFlow<String?> = _selectedChat.asStateFlow()

    /**
     * Legacy destination selector (kept because parts of the app still read it). Kept in sync
     * with [_selectedChat]: broadcast → null destination, 1:1 → that peer.
     */
    private val _selectedDestination = MutableStateFlow<String?>(null)
    val selectedDestination: StateFlow<String?> = _selectedDestination.asStateFlow()

    /** All chat messages (sent + received) — used by the Log tab and to compute per-peer views. */
    private val _receivedMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val receivedMessages: StateFlow<List<ChatMessage>> = _receivedMessages.asStateFlow()

    /**
     * Messages filtered to the currently selected chat.
     *
     * Rules:
     * - Broadcast chat: only broadcasts (toNodeId == null).
     * - 1:1 chat with peer X: only messages whose conversation peer is X — broadcasts are
     *   *not* mixed in, they live exclusively in the broadcast chat.
     * - No chat open: empty.
     */
    val filteredMessages: StateFlow<List<ChatMessage>> = combine(
        _receivedMessages,
        _selectedChat,
    ) { all, selected ->
        when (selected) {
            null -> emptyList()
            BROADCAST_CHAT_ID -> all.filter { it.toNodeId == null }
            else -> all.filter { it.peerOf() == selected }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Message ids already displayed as inbound — dedup guard against multi-path/re-flood. */
    private val seenInboundIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** True once the historical chat log has been read from Room. Prevents duplicate rehydration. */
    @Volatile
    private var chatHistoryLoaded = false

    init {
        _localNodeId.value = transport.localNodeId?.value

        // NODE events: just refresh our known local id (peer bookkeeping is now DB-driven).
        viewModelScope.launch {
            transport.nodeEvents.collect { event ->
                addLog("NODE: ${event.nodeId.value} online=${event.isOnline} rssi=${event.rssi}")
                _localNodeId.value = transport.localNodeId?.value
            }
        }

        viewModelScope.launch {
            transport.inboundMessages.collect { packet ->
                val msg = packet.message

                // Ignore control-plane bundles (receipts, routing summaries) in the chat UI.
                if (!msg.messageType.isUserData) return@collect

                // Dedup by message id — the same bundle can physically arrive more than once.
                if (!seenInboundIds.add(msg.id)) return@collect

                // Only messages addressed to us or broadcast should appear in the chat.
                val myId = transport.localNodeId?.value
                val isForMe = myId != null && msg.destinationNodeId.value == myId
                val isBroadcast = msg.destinationNodeId == NodeId.BROADCAST
                if (!isForMe && !isBroadcast) return@collect

                val text = String(msg.payloadBytes, Charsets.UTF_8)
                val from = msg.originNodeId.value
                addLog("RX from ${from.takeLast(8)}: \"$text\"")
                _receivedMessages.value = _receivedMessages.value + ChatMessage(
                    msgId = msg.id,
                    text = text,
                    from = from,
                    // For a broadcast toNodeId=null (renders only in broadcast chat). For an
                    // incoming 1:1, we store the sender as the conversation peer so the filter
                    // `it.peerOf() == selected` catches it in that peer's 1:1 chat.
                    toNodeId = if (isBroadcast) null else from,
                    isOutgoing = false,
                    status = "✓ received",
                    time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date()),
                )
            }
        }

        viewModelScope.launch {
            // Transport-level ACK is per-hop, not end-to-end — we only log it here. A
            // hop-ACK for a mule-relayed bundle would incorrectly promote the sender's
            // chat bubble to "delivered", so we DON'T mark UI delivered from this signal.
            transport.deliveryStatus.collect { event ->
                addLog("ACK: pktId=${event.meshPacketId} status=${event.status}")
            }
        }

        // Real end-to-end delivered signal. Fires when queueManager.markDelivered is called
        // (direct delivery, broadcast fan-out completes, or a receipt arrives).
        viewModelScope.launch {
            queueManager.deliveredIds.collect { msgId -> markChatDeliveredById(msgId) }
        }

        // Load past chat history from DB. We do this once at construction — as soon as we
        // know our own node id — so previously-exchanged messages survive app restarts.
        viewModelScope.launch { loadChatHistoryIfPossible() }
    }

    /** Load the historical chat view (best-effort — silently no-ops if we don't know our id yet). */
    private suspend fun loadChatHistoryIfPossible() {
        if (chatHistoryLoaded) return
        val myId = transport.localNodeId?.value ?: return
        val rows = messageDao.getChatHistoryFor(myId)
        chatHistoryLoaded = true
        val history = rows.mapNotNull { e ->
            val text = try { String(e.payload, Charsets.UTF_8) } catch (_: Exception) { return@mapNotNull null }
            val isOutgoing = e.originNodeId == myId
            val isBroadcast = e.destinationNodeId == "^all"
            val peer = when {
                isBroadcast -> null              // toNodeId=null → lives in the broadcast chat only
                isOutgoing -> e.destinationNodeId
                else -> e.originNodeId
            }
            val status = when (e.status) {
                "DELIVERED" -> if (isOutgoing) "✓ delivered" else "✓ received"
                "BUFFERED", "FORWARDING" -> "buffered"
                "EXPIRED" -> "expired"
                "DROPPED" -> "dropped"
                else -> e.status
            }
            ChatMessage(
                msgId = e.id,
                text = text,
                from = if (isOutgoing) "me" else e.originNodeId,
                toNodeId = peer,
                isOutgoing = isOutgoing,
                status = status,
                time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(e.createdAtMs)),
            )
        }
        // Mark all as already-seen so we don't re-append duplicates when live inbound flow catches up.
        for (r in rows) seenInboundIds.add(r.id)
        _receivedMessages.value = history + _receivedMessages.value
    }

    // ── Actions ──────────────────────────────────────────────────────────

    fun connect() {
        viewModelScope.launch {
            addLog("Starting all transports (BLE + WiFi Direct + LoRa hub)...")
            com.dtn.mesh.service.DtnForegroundService.start(getApplication())
            val success = transport.connect()
            if (success) {
                addLog("Transports initiated — foreground service running")
                _localNodeId.value = transport.localNodeId?.value
                // Now that we (probably) know our id, try loading chat history if init couldn't.
                loadChatHistoryIfPossible()
            } else {
                addLog("ERROR: Connection failed")
            }
        }
    }

    fun connectHubOnly() {
        viewModelScope.launch {
            addLog("Joining LoRa hub WiFi...")
            val mgr = transport as? com.dtn.mesh.receiver.MultiTransportManager
            val success = mgr?.connectHub() ?: transport.connect()
            if (success) {
                orchestrator.start()
                addLog("LoRa hub transport connecting")
            } else {
                addLog("ERROR: could not join hub — is a DTN-HUB in range?")
            }
        }
    }

    fun connectBleOnly() {
        viewModelScope.launch {
            addLog("Starting BLE discovery...")
            val mgr = transport as? com.dtn.mesh.receiver.MultiTransportManager
            val success = mgr?.connectBle() ?: false
            if (success) {
                orchestrator.start()
                addLog("BLE advertising + scanning started")
            } else {
                addLog("ERROR: BLE not available (is Bluetooth on?)")
            }
        }
    }

    fun connectWifiDirectOnly() {
        viewModelScope.launch {
            addLog("Starting WiFi Direct discovery...")
            val mgr = transport as? com.dtn.mesh.receiver.MultiTransportManager
            val success = mgr?.connectWifiDirect() ?: false
            if (success) {
                orchestrator.start()
                addLog("WiFi Direct discovery started")
            } else {
                addLog("ERROR: WiFi Direct not available")
            }
        }
    }

    fun disconnect() {
        com.dtn.mesh.service.DtnForegroundService.stop(getApplication())
        orchestrator.stop()
        transport.disconnect()
        // Once our transports are down we can't verify any peer's liveness. Clearing the
        // online flags avoids the classic reconnect bug: sendMessage sees a stale is_online=1
        // and tries to hand a bundle to a device we haven't rediscovered yet, so the message
        // silently buffers. Peers flip green again on the next real encounter after reconnect.
        viewModelScope.launch { contactDao.markAllOffline() }
        addLog("Disconnected — foreground service stopped")
    }

    // ── Permissions ──────────────────────────────────────────────────────

    fun onPermissionsGranted() {
        addLog("All permissions granted")
    }

    fun onPermissionsDenied(denied: List<String>) {
        addLog("WARN: permissions denied: ${denied.joinToString()}")
        addLog("Some transports may not work without these permissions")
    }

    /**
     * Open a chat. Pass [BROADCAST_CHAT_ID] for the broadcast conversation, a node id for a
     * 1:1 chat, or null to return to the chat list screen.
     */
    fun openChat(chatKey: String?) {
        _selectedChat.value = chatKey
        _selectedDestination.value = when (chatKey) {
            null, BROADCAST_CHAT_ID -> null   // legacy: null = broadcast
            else -> chatKey
        }
        if (chatKey != null) {
            addLog("Opened chat: ${chatKey.takeLast(8)}")
        }
    }

    /** Set (or clear) a user-provided nickname for a peer. Blank string clears the name. */
    fun renamePeer(nodeId: String, name: String) {
        viewModelScope.launch {
            val clean = name.trim().ifBlank { null }
            contactDao.updateCustomName(nodeId, clean)
            addLog("Renamed ${nodeId.takeLast(8)} → ${clean ?: "(cleared)"}")
        }
    }

    fun sendTestMessage(text: String) {
        viewModelScope.launch {
            val destKey = _selectedChat.value
            val dest = when (destKey) {
                null, BROADCAST_CHAT_ID -> NodeId.BROADCAST
                else -> NodeId(destKey)
            }
            val msg = DtnMessage(
                id = UUID.randomUUID().toString(),
                originNodeId = transport.localNodeId ?: NodeId.LOCAL,
                destinationNodeId = dest,
                payloadBytes = text.toByteArray(Charsets.UTF_8),
                createdAtMs = System.currentTimeMillis(),
                ttlMs = 4 * 3_600_000L, // 4 hours
                messageType = DtnMessageType.DATA,
            )
            val stored = queueManager.ingest(msg)
            if (stored) {
                val isBroadcast = dest == NodeId.BROADCAST
                val target = if (isBroadcast) "ALL" else dest.value.takeLast(8)
                addLog("QUEUED: \"$text\" → $target (${msg.id.take(8)})")
                _receivedMessages.value = _receivedMessages.value + ChatMessage(
                    msgId = msg.id,
                    text = text,
                    from = "me",
                    // Broadcast → toNodeId=null (lives only in the broadcast chat).
                    toNodeId = if (isBroadcast) null else dest.value,
                    isOutgoing = true,
                    status = "buffered",
                    time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date()),
                )
                // Trigger immediate forwarding. Wrapped defensively so a stopped-and-restarted
                // orchestrator (see restartable channel in DtnOrchestrator) can never crash us.
                runCatching { orchestrator.triggerFlush() }
                    .onFailure { Log.e(TAG, "triggerFlush failed", it) }
            } else {
                addLog("REJECTED: duplicate or expired")
            }
        }
    }

    fun switchStrategy() {
        val newType = if (strategySelector.activeType == StrategySelector.StrategyType.PROPHET)
            StrategySelector.StrategyType.MAXPROP else StrategySelector.StrategyType.PROPHET
        strategySelector.switchTo(newType)
        _activeStrategy.value = newType.name
        addLog("Strategy switched to ${newType.name}")
    }

    /** Manually retry sending all buffered messages to online peers. */
    fun syncBuffer() {
        viewModelScope.launch {
            addLog("Manual sync — retrying buffered messages...")
            runCatching { orchestrator.triggerFlush() }
                .onFailure { Log.e(TAG, "triggerFlush failed", it) }
        }
    }

    /** Manually clear the buffer (force-drop all pending messages). */
    fun clearBuffer() {
        viewModelScope.launch {
            val n = queueManager.clearBuffer()
            addLog("Cleared $n buffered message(s)")
        }
    }

    fun exportData() {
        viewModelScope.launch {
            addLog("Exporting research data...")
            val bundle = exporter.exportAll()
            val path = FileExportHelper.writeToStorage(getApplication(), bundle)
            _exportPath.value = path
            addLog(if (path != null) "Exported to: $path" else "Export FAILED")
        }
    }

    /**
     * Flip the outgoing chat bubble with the given DTN message id to "✓ delivered".
     * Called from the [MessageQueueManager.deliveredIds] observer — never fires for
     * mule-relayed hops, only for real end-delivery.
     */
    private fun markChatDeliveredById(msgId: String) {
        val list = _receivedMessages.value.toMutableList()
        val idx = list.indexOfFirst { it.msgId == msgId && it.isOutgoing }
        if (idx >= 0 && list[idx].status != "✓ delivered") {
            list[idx] = list[idx].copy(status = "✓ delivered")
            _receivedMessages.value = list
        }
    }

    private fun addLog(entry: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        _logEntries.value = listOf("[$timestamp] $entry") + _logEntries.value.take(99)
        Log.d(TAG, entry)
    }
}

/** A single chat message displayed in the chat views. */
data class ChatMessage(
    /** DTN message id — used to correlate delivery signals back to the UI bubble. */
    val msgId: String,
    val text: String,
    /** Sender id, or "me" for outgoing messages. */
    val from: String,
    /**
     * Conversation key. `null` for broadcasts (they live only in the broadcast chat).
     * For outgoing 1:1: destination node id.
     * For incoming 1:1: sender node id (the peer we're chatting with).
     */
    val toNodeId: String?,
    val isOutgoing: Boolean,
    val status: String,
    val time: String,
) {
    /** The "other side" of this chat — used by [DtnViewModel.filteredMessages]. */
    fun peerOf(): String? = toNodeId
}

/** A peer we've seen. Sourced from the Room contacts table so it survives app restarts. */
data class PeerInfo(
    val nodeId: String,
    val isOnline: Boolean,
    val lastSeenMs: Long,
    /** User-provided nickname; falls back to [longName] then to the truncated id. */
    val customName: String? = null,
    val longName: String? = null,
    /** Most recent RSSI in dBm from any transport (sentinel -200 = unknown). */
    val lastRssi: Int = -200,
) {
    /** Display label preferring the custom nickname, then longName, then the last 8 of the id. */
    fun displayName(): String =
        customName?.takeIf { it.isNotBlank() }
            ?: longName?.takeIf { it.isNotBlank() }
            ?: nodeId.takeLast(8)
}
