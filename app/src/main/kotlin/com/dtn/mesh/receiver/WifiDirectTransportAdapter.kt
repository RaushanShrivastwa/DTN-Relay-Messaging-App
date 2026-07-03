package com.dtn.mesh.receiver

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.NodeId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WiFi Direct (P2P) transport adapter for phone-to-phone DTN message exchange
 * without any LoRa hardware.
 *
 * Uses Android's WifiP2pManager API to:
 * - Discover nearby peers running the same app
 * - Form P2P groups (one device becomes group owner)
 * - Exchange DTN wire-format messages over TCP sockets
 *
 * This enables Phase 3 of the DTN architecture: direct delivery when two
 * phones meet physically but no LoRa hub is nearby.
 */
@Singleton
class WifiDirectTransportAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identity: com.dtn.mesh.model.LocalNodeIdentity,
) : DtnTransport {

    companion object {
        private const val TAG = "WifiDirectTransport"
        /** TCP port for DTN message exchange over WiFi Direct. */
        private const val DTN_P2P_PORT = 9734
        /** Service discovery type for finding other DTN Mesh Relay apps. */
        private const val SERVICE_TYPE = "_dtnmesh._tcp"
        /** Peer discovery interval (ms). */
        private const val DISCOVERY_INTERVAL_MS = 15_000L
        /** Packet ID counter for WiFi Direct sends (local, not Meshtastic). */
        private var packetIdCounter = 2_000_000
    }

    override val transportName: String = "WiFi Direct"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var isDiscovering = false
    private var serverSocket: ServerSocket? = null
    private var groupOwnerAddress: String? = null
    private var isGroupOwner = false

    /** Known peers discovered via WiFi Direct. Maps device address → NodeId. */
    private val discoveredPeers = mutableMapOf<String, WifiP2pDevice>()
    /** Connected peers we can send to. Maps NodeId string → peer IP address. */
    private val connectedPeers = mutableMapOf<String, String>()

    // ── Flows ────────────────────────────────────────────────────────────

    private val _inboundMessages = MutableSharedFlow<InboundPacket>(
        extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val inboundMessages: Flow<InboundPacket> = _inboundMessages.asSharedFlow()

    private val _nodeEvents = MutableSharedFlow<NodeEncounterEvent>(
        extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val nodeEvents: Flow<NodeEncounterEvent> = _nodeEvents.asSharedFlow()

    private val _deliveryStatus = MutableSharedFlow<DeliveryStatusEvent>(
        extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val deliveryStatus: Flow<DeliveryStatusEvent> = _deliveryStatus.asSharedFlow()

    private val _connectionState = MutableStateFlow(MeshConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<MeshConnectionState> = _connectionState.asStateFlow()

    // Single stable identity shared with the BLE and LoRa-hub transports.
    override val localNodeId: NodeId get() = identity.nodeId

    override val isConnected: Boolean
        get() = _connectionState.value == MeshConnectionState.CONNECTED

    /** Check if a specific peer is reachable via WiFi Direct. */
    fun isPeerReachable(peerId: NodeId): Boolean = connectedPeers.containsKey(peerId.value)

    // ── Lifecycle ────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    override suspend fun connect(): Boolean {
        Log.i(TAG, "Starting WiFi Direct discovery")
        _connectionState.value = MeshConnectionState.CONNECTING

        wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (wifiP2pManager == null) {
            Log.e(TAG, "WiFi P2P not supported on this device")
            _connectionState.value = MeshConnectionState.DISCONNECTED
            return false
        }

        channel = wifiP2pManager?.initialize(context, context.mainLooper, null)

        try {
            registerP2pReceivers()
            startPeerDiscovery()
            startServerSocket()
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "WiFi Direct permission missing: ${e.message}")
            _connectionState.value = MeshConnectionState.DISCONNECTED
            disconnect()
            return false
        } catch (e: Exception) {
            Log.e(TAG, "WiFi Direct startup failed: ${e.message}", e)
            _connectionState.value = MeshConnectionState.DISCONNECTED
            disconnect()
            return false
        }
    }

    override fun disconnect() {
        Log.i(TAG, "Stopping WiFi Direct")
        stopDiscovery()
        unregisterP2pReceivers()
        serverSocket?.close()
        serverSocket = null
        connectedPeers.clear()
        discoveredPeers.clear()
        _connectionState.value = MeshConnectionState.DISCONNECTED
    }

    @SuppressLint("MissingPermission")
    private fun startPeerDiscovery() {
        wifiP2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Peer discovery started")
                isDiscovering = true
            }
            override fun onFailure(reason: Int) {
                Log.e(TAG, "Peer discovery failed: reason=$reason")
                isDiscovering = false
            }
        })
    }

    private fun stopDiscovery() {
        if (isDiscovering) {
            wifiP2pManager?.stopPeerDiscovery(channel, null)
            isDiscovering = false
        }
    }

    // ── Server socket (receive side) ─────────────────────────────────────

    private fun startServerSocket() {
        scope.launch {
            try {
                serverSocket = ServerSocket(DTN_P2P_PORT)
                Log.i(TAG, "Server listening on port $DTN_P2P_PORT")

                while (serverSocket?.isClosed == false) {
                    val clientSocket = serverSocket?.accept() ?: break
                    scope.launch { handleIncomingConnection(clientSocket) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server socket error", e)
            }
        }
    }

    private fun handleIncomingConnection(socket: Socket) {
        try {
            val remoteIp = socket.inetAddress?.hostAddress
            val dis = DataInputStream(socket.getInputStream())
            // Protocol: [4 bytes wireLength][wireBytes][4 bytes senderIdLength][senderIdBytes]
            // A wireLength of 0 is a HELLO — identity exchange only, no DTN payload.
            val length = dis.readInt()
            if (length < 0 || length > DtnMessage.LORA_MAX_FRAME_BYTES * 10) {
                Log.w(TAG, "Bad packet length ($length), dropping")
                socket.close()
                return
            }
            val wireBytes = ByteArray(length)
            if (length > 0) dis.readFully(wireBytes)

            // Read sender's REAL node id (from LocalNodeIdentity on the other phone).
            val senderIdLength = dis.readInt()
            val senderId = String(ByteArray(senderIdLength).also { dis.readFully(it) })
            socket.close()

            val fromNode = NodeId(senderId)

            // Learn the real-id → IP mapping from the live connection, and reply with our own
            // HELLO the first time we see this peer so the mapping becomes bidirectional.
            val isNewPeer = !connectedPeers.containsKey(senderId)
            if (remoteIp != null) connectedPeers[senderId] = remoteIp
            if (isNewPeer && remoteIp != null) {
                _nodeEvents.tryEmit(NodeEncounterEvent(
                    nodeId = fromNode,
                    rssi = -30, snr = 20f,
                    timestampMs = System.currentTimeMillis(),
                    isOnline = true,
                ))
                scope.launch { sendHello(remoteIp) }
            }

            if (length > 0) {
                val msg = DtnWireCodec.decode(
                    wireBytes = wireBytes,
                    rssi = -30, // WiFi Direct — strong signal proxy
                    snr = 20f,
                ) ?: return
                _inboundMessages.tryEmit(InboundPacket(message = msg, channel = 0))
                Log.d(TAG, "Received ${wireBytes.size}B from $senderId via P2P")
            } else {
                Log.d(TAG, "HELLO from $senderId @ $remoteIp")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling P2P connection", e)
        }
    }

    /** Send an identity-only HELLO (zero-length DTN payload) to establish real-id ↔ IP mapping. */
    private fun sendHello(peerIp: String) {
        try {
            val myId = identity.nodeId.value.toByteArray()
            Socket().use { sock ->
                sock.connect(InetSocketAddress(peerIp, DTN_P2P_PORT), 5000)
                DataOutputStream(sock.getOutputStream()).apply {
                    writeInt(0)              // wireLength = 0 → HELLO
                    writeInt(myId.size)
                    write(myId)
                    flush()
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "HELLO to $peerIp failed: ${e.message}")
        }
    }

    // ── Send (client side) ───────────────────────────────────────────────

    override suspend fun sendMessage(message: DtnMessage, targetPeer: NodeId): Int? {
        val peerIp = connectedPeers[targetPeer.value] ?: return null

        return try {
            val wireBytes = DtnWireCodec.encode(message)
            val myId = identity.nodeId.value

            val socket = Socket()
            socket.connect(InetSocketAddress(peerIp, DTN_P2P_PORT), 5000)
            val dos = DataOutputStream(socket.getOutputStream())

            // Protocol: [4 bytes wireLength][wireBytes][4 bytes senderIdLength][senderIdBytes]
            dos.writeInt(wireBytes.size)
            dos.write(wireBytes)
            dos.writeInt(myId.toByteArray().size)
            dos.write(myId.toByteArray())
            dos.flush()
            socket.close()

            val pktId = packetIdCounter++
            // Assume delivered for WiFi Direct (direct TCP connection = reliable)
            _deliveryStatus.tryEmit(DeliveryStatusEvent(
                meshPacketId = pktId,
                status = DeliveryOutcome.DELIVERED,
            ))

            Log.d(TAG, "Sent ${wireBytes.size}B to ${targetPeer.value} at $peerIp")
            pktId
        } catch (e: Exception) {
            Log.e(TAG, "Send failed to ${targetPeer.value}", e)
            null
        }
    }

    // ── WiFi P2P BroadcastReceiver ───────────────────────────────────────

    private var receiversRegistered = false

    private fun registerP2pReceivers() {
        if (receiversRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        context.registerReceiver(p2pReceiver, filter)
        receiversRegistered = true
    }

    private fun unregisterP2pReceivers() {
        if (!receiversRegistered) return
        runCatching { context.unregisterReceiver(p2pReceiver) }
        receiversRegistered = false
    }

    @SuppressLint("MissingPermission")
    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        Log.w(TAG, "WiFi P2P is not enabled")
                        _connectionState.value = MeshConnectionState.DISCONNECTED
                    }
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    wifiP2pManager?.requestPeers(channel) { peers: WifiP2pDeviceList? ->
                        handlePeersChanged(peers)
                    }
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    wifiP2pManager?.requestConnectionInfo(channel) { info: WifiP2pInfo? ->
                        handleConnectionInfo(info)
                    }
                    wifiP2pManager?.requestGroupInfo(channel) { group: WifiP2pGroup? ->
                        handleGroupInfo(group)
                    }
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    // Our identity is fixed (LocalNodeIdentity); nothing to update here.
                }
            }
        }
    }

    // ── Peer/Connection handling ─────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun handlePeersChanged(peers: WifiP2pDeviceList?) {
        val peerList = peers?.deviceList ?: return
        discoveredPeers.clear()

        for (device in peerList) {
            discoveredPeers[device.deviceAddress] = device
            // NOTE: no encounter is emitted here — the WiFi P2P device name is NOT a real node id.
            // We only learn a peer's real id (and emit the encounter) after the HELLO handshake in
            // handleIncomingConnection. Discovery just triggers a connection attempt.
            if (device.status == WifiP2pDevice.AVAILABLE) {
                connectToPeer(device)
            }
        }
        Log.d(TAG, "Peers changed: ${peerList.size} devices found")
    }

    @SuppressLint("MissingPermission")
    private fun connectToPeer(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }
        wifiP2pManager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Connection initiated to ${device.deviceName}")
            }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "Connection failed to ${device.deviceName}: reason=$reason")
            }
        })
    }

    private fun handleConnectionInfo(info: WifiP2pInfo?) {
        info ?: return
        if (info.groupFormed) {
            isGroupOwner = info.isGroupOwner
            groupOwnerAddress = info.groupOwnerAddress?.hostAddress
            _connectionState.value = MeshConnectionState.CONNECTED
            Log.i(TAG, "P2P connected. GroupOwner=$isGroupOwner, ownerAddr=$groupOwnerAddress")
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleGroupInfo(group: WifiP2pGroup?) {
        group ?: return
        // Real node ids are never derived from the WiFi P2P device name. Instead we exchange a
        // HELLO over TCP to learn each peer's real LocalNodeIdentity and its IP.
        if (isGroupOwner) {
            // The group owner doesn't know client IPs until they connect. Clients initiate HELLO
            // to the owner (below), which teaches us their real id → IP in handleIncomingConnection.
            for (client in group.clientList) {
                discoveredPeers[client.deviceAddress] = client
            }
        } else {
            // We're a client — the group owner's IP is reachable now, so greet it. The owner's
            // HELLO reply (and our own) establish the bidirectional real-id ↔ IP mapping.
            groupOwnerAddress?.let { ip -> scope.launch { sendHello(ip) } }
        }
    }
}
