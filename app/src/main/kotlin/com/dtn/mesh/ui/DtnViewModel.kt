package com.dtn.mesh.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "DtnViewModel"
    }

    val connectionState: StateFlow<MeshConnectionState> = transport.connectionState

    private val _localNodeId = MutableStateFlow<String?>(null)
    val localNodeId: StateFlow<String?> = _localNodeId.asStateFlow()

    private val _logEntries = MutableStateFlow<List<String>>(emptyList())
    val logEntries: StateFlow<List<String>> = _logEntries.asStateFlow()

    /** Reactive buffered count straight from Room — always accurate. */
    val bufferedCount: StateFlow<Int> = queueManager.observeBufferedCount()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, 0)

    private val _exportPath = MutableStateFlow<String?>(null)
    val exportPath: StateFlow<String?> = _exportPath.asStateFlow()

    private val _activeStrategy = MutableStateFlow("PROPHET")
    val activeStrategy: StateFlow<String> = _activeStrategy.asStateFlow()

    /** Discovered peers with their online state (persistent — offline peers stay in the list). */
    private val _peerList = MutableStateFlow<List<PeerInfo>>(emptyList())
    val peerList: StateFlow<List<PeerInfo>> = _peerList.asStateFlow()

    /** Backward compatibility — just the IDs (used by any code still importing this). */
    val discoveredPeers: StateFlow<List<String>> = _peerList
        .let { flow ->
            kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList()).also { out ->
                viewModelScope.launch { flow.collect { list -> out.value = list.map { it.nodeId } } }
            }
        }.asStateFlow()

    /** Currently selected destination. Null = broadcast to all. */
    private val _selectedDestination = MutableStateFlow<String?>(null)
    val selectedDestination: StateFlow<String?> = _selectedDestination.asStateFlow()

    /** Chat messages (sent + received) for the messages tab. */
    private val _receivedMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val receivedMessages: StateFlow<List<ChatMessage>> = _receivedMessages.asStateFlow()

    /** Message ids already displayed as inbound — dedup guard against multi-path/re-flood. */
    private val seenInboundIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    init {
        _localNodeId.value = transport.localNodeId?.value
        // Collect events for live log
        viewModelScope.launch {
            transport.nodeEvents.collect { event ->
                addLog("NODE: ${event.nodeId.value} online=${event.isOnline} rssi=${event.rssi}")
                _localNodeId.value = transport.localNodeId?.value
                // Update peer list: keep offline peers visible so users can still target them.
                // The message will be buffered until that peer comes back in range.
                val current = _peerList.value.toMutableList()
                val idx = current.indexOfFirst { it.nodeId == event.nodeId.value }
                if (idx >= 0) {
                    current[idx] = current[idx].copy(isOnline = event.isOnline, lastSeenMs = System.currentTimeMillis())
                } else {
                    current.add(PeerInfo(
                        nodeId = event.nodeId.value,
                        isOnline = event.isOnline,
                        lastSeenMs = System.currentTimeMillis(),
                    ))
                }
                _peerList.value = current
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
                // Mule-relay traffic (destined for someone else) is silently carried.
                val myId = transport.localNodeId?.value
                val isForMe = myId != null && msg.destinationNodeId.value == myId
                val isBroadcast = msg.destinationNodeId == NodeId.BROADCAST
                if (!isForMe && !isBroadcast) return@collect

                val text = String(msg.payloadBytes, Charsets.UTF_8)
                val from = msg.originNodeId.value
                addLog("RX from ${from.takeLast(8)}: \"$text\"")
                _receivedMessages.value = _receivedMessages.value + ChatMessage(
                    text = text,
                    from = from,
                    isOutgoing = false,
                    status = "✓ received",
                    time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date()),
                )
            }
        }
        viewModelScope.launch {
            transport.deliveryStatus.collect { event ->
                addLog("ACK: pktId=${event.meshPacketId} status=${event.status}")
                // Buffer count is now reactive from Room — no manual update needed
                if (event.status == com.dtn.mesh.receiver.DeliveryOutcome.DELIVERED) {
                    markOutgoingDelivered()
                }
            }
        }
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

    fun selectDestination(nodeId: String?) {
        _selectedDestination.value = nodeId
        addLog("Destination: ${nodeId ?: "BROADCAST (all)"}")
    }

    fun sendTestMessage(text: String) {
        viewModelScope.launch {
            val dest = _selectedDestination.value?.let { NodeId(it) } ?: NodeId.BROADCAST
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
                val target = if (dest == NodeId.BROADCAST) "ALL" else dest.value.takeLast(8)
                addLog("QUEUED: \"$text\" → $target (${msg.id.take(8)})")
                // Add to chat as outgoing
                _receivedMessages.value = _receivedMessages.value + ChatMessage(
                    text = text,
                    from = "me",
                    isOutgoing = true,
                    status = "buffered",
                    time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date()),
                )
                // Trigger immediate forwarding to any online peers
                orchestrator.triggerFlush()
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
            orchestrator.triggerFlush()
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

    /** Update the most recent buffered outgoing chat message to delivered status. */
    private fun markOutgoingDelivered() {
        val list = _receivedMessages.value.toMutableList()
        val idx = list.indexOfLast { it.isOutgoing && it.status == "buffered" }
        if (idx >= 0) {
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

/** A single chat message displayed in the Messages tab. */
data class ChatMessage(
    val text: String,
    val from: String,
    val isOutgoing: Boolean,
    val status: String,
    val time: String,
)

/** A peer we've seen (may be online or offline). */
data class PeerInfo(
    val nodeId: String,
    val isOnline: Boolean,
    val lastSeenMs: Long,
)
