package com.dtn.mesh.receiver

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.PatternMatcher
import android.util.Log
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.LocalNodeIdentity
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/*
 * LoRa-hub transport: the phone joins a hub's WiFi SoftAP (as a STA) and exchanges DTN bundles
 * with the hub over a framed TCP connection. The hub (custom ESP32 firmware) bridges to the
 * long-range LoRa backbone. This replaces the old Meshtastic AIDL path.
 *
 * ## Framed TCP protocol (port 9740)
 * Frame = `[1B type][2B length BE][payload...]`
 * - HELLO    (0x03): hub → phone, 4B hub node id. Announces the hub so we can treat it as a peer.
 * - REGISTER (0x01): phone → hub, 4B our node id. Lets the hub route bundles back to us.
 * - BUNDLE   (0x02): either direction, a self-contained DTN bundle ([DtnWireCodec] wire bytes).
 *
 * ## Auto-join
 * On API 29+ we request the hub network via [WifiNetworkSpecifier] (SSID prefix "DTN-HUB-") and
 * bind our socket to it, so the phone can attach without leaving the app and without losing its
 * cellular data default. On older APIs we assume the user has joined the hub WiFi manually and
 * connect on the active network.
 */
@Singleton
class LoRaHubTransportAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identity: LocalNodeIdentity,
) : DtnTransport {

    companion object {
        private const val TAG = "LoRaHubTransport"

        /** SoftAP SSID prefix every hub uses (e.g. "DTN-HUB-A"). */
        private const val HUB_SSID_PREFIX = "DTN-HUB-"
        /** Shared SoftAP passphrase (matches firmware AP_PASS). */
        private const val HUB_PASSPHRASE = "dtnmesh123"
        /** SoftAP gateway IP + DTN server port (matches firmware). */
        private const val HUB_IP = "192.168.4.1"
        private const val HUB_PORT = 9740

        private const val FRAME_REGISTER: Byte = 0x01
        private const val FRAME_BUNDLE: Byte = 0x02
        private const val FRAME_HELLO: Byte = 0x03

        private const val CONNECT_TIMEOUT_MS = 8_000

        private var packetIdCounter = 3_000_000
    }

    override val transportName: String = "LoRa Hub (WiFi)"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sendMutex = Mutex()

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var boundNetwork: Network? = null
    private var socket: Socket? = null
    private var output: DataOutputStream? = null

    /** The hub's node id, learned from its HELLO frame. Null until connected. */
    private var hubNodeId: NodeId? = null

    // ── Flows ────────────────────────────────────────────────────────────

    private val _inboundMessages = MutableSharedFlow<InboundPacket>(
        extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val inboundMessages: Flow<InboundPacket> = _inboundMessages.asSharedFlow()

    private val _nodeEvents = MutableSharedFlow<NodeEncounterEvent>(
        extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val nodeEvents: Flow<NodeEncounterEvent> = _nodeEvents.asSharedFlow()

    private val _deliveryStatus = MutableSharedFlow<DeliveryStatusEvent>(
        extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val deliveryStatus: Flow<DeliveryStatusEvent> = _deliveryStatus.asSharedFlow()

    private val _connectionState = MutableStateFlow(MeshConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<MeshConnectionState> = _connectionState.asStateFlow()

    override val isConnected: Boolean
        get() = _connectionState.value == MeshConnectionState.CONNECTED

    override val localNodeId: NodeId get() = identity.nodeId

    /** Whether the given peer is the currently-connected hub. */
    fun isHub(peerId: NodeId): Boolean = peerId == hubNodeId

    // ── Lifecycle ────────────────────────────────────────────────────────

    override suspend fun connect(): Boolean {
        if (isConnected) return true
        _connectionState.value = MeshConnectionState.CONNECTING

        try {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requestHubNetwork()
                true // async — CONNECTED is reported from the network callback
            } else {
                // Legacy: assume user joined the hub WiFi manually; connect on the active network.
                scope.launch { openSocketAndRun(network = null) }
                true
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "LoRa Hub permission missing: ${e.message}")
            _connectionState.value = MeshConnectionState.DISCONNECTED
            disconnect()
            return false
        } catch (e: Exception) {
            Log.e(TAG, "LoRa Hub startup failed: ${e.message}", e)
            _connectionState.value = MeshConnectionState.DISCONNECTED
            disconnect()
            return false
        }
    }

    override fun disconnect() {
        _connectionState.value = MeshConnectionState.DISCONNECTED
        runCatching { socket?.close() }
        socket = null
        output = null
        hubNodeId?.let { emitHubEncounter(it, online = false) }
        hubNodeId = null
        networkCallback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
        networkCallback = null
        boundNetwork = null
    }

    @SuppressLint("MissingPermission")
    private fun requestHubNetwork() {
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsidPattern(PatternMatcher(HUB_SSID_PREFIX, PatternMatcher.PATTERN_PREFIX))
            .setWpa2Passphrase(HUB_PASSPHRASE)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Hub WiFi available")
                boundNetwork = network
                scope.launch { openSocketAndRun(network) }
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "Hub WiFi lost")
                _connectionState.value = MeshConnectionState.DISCONNECTED
                runCatching { socket?.close() }
                socket = null; output = null
                hubNodeId?.let { emitHubEncounter(it, online = false) }
                hubNodeId = null
            }

            override fun onUnavailable() {
                Log.w(TAG, "Hub WiFi unavailable")
                _connectionState.value = MeshConnectionState.DISCONNECTED
            }
        }
        networkCallback = cb
        connectivityManager.requestNetwork(request, cb)
    }

    // ── Socket + read loop ───────────────────────────────────────────────

    private fun openSocketAndRun(network: Network?) {
        try {
            val sock = (network?.socketFactory?.createSocket() ?: Socket()).also { it.tcpNoDelay = true }
            sock.connect(InetSocketAddress(HUB_IP, HUB_PORT), CONNECT_TIMEOUT_MS)
            socket = sock
            output = DataOutputStream(sock.getOutputStream())

            // Announce ourselves so the hub can route bundles back to us.
            writeFrameBlocking(FRAME_REGISTER, int32(identity.nodeNum32))

            _connectionState.value = MeshConnectionState.CONNECTED
            Log.i(TAG, "Connected to hub at $HUB_IP:$HUB_PORT as ${identity.nodeId}")

            readLoop(DataInputStream(sock.getInputStream()))
        } catch (e: Exception) {
            Log.e(TAG, "Hub connect/read failed", e)
            _connectionState.value = MeshConnectionState.DISCONNECTED
            runCatching { socket?.close() }
            socket = null; output = null
        }
    }

    private fun readLoop(input: DataInputStream) {
        while (socket?.isClosed == false) {
            val type = input.readByte()
            val len = (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
            if (len < 0 || len > DtnMessage.LORA_MAX_FRAME_BYTES * 4) {
                Log.w(TAG, "Bad frame length $len, closing")
                break
            }
            val payload = ByteArray(len)
            input.readFully(payload)

            when (type) {
                FRAME_HELLO -> if (len >= 4) {
                    val hub = NodeId.fromNodeNum(readInt32(payload))
                    hubNodeId = hub
                    Log.i(TAG, "Hub HELLO: $hub")
                    emitHubEncounter(hub, online = true)
                }
                FRAME_BUNDLE -> {
                    val msg = DtnWireCodec.decode(payload, rssi = -40, snr = 15f) ?: continue
                    _inboundMessages.tryEmit(InboundPacket(message = msg, channel = msg.channel))
                }
                else -> Log.d(TAG, "Unknown frame type $type")
            }
        }
    }

    // ── Send ─────────────────────────────────────────────────────────────

    /**
     * Hand a bundle to the hub. [targetPeer] is expected to be the hub (see [isHub]); the hub
     * decides onward LoRa routing from the bundle's self-contained destination.
     *
     * Handing off over reliable TCP is treated as a successful delivery for local buffer
     * accounting (a hop-level ack, not an end-to-end one) — consistent with the WiFi Direct path.
     */
    override suspend fun sendMessage(message: DtnMessage, targetPeer: NodeId): Int? {
        if (!isConnected) return null
        return try {
            val wire = DtnWireCodec.encode(message)
            writeFrame(FRAME_BUNDLE, wire)
            val pktId = packetIdCounter++
            _deliveryStatus.tryEmit(DeliveryStatusEvent(meshPacketId = pktId, status = DeliveryOutcome.DELIVERED))
            Log.d(TAG, "Handed ${wire.size}B bundle to hub (id=$pktId)")
            pktId
        } catch (e: Exception) {
            Log.e(TAG, "Send to hub failed", e)
            null
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private suspend fun writeFrame(type: Byte, payload: ByteArray) = sendMutex.withLock {
        writeFrameBlocking(type, payload)
    }

    private fun writeFrameBlocking(type: Byte, payload: ByteArray) {
        val out = output ?: throw IllegalStateException("Not connected")
        out.writeByte(type.toInt())
        out.writeByte((payload.size ushr 8) and 0xFF)
        out.writeByte(payload.size and 0xFF)
        out.write(payload)
        out.flush()
    }

    private fun emitHubEncounter(hub: NodeId, online: Boolean) {
        _nodeEvents.tryEmit(NodeEncounterEvent(
            nodeId = hub,
            rssi = -40,
            snr = 15f,
            timestampMs = System.currentTimeMillis(),
            isOnline = online,
            longName = "LoRa Hub",
            shortName = "HUB",
        ))
    }

    private fun int32(v: Int): ByteArray =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun readInt32(b: ByteArray): Int =
        ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
}
