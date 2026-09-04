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
    private val lifecycleLog: com.dtn.mesh.service.MessageLifecycleLog,
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

    /** Live list of all currently-buffered messages (for the Network tab). */
    val bufferedMessages: StateFlow<List<BufferedMsgInfo>> = queueManager.observeBufferedMessages()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _exportPath = MutableStateFlow<String?>(null)
    val exportPath: StateFlow<String?> = _exportPath.asStateFlow()

    /** Message lifecycle events for the dedicated Lifecycle page. */
    val lifecycleEntries: StateFlow<List<com.dtn.mesh.service.LifecycleEntry>> = lifecycleLog.entries

    /** Per-message hop routes for the Network tab. */
    val messageRoutes: StateFlow<List<com.dtn.mesh.service.MessageLifecycleLog.RouteRecord>> = lifecycleLog.routes

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
                // Start the orchestrator directly rather than relying solely on the
                // foreground service's onCreate. The service start is async and can be
                // delayed (or fail before reaching orchestrator.start() if startForeground
                // throws), which left the orchestrator's encounter->contacts persistence
                // never running: peers showed up in the raw NODE log (this ViewModel
                // collects transport.nodeEvents directly) but never appeared in the chat /
                // peer list, which is driven by the Room contacts table written only in
                // DtnOrchestrator.onPeerEvent. start() is idempotent, so the service
                // calling it too is a harmless no-op.
                orchestrator.start()
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
                lifecycleLog.log(com.dtn.mesh.service.LifecycleEvent.CREATED, msg.id,
                    origin = msg.originNodeId.value, dest = msg.destinationNodeId.value,
                    ttlRemainingMs = msg.ttlMs)
                lifecycleLog.log(com.dtn.mesh.service.LifecycleEvent.BUFFERED, msg.id,
                    origin = msg.originNodeId.value, dest = msg.destinationNodeId.value)
                lifecycleLog.recordAsOrigin(msg.id,
                    source = msg.originNodeId.value,
                    dest = msg.destinationNodeId.value,
                    me = msg.originNodeId.value)
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

    /** Explicitly select a routing protocol from the UI switch. [typeName] is "PROPHET" or "MAXPROP". */
    fun setStrategy(typeName: String) {
        val type = runCatching { StrategySelector.StrategyType.valueOf(typeName) }
            .getOrDefault(StrategySelector.StrategyType.PROPHET)
        if (type == strategySelector.activeType) return
        strategySelector.switchTo(type)
        _activeStrategy.value = type.name
        addLog("Strategy set to ${type.name}")
    }

    /**
     * Build a human-readable breakdown of how PROPHET would route toward [destNodeId]:
     * my own delivery predictability P(me,dest), each online peer's belief P(peer,dest), the
     * 2-hop path likelihood through them P(me,peer)·P(peer,dest), and the transitive gain each
     * would contribute to my table. Returns null when the active strategy isn't PROPHET
     * (MaxProp uses a different cost model) or the destination is blank/broadcast.
     */
    fun explainRoute(destNodeId: String): RoutingExplanation? {
        if (destNodeId.isBlank() || destNodeId == "^all" || destNodeId == NodeId.BROADCAST.value) return null
        val dest = NodeId(destNodeId)
        val candidates = peerList.value.filter { it.nodeId != destNodeId }

        return when (val active = strategySelector.active) {
            is com.dtn.mesh.routing.ProphetStrategy -> {
                val cfg = active.config
                val myP = active.getDeliveryProbability(dest)
                val beliefs = candidates.map { peer ->
                    val pMeToNode = active.getDeliveryProbability(NodeId(peer.nodeId))
                    val pNodeToDest = active.peerDeliveryProbability(NodeId(peer.nodeId), dest)
                    val pathVia = pMeToNode * pNodeToDest
                    val gain = (1.0 - myP) * pMeToNode * pNodeToDest * cfg.betaTransitivity
                    RoutingNodeBelief(
                        nodeId = peer.nodeId,
                        displayName = peer.displayName(),
                        isOnline = peer.isOnline,
                        score = pathVia,
                        detail = "P(me→node) ${fmt(pMeToNode)} · P(node→dest) ${fmt(pNodeToDest)} · +transit ${fmt(gain)}",
                    )
                }.sortedByDescending { it.score }
                val best = beliefs.firstOrNull { it.score > 0.0 }
                RoutingExplanation(
                    dest = destNodeId,
                    strategy = "PROPHET",
                    myScore = myP,
                    myScoreLabel = "P(me → dest)",
                    beliefs = beliefs,
                    bestNextHop = best?.nodeId,
                    bestNextHopName = best?.displayName,
                    bestScore = best?.score ?: 0.0,
                    bestScoreLabel = "P_path",
                    formulas = listOf(
                        FormulaItem("Direct encounter", "P(a,b) = P + (1−P)·P_init + ε^d"),
                        FormulaItem("Transitivity", "P(a,c) += (1−P(a,c))·P(a,b)·P(b,c)·β"),
                        FormulaItem("Aging", "P(a,b) = P(a,b)·γ^k"),
                        FormulaItem("2-hop path", "P_path(via j) = P(me,j)·P(j,dest)"),
                    ),
                    constantsLine = "P_init=${fmt2(cfg.pEncounter)} · β=${fmt2(cfg.betaTransitivity)} · " +
                        "γ=${fmt2(cfg.gammaAging)} · path floor=${fmt2(cfg.pFloorPath)}",
                )
            }
            is com.dtn.mesh.routing.MaxPropStrategy -> {
                val cfg = active.config
                val fDest = active.getDeliveryProbability(dest) // f(me, dest)
                val beliefs = candidates.map { peer ->
                    val fPeer = active.getDeliveryProbability(NodeId(peer.nodeId)) // f(me, peer)
                    RoutingNodeBelief(
                        nodeId = peer.nodeId,
                        displayName = peer.displayName(),
                        isOnline = peer.isOnline,
                        score = fPeer,
                        detail = "f(me→node) ${fmt(fPeer)} · est. cost ${fmt(1.0 - fPeer)}",
                    )
                }.sortedByDescending { it.score }
                val best = beliefs.firstOrNull { it.score > 0.0 }
                RoutingExplanation(
                    dest = destNodeId,
                    strategy = "MAXPROP",
                    myScore = fDest,
                    myScoreLabel = "f(me → dest)",
                    beliefs = beliefs,
                    bestNextHop = best?.nodeId,
                    bestNextHopName = best?.displayName,
                    bestScore = best?.score ?: 0.0,
                    bestScoreLabel = "f",
                    formulas = listOf(
                        FormulaItem("Path likelihood", "f(i,j) = encounters(j) / Σ encounters"),
                        FormulaItem("Delivery cost", "cost(dest) = 1 − f(dest)  (lower = better)"),
                        FormulaItem("Transitivity", "f(i,k) ← peer f(j,k) · discount (if unknown)"),
                        FormulaItem("Recency decay", "count ← count · decay each cycle"),
                    ),
                    constantsLine = "discount=${fmt2(cfg.transitivityDiscount)} · " +
                        "recency decay=${fmt2(cfg.recencyDecayFactor)}",
                )
            }
            else -> null
        }
    }

    private fun fmt(v: Double): String = "%.3f".format(v)
    private fun fmt2(v: Double): String = "%.2f".format(v)

    /** Manually retry sending all buffered messages to online peers. */
    fun syncBuffer() {
        viewModelScope.launch {
            addLog("Manual sync — retrying buffered messages...")
            runCatching { orchestrator.triggerFlush() }
                .onFailure { Log.e(TAG, "triggerFlush failed", it) }
        }
    }

    /**
     * Force refresh online/offline status for all peers.
     * Runs an aggressive 3-second staleness check on the BLE transport — any peer whose
     * scan advertisements have stopped arriving in the last 3s gets marked offline
     * immediately. This catches the "peer's app is disconnected but their Bluetooth is
     * still on" scenario where cached scan records would otherwise keep them showing
     * green for up to 20 seconds.
     */
    fun refreshPeerStatus() {
        viewModelScope.launch {
            addLog("Refreshing peer status (3s aggressive check)...")
            val mgr = transport as? com.dtn.mesh.receiver.MultiTransportManager
            mgr?.forceStalenessCheck()
            // Also push any pending receipts / buffered messages while we're here.
            runCatching { orchestrator.triggerFlush() }
                .onFailure { Log.e(TAG, "triggerFlush failed on refresh", it) }
        }
    }

    /** Current PROPHET P-values for display in the Network tab. */
    fun getProbabilities(): Map<String, Double> {
        return try {
            val prophet = strategySelector.active as? com.dtn.mesh.routing.ProphetStrategy
            prophet?.getPTable() ?: emptyMap()
        } catch (_: Exception) { emptyMap() }
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

/** One candidate carrier's score toward a destination, strategy-agnostic for the UI. */
data class RoutingNodeBelief(
    val nodeId: String,
    val displayName: String,
    val isOnline: Boolean,
    /** Primary ranking score for this carrier (PROPHET: P_path; MaxProp: f(me,node)). */
    val score: Double,
    /** Pre-formatted secondary detail line explaining how [score] was derived. */
    val detail: String,
)

/** A named formula shown in the "Formulas & constants" breakdown. */
data class FormulaItem(val label: String, val formula: String)

/** Full explanation of how the active strategy scores routes toward one destination. */
data class RoutingExplanation(
    val dest: String,
    val strategy: String,
    /** My own score toward the destination (PROPHET P(me,dest); MaxProp f(me,dest)). */
    val myScore: Double,
    val myScoreLabel: String,
    val beliefs: List<RoutingNodeBelief>,
    val bestNextHop: String?,
    val bestNextHopName: String?,
    val bestScore: Double,
    val bestScoreLabel: String,
    val formulas: List<FormulaItem>,
    val constantsLine: String,
)

/** Buffered message info for the Network dashboard. */
data class BufferedMsgInfo(
    val msgId: String,
    val origin: String,
    val dest: String,
    val hopCount: Int,
    val forwardCount: Int,
    val ttlMin: Long,
    val bufferedFor: Long,  // seconds
    val status: String,
)
