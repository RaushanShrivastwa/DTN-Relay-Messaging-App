package com.dtn.mesh.receiver

import android.util.Log
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.NodeId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Aggregates every [DtnTransport] into one unified [MeshTransport] for the [DtnOrchestrator].
 *
 * Transports (independent of Meshtastic):
 * - [BleTransportAdapter]: phone-to-phone, primary P2P (coexists with a busy WiFi STA).
 * - [WifiDirectTransportAdapter]: phone-to-phone, higher-throughput fallback when no hub is attached.
 * - [LoRaHubTransportAdapter]: phone ⇄ ESP32 hub over WiFi; the hub bridges the LoRa backbone.
 *
 * - Merges inbound messages, node events, and delivery status from all transports.
 * - Send priority for a peer: BLE → WiFi Direct → LoRa hub.
 * - Connection state: CONNECTED if ANY transport is connected.
 */
@Singleton
class MultiTransportManager @Inject constructor(
    private val bleTransport: BleTransportAdapter,
    private val wifiDirectTransport: WifiDirectTransportAdapter,
    private val loRaHubTransport: LoRaHubTransportAdapter,
) : MeshTransport {

    companion object {
        private const val TAG = "MultiTransport"
    }

    private val transports: List<DtnTransport> =
        listOf(bleTransport, wifiDirectTransport, loRaHubTransport)

    // ── Merged Flows ─────────────────────────────────────────────────────

    override val inboundMessages: Flow<InboundPacket> =
        transports.map { it.inboundMessages }.merge()

    override val nodeEvents: Flow<NodeEncounterEvent> =
        transports.map { it.nodeEvents }.merge()

    override val deliveryStatus: Flow<DeliveryStatusEvent> =
        transports.map { it.deliveryStatus }.merge()

    private val _connectionState = MutableStateFlow(MeshConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<MeshConnectionState> = _connectionState.asStateFlow()

    override val isConnected: Boolean
        get() = transports.any { it.isConnected }

    override val localNodeId: NodeId?
        get() = transports.firstOrNull { it.isConnected }?.localNodeId
            ?: bleTransport.localNodeId

    // ── Lifecycle ────────────────────────────────────────────────────────

    override suspend fun connect(): Boolean {
        var anySuccess = false
        for (t in transports) {
            val ok = t.connect()
            if (ok) {
                Log.i(TAG, "${t.transportName} connected")
                anySuccess = true
            }
        }
        updateConnectionState()
        return anySuccess
    }

    override fun disconnect() {
        transports.forEach { it.disconnect() }
        _connectionState.value = MeshConnectionState.DISCONNECTED
    }

    /** Connect only the LoRa hub transport (join hub SoftAP). */
    suspend fun connectHub(): Boolean {
        val ok = loRaHubTransport.connect()
        updateConnectionState()
        return ok
    }

    /** Connect only the BLE P2P transport. */
    suspend fun connectBle(): Boolean {
        val ok = bleTransport.connect()
        updateConnectionState()
        return ok
    }

    /** Connect only the WiFi Direct transport (start discovery). */
    suspend fun connectWifiDirect(): Boolean {
        val ok = wifiDirectTransport.connect()
        updateConnectionState()
        return ok
    }

    /** Disconnect WiFi Direct only. */
    fun disconnectWifiDirect() {
        wifiDirectTransport.disconnect()
        updateConnectionState()
    }

    // ── Send ─────────────────────────────────────────────────────────────

    /**
     * Send via the best available transport for the target peer.
     * Priority: BLE (if a live BLE neighbor) > WiFi Direct (if a P2P neighbor) > LoRa hub.
     */
    override suspend fun sendMessage(message: DtnMessage, targetPeer: NodeId): Int? {
        // 1. BLE — primary P2P, no airtime cost, coexists with hub WiFi.
        if (bleTransport.isConnected && bleTransport.isPeerReachable(targetPeer)) {
            bleTransport.sendMessage(message, targetPeer)?.let {
                Log.d(TAG, "Sent via BLE to ${targetPeer.value}"); return it
            }
        }

        // 2. WiFi Direct — higher throughput when the peer is a P2P neighbor.
        if (wifiDirectTransport.isConnected && wifiDirectTransport.isPeerReachable(targetPeer)) {
            wifiDirectTransport.sendMessage(message, targetPeer)?.let {
                Log.d(TAG, "Sent via WiFi Direct to ${targetPeer.value}"); return it
            }
        }

        // 3. LoRa hub — long-haul backbone (peer is the hub itself).
        if (loRaHubTransport.isConnected && loRaHubTransport.isHub(targetPeer)) {
            loRaHubTransport.sendMessage(message, targetPeer)?.let {
                Log.d(TAG, "Handed to LoRa hub for ${targetPeer.value}"); return it
            }
        }

        Log.w(TAG, "No transport available for ${targetPeer.value}")
        return null
    }

    private fun updateConnectionState() {
        _connectionState.value = when {
            transports.any { it.connectionState.value == MeshConnectionState.CONNECTED } ->
                MeshConnectionState.CONNECTED
            transports.any { it.connectionState.value == MeshConnectionState.CONNECTING } ->
                MeshConnectionState.CONNECTING
            else -> MeshConnectionState.DISCONNECTED
        }
    }
}
