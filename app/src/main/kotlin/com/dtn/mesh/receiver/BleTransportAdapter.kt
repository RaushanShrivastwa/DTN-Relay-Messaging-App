// File-level suppression — kapt has a long-standing bug where per-method @SuppressLint
// occasionally emits an empty @SuppressLint() in the generated Java stub, which then fails
// the Java compile with "annotation is missing a default value for the element 'value'".
// A single @file:SuppressLint sidesteps the bug and covers every Bluetooth call in this file.
@file:SuppressLint("MissingPermission")

package com.dtn.mesh.receiver

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.LocalNodeIdentity
import com.dtn.mesh.model.NodeId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/*
 * BLE transport for encounter-based, phone-to-phone DTN exchange. This is the **primary** P2P
 * path when a phone is attached to a hub SoftAP (WiFi Direct conflicts with a busy WiFi STA;
 * BLE coexists cleanly).
 *
 * Each phone is symmetric:
 * - Runs a **GATT server** exposing a DTN service with one bundle characteristic (write).
 * - **Advertises** the service UUID plus its 4-byte node id as service data, so scanners learn
 *   who is nearby without connecting.
 * - **Scans** for the service UUID; on discovery emits an encounter and, when it has bundles to
 *   send, connects as a **GATT client** and writes them.
 *
 * ## Bundle transfer framing (over the characteristic)
 * A bundle is sent as `[2B totalLen BE][wireBytes...]`, split into MTU-sized chunks written in
 * order. The receiver accumulates per-device until it has `totalLen` bytes, then decodes.
 *
 * NOTE: BLE is timing- and device-sensitive; this implementation is structurally complete but
 * must be validated on real hardware (MTU negotiation, chunk pacing, reconnection).
 */
@Singleton
class BleTransportAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identity: LocalNodeIdentity,
) : DtnTransport {

    companion object {
        private const val TAG = "BleTransport"

        val SERVICE_UUID: UUID = UUID.fromString("d791b000-2c8f-4f2a-9b1e-000000000001")
        val BUNDLE_CHAR_UUID: UUID = UUID.fromString("d791b001-2c8f-4f2a-9b1e-000000000001")
        private val SERVICE_PARCEL = ParcelUuid(SERVICE_UUID)

        private const val DEFAULT_CHUNK = 20 // pre-MTU-negotiation safe size (23 - 3 ATT overhead)
        private const val ENCOUNTER_COOLDOWN_MS = 8_000L // min gap between encounter emits per peer
        /**
         * If we haven't seen ANY signal from a peer (scan hit, GATT connect, inbound write,
         * outbound write) for this long, declare it offline. 20s balances responsiveness
         * against false-positives on devices where the OS throttles BLE scan duty cycle.
         */
        private const val PEER_STALE_THRESHOLD_MS = 20_000L
        /** How often the staleness sweeper wakes up. */
        private const val SWEEPER_TICK_MS = 4_000L
        private var packetIdCounter = 4_000_000
    }

    override val transportName: String = "BLE"

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter get() = bluetoothManager?.adapter

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null

    /** Address → GATT client connection (we are central). */
    private val clientGatts = ConcurrentHashMap<String, BluetoothGatt>()
    /** NodeId → device, learned from advertisements. */
    private val deviceForNode = ConcurrentHashMap<String, BluetoothDevice>()
    /** Per-device reassembly buffers for inbound writes (we are peripheral). */
    private val rxBuffers = ConcurrentHashMap<String, ByteArrayOutputStream>()
    /** Address → negotiated chunk size (central role). */
    private val chunkSize = ConcurrentHashMap<String, Int>()
    /** Address → true once services are discovered and ready to write. */
    private val readyDevices = ConcurrentHashMap<String, Boolean>()
    /** Address → deferred completed when connection+discovery finishes (or fails). */
    private val connectWaiters = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Boolean>>()

    /**
     * NodeId → timestamp of most recent scan callback for that peer. Updated on every scan
     * hit (not rate-limited like [lastEncounterEmit]) so the staleness sweeper can distinguish
     * "still advertising" from "went out of range".
     */
    private val peerLastSeenMs = ConcurrentHashMap<String, Long>()

    /** Scope for background jobs (staleness sweep). Recreated on each [connect]. */
    private var adapterScope: CoroutineScope? = null
    private var sweeperJob: Job? = null

    // ── Flows ────────────────────────────────────────────────────────────

    private val _inboundMessages = MutableSharedFlow<InboundPacket>(
        extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val inboundMessages: Flow<InboundPacket> = _inboundMessages.asSharedFlow()

    private val _nodeEvents = MutableSharedFlow<NodeEncounterEvent>(
        extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val nodeEvents: Flow<NodeEncounterEvent> = _nodeEvents.asSharedFlow()

    private val _deliveryStatus = MutableSharedFlow<DeliveryStatusEvent>(
        extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val deliveryStatus: Flow<DeliveryStatusEvent> = _deliveryStatus.asSharedFlow()

    private val _connectionState = MutableStateFlow(MeshConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<MeshConnectionState> = _connectionState.asStateFlow()

    override val isConnected: Boolean
        get() = _connectionState.value == MeshConnectionState.CONNECTED

    override val localNodeId: NodeId get() = identity.nodeId

    /** Whether a peer is currently reachable via BLE (seen in recent scans). */
    fun isPeerReachable(peerId: NodeId): Boolean {
        return deviceForNode.containsKey(peerId.value)
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    override suspend fun connect(): Boolean {
        val a = adapter ?: run { Log.e(TAG, "No Bluetooth adapter"); return false }
        if (!a.isEnabled) { Log.w(TAG, "Bluetooth is off"); return false }
        _connectionState.value = MeshConnectionState.CONNECTING

        try {
            startGattServer()
            startAdvertising()
            startScanning()
            startStalenessSweeper()
            _connectionState.value = MeshConnectionState.CONNECTED
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "BLE permission missing: ${e.message}")
            _connectionState.value = MeshConnectionState.DISCONNECTED
            disconnect()
            return false
        } catch (e: Exception) {
            Log.e(TAG, "BLE startup failed: ${e.message}", e)
            _connectionState.value = MeshConnectionState.DISCONNECTED
            disconnect()
            return false
        }
    }

    override fun disconnect() {
        runCatching { sweeperJob?.cancel() }; sweeperJob = null
        runCatching { adapterScope?.cancel() }; adapterScope = null
        runCatching { scanner?.stopScan(scanCallback) }
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        clientGatts.values.forEach { runCatching { it.close() } }
        clientGatts.clear()
        runCatching { gattServer?.close() }
        gattServer = null
        deviceForNode.clear(); rxBuffers.clear(); chunkSize.clear()
        readyDevices.clear(); lastEncounterEmit.clear(); peerLastSeenMs.clear()
        connectWaiters.values.forEach { it.complete(false) }; connectWaiters.clear()
        chunkAckWaiters.values.forEach { it.complete(false) }; chunkAckWaiters.clear()
        _connectionState.value = MeshConnectionState.DISCONNECTED
    }

    /**
     * Periodically declares peers we haven't seen recently as offline.
     *
     * BLE `onConnectionStateChange(DISCONNECTED)` only fires when there is an active GATT
     * session; the common case (peer walks out of scan range without a GATT connection) never
     * triggers it, leaving the remote node stuck showing "online" on other phones. This sweep
     * emits an isOnline=false encounter after [PEER_STALE_THRESHOLD_MS] of scan silence.
     */
    private fun startStalenessSweeper() {
        val sc = CoroutineScope(Dispatchers.IO + SupervisorJob())
        adapterScope = sc
        sweeperJob = sc.launch {
            while (isActive) {
                delay(SWEEPER_TICK_MS)
                runStalenessCheck(PEER_STALE_THRESHOLD_MS)
            }
        }
    }

    /**
     * Immediately check every tracked peer against a caller-supplied threshold and emit
     * offline events for stale ones. Called by the Refresh button in the UI with an
     * aggressive short threshold (~3 s) — since we scan with SCAN_MODE_LOW_LATENCY every
     * in-range peer emits advertisements every ~100–500 ms, so 3 seconds of scan silence
     * reliably means the peer stopped advertising (their app hit Disconnect, even if the
     * OS BLE radio is still on).
     */
    fun forceStalenessCheck(thresholdMs: Long = 3_000L) {
        runStalenessCheck(thresholdMs)
    }

    /** Shared body of the sweeper and the manual refresh, threshold-parameterised. */
    private fun runStalenessCheck(thresholdMs: Long) {
        val now = System.currentTimeMillis()
        val stale = peerLastSeenMs.entries.filter { now - it.value > thresholdMs }
        for ((peerIdStr, _) in stale) {
            // Capture the BluetoothDevice before we drop the mapping so we can tear down
            // its GATT session by MAC address. Without this, a cached GATT connection to
            // a peer whose app has hit Disconnect can silently accept writes at the LL
            // layer while the DTN service is gone — the "fake ACK" scenario.
            val staleDevice = deviceForNode.remove(peerIdStr)
            peerLastSeenMs.remove(peerIdStr)
            lastEncounterEmit.remove(peerIdStr)
            staleDevice?.address?.let { addr ->
                connectWaiters.remove(addr)?.complete(false)
                chunkAckWaiters.remove(addr)?.complete(false)
                clientGatts.remove(addr)?.let { runCatching { it.close() } }
                readyDevices.remove(addr)
                chunkSize.remove(addr)
                rxBuffers.remove(addr)
            }
            _nodeEvents.tryEmit(NodeEncounterEvent(
                nodeId = NodeId(peerIdStr),
                rssi = 0,
                snr = 0f,
                timestampMs = now,
                isOnline = false,
            ))
            Log.d(TAG, "Peer stale (threshold=${thresholdMs}ms): ${peerIdStr.takeLast(8)}")
        }
    }

    // ── GATT server (peripheral / receive side) ──────────────────────────

    private fun startGattServer() {
        val server = bluetoothManager?.openGattServer(context, gattServerCallback) ?: return
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            BUNDLE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        service.addCharacteristic(characteristic)
        server.addService(service)
        gattServer = server
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (characteristic.uuid == BUNDLE_CHAR_UUID) {
                accumulateInbound(device, value)
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }
    }

    /** Append inbound bytes for a device and extract complete bundles. */
    private fun accumulateInbound(device: BluetoothDevice, value: ByteArray) {
        val address = device.address
        val buf = rxBuffers.getOrPut(address) { ByteArrayOutputStream() }
        buf.write(value)
        val bytes = buf.toByteArray()
        if (bytes.size < 2) return
        val total = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        if (bytes.size - 2 < total) return // more chunks pending

        val wire = bytes.copyOfRange(2, 2 + total)
        // Reset buffer, keeping any trailing bytes belonging to the next transfer.
        buf.reset()
        if (bytes.size > 2 + total) buf.write(bytes, 2 + total, bytes.size - (2 + total))

        val msg = DtnWireCodec.decode(wire, rssi = -60, snr = 10f) ?: return
        // A successful decode means the peer is definitely reachable — refresh the origin's
        // last-seen so the sweeper doesn't misfire during an active exchange.
        peerLastSeenMs[msg.originNodeId.value] = System.currentTimeMillis()

        // Identify the IMMEDIATE neighbour that wrote to us (the previous hop), NOT the wire
        // header's origin (which for a relayed bundle is some far-away node).
        //
        // Handshake bundles (ROUTING_SUMMARY / ROUTING_ACK) are always sent by the directly
        // connected peer, so their originNodeId IS that neighbour. We use that to LEARN the
        // device-address → node-id mapping here — critical on radios (e.g. Qualcomm/OnePlus)
        // that suspend active BLE scanning while a GATT link is up: on those phones we may
        // never receive the peer's scan-response service data, so the scan path alone would
        // leave the peer stuck "offline" even while we're actively exchanging data. Learning
        // the mapping from the handshake, plus emitting an online encounter for any inbound
        // write, guarantees the peer is recognised purely from received traffic.
        val neighbour: NodeId? = if (msg.messageType.isHandshake) msg.originNodeId else nodeFor(address)
        if (neighbour != null && neighbour != identity.nodeId) {
            deviceForNode[neighbour.value] = device
            // A live GATT write is a strong reachability signal; -60 dBm is a reasonable
            // "connected neighbour" estimate that the scan path will refine if it ever fires.
            emitOnlineEncounter(neighbour, rssi = -60)
        }

        _inboundMessages.tryEmit(InboundPacket(message = msg, channel = msg.channel, viaPeer = neighbour))
    }

    // ── Advertising ──────────────────────────────────────────────────────

    private fun startAdvertising() {
        val adv = adapter?.bluetoothLeAdvertiser ?: run { Log.w(TAG, "Advertising unsupported"); return }
        advertiser = adv
        // LOW_LATENCY advertises roughly every 100 ms (vs BALANCED's 250 ms and LOW_POWER's
        // 1 s). Combined with LOW_LATENCY scanning on the peer side this gives near-immediate
        // detection and eliminates the "peer flickers offline while still in range" pattern
        // we saw with BALANCED. Battery cost is real but acceptable while the DTN foreground
        // service is running.
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        // Publish our node id (4 bytes) as service data so scanners identify us without connecting.
        // We split the advertisement to fit within the 31-byte legacy limit:
        // Service UUID in the advertisement, Service Data in the scan response.
        val data = AdvertiseData.Builder()
            .addServiceUuid(SERVICE_PARCEL)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .addServiceData(SERVICE_PARCEL, int32(identity.nodeNum32))
            .build()
        adv.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) { Log.e(TAG, "Advertise failed: $errorCode") }
    }

    // ── Scanning (central / discover side) ───────────────────────────────

    private fun startScanning() {
        val sc = adapter?.bluetoothLeScanner ?: run { Log.w(TAG, "Scanning unsupported"); return }
        scanner = sc
        val filters = listOf(ScanFilter.Builder().setServiceUuid(SERVICE_PARCEL).build())
        // LOW_LATENCY scans nearly continuously. BALANCED had multi-second gaps between
        // scan windows, which at edge-of-range meant whole advertisement bursts got missed
        // and the 25 s staleness threshold tripped. LOW_LATENCY reliably picks up an
        // advertising peer within a few hundred ms; combined with LOW_LATENCY advertising
        // on the other side, the offline flicker for in-range peers goes away.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        sc.startScan(filters, settings, scanCallback)
    }

    /** Address → last time we emitted an encounter for this peer (debounce). */
    private val lastEncounterEmit = ConcurrentHashMap<String, Long>()

    /**
     * Mark a peer online from ANY liveness signal — a scan hit OR an inbound GATT write.
     * [peerLastSeenMs] is always refreshed (it drives the staleness sweeper), but the
     * [NodeEncounterEvent] is rate-limited to [ENCOUNTER_COOLDOWN_MS] per peer so we don't
     * flood the orchestrator during an active transfer.
     */
    private fun emitOnlineEncounter(peer: NodeId, rssi: Int) {
        if (peer == identity.nodeId) return
        val now = System.currentTimeMillis()
        peerLastSeenMs[peer.value] = now
        val last = lastEncounterEmit[peer.value] ?: 0L
        if (now - last < ENCOUNTER_COOLDOWN_MS) return
        lastEncounterEmit[peer.value] = now
        _nodeEvents.tryEmit(NodeEncounterEvent(
            nodeId = peer,
            rssi = rssi,
            snr = 0f,
            timestampMs = now,
            isOnline = true,
            shortName = "BLE",
        ))
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val serviceData = result.scanRecord?.getServiceData(SERVICE_PARCEL) ?: return
            if (serviceData.size < 4) return
            val peer = NodeId.fromNodeNum(readInt32(serviceData))
            if (peer == identity.nodeId) return // ignore our own advertisement

            deviceForNode[peer.value] = result.device
            emitOnlineEncounter(peer, result.rssi)
        }

        override fun onScanFailed(errorCode: Int) { Log.e(TAG, "Scan failed: $errorCode") }
    }

    // ── GATT client (send side) ──────────────────────────────────────────

    override suspend fun sendMessage(message: DtnMessage, targetPeer: NodeId): Int? {
        // Global guard: any unexpected exception in the send path returns null instead of
        // crashing the orchestrator's consumer coroutine (which would kill all forwarding).
        return try {
            sendMessageInternal(message, targetPeer)
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage crashed for ${targetPeer.value}", e)
            null
        }
    }

    private suspend fun sendMessageInternal(message: DtnMessage, targetPeer: NodeId): Int? {
        val device = deviceForNode[targetPeer.value] ?: run {
            Log.w(TAG, "Peer ${targetPeer.value} not in scan range")
            return null
        }

        // 1. Encode BEFORE we open a GATT connection, so encode failures (oversized payload,
        //    invalid UUID, etc.) don't waste a connection attempt.
        val wire = try {
            DtnWireCodec.encode(message)
        } catch (e: Exception) {
            Log.e(TAG, "Encode failed for ${message.id}: ${e.message}")
            return null
        }

        // 2. Ensure connected AND services discovered.
        if (!ensureReady(device)) {
            Log.w(TAG, "Could not ready GATT for ${targetPeer.value}")
            return null
        }

        val gatt = clientGatts[device.address] ?: return null
        val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(BUNDLE_CHAR_UUID) ?: run {
            Log.w(TAG, "DTN characteristic not found on ${targetPeer.value}")
            return null
        }

        // 3. Write the framed bundle in MTU-sized chunks.
        val framed = int16(wire.size) + wire
        val chunk = chunkSize[device.address] ?: DEFAULT_CHUNK

        var offset = 0
        while (offset < framed.size) {
            val end = minOf(offset + chunk, framed.size)
            val slice = framed.copyOfRange(offset, end)
            val ok = writeChunkAndWait(gatt, characteristic, slice)
            if (!ok) {
                Log.w(TAG, "Chunk write failed to ${targetPeer.value} — tearing down stale GATT")
                runCatching { clientGatts.remove(device.address)?.close() }
                readyDevices.remove(device.address)
                return null
            }
            offset = end
        }

        val pktId = packetIdCounter++
        // A successful send is the strongest possible liveness signal — mid-transfer we may
        // not even receive scan advertisements from the peer (the link is busy carrying our
        // data), so without this refresh the sweeper could false-flag an active peer offline.
        peerLastSeenMs[targetPeer.value] = System.currentTimeMillis()
        Log.d(TAG, "BLE sent ${framed.size}B to ${targetPeer.value} (pkt=$pktId)")
        _deliveryStatus.tryEmit(DeliveryStatusEvent(meshPacketId = pktId, status = DeliveryOutcome.DELIVERED))
        return pktId
    }

    /** Connect (if needed) and wait until services are discovered. */
    private suspend fun ensureReady(device: BluetoothDevice): Boolean {
        // Fast path: already connected and ready
        if (readyDevices[device.address] == true && clientGatts.containsKey(device.address)) return true

        // Stale connection (connected but discovery never completed) — tear it down for a clean retry
        if (clientGatts.containsKey(device.address) && readyDevices[device.address] != true) {
            Log.d(TAG, "Stale GATT to ${device.address}, closing for clean reconnect")
            runCatching { clientGatts.remove(device.address)?.close() }
            readyDevices.remove(device.address)
        }

        val waiter = kotlinx.coroutines.CompletableDeferred<Boolean>()
        connectWaiters[device.address] = waiter

        Log.d(TAG, "Connecting GATT to ${device.address}")
        device.connectGatt(context, false, gattClientCallback)

        return kotlinx.coroutines.withTimeoutOrNull(8000) { waiter.await() } ?: run {
            Log.w(TAG, "GATT ready timeout for ${device.address}")
            connectWaiters.remove(device.address)
            // Clean up the half-open connection so the next attempt starts fresh
            runCatching { clientGatts.remove(device.address)?.close() }
            readyDevices.remove(device.address)
            false
        }
    }

    /**
     * Per-device deferreds that resolve when the peer's ATT layer acknowledges a write.
     * Populated before `writeCharacteristic` returns, completed inside
     * [gattClientCallback.onCharacteristicWrite].
     */
    private val chunkAckWaiters =
        ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Boolean>>()

    /**
     * Write one chunk with WRITE_TYPE_DEFAULT (write-with-response) and wait for the
     * peer's ATT ACK. This is the only way to know whether the peer actually received
     * the bytes — with WRITE_TYPE_NO_RESPONSE Android reports success as soon as the
     * write is queued to the radio, which fooled the delivery layer into marking
     * messages as delivered even when the receiver's DTN GATT server was gone (app
     * disconnected but Bluetooth still on).
     *
     * Timeout is generous but bounded (2 s) so we don't hang forever on an unresponsive
     * peer.
     */
    @Suppress("DEPRECATION")
    private suspend fun writeChunkAndWait(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray,
    ): Boolean {
        val addr = gatt.device.address
        // Any previous waiter for this device is stale — release it as failed.
        chunkAckWaiters.remove(addr)?.complete(false)

        val waiter = kotlinx.coroutines.CompletableDeferred<Boolean>()
        chunkAckWaiters[addr] = waiter

        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = data
        val queued = gatt.writeCharacteristic(characteristic)
        if (!queued) {
            chunkAckWaiters.remove(addr)
            return false
        }
        // Real end-to-end confirmation: we ONLY report success if the peer ATT-acked.
        val acked = kotlinx.coroutines.withTimeoutOrNull(2_000L) { waiter.await() } ?: false
        chunkAckWaiters.remove(addr)
        return acked
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                clientGatts[gatt.device.address] = gatt
                // Successful GATT connection is a strong liveness signal — refresh so the
                // sweeper doesn't declare this peer offline while we're mid-transfer.
                nodeFor(gatt.device.address)?.let { peerLastSeenMs[it.value] = System.currentTimeMillis() }
                gatt.requestMtu(247)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                clientGatts.remove(gatt.device.address)
                readyDevices.remove(gatt.device.address)
                runCatching { gatt.close() }
                // Fail any in-flight waiters for this device — connect handshake and
                // pending chunk ACKs both need to unblock so the send path returns null.
                connectWaiters.remove(gatt.device.address)?.complete(false)
                chunkAckWaiters.remove(gatt.device.address)?.complete(false)
                // Emit offline event so orchestrator + UI know this peer is gone
                val peer = nodeFor(gatt.device.address)
                if (peer != null) {
                    _nodeEvents.tryEmit(NodeEncounterEvent(
                        nodeId = peer,
                        rssi = 0,
                        snr = 0f,
                        timestampMs = System.currentTimeMillis(),
                        isOnline = false,
                    ))
                    Log.d(TAG, "Peer disconnected: ${peer.value}")
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            chunkSize[gatt.device.address] = (mtu - 3).coerceAtLeast(DEFAULT_CHUNK)
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val ok = status == BluetoothGatt.GATT_SUCCESS &&
                gatt.getService(SERVICE_UUID)?.getCharacteristic(BUNDLE_CHAR_UUID) != null
            readyDevices[gatt.device.address] = ok
            connectWaiters.remove(gatt.device.address)?.complete(ok)
            Log.d(TAG, "Services discovered for ${gatt.device.address}: ready=$ok")
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            // Complete the pending waiter for this device — this is the real end-to-end
            // confirmation. status != GATT_SUCCESS means the peer rejected the write
            // (e.g. no matching characteristic → their DTN service is gone) or the link
            // died mid-write. Either way it's a real "not delivered" signal.
            val ok = status == BluetoothGatt.GATT_SUCCESS
            chunkAckWaiters.remove(gatt.device.address)?.complete(ok)
            if (!ok) {
                Log.w(TAG, "onCharacteristicWrite FAILED status=$status for ${gatt.device.address}")
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun nodeFor(address: String): NodeId? =
        deviceForNode.entries.firstOrNull { it.value.address == address }?.key?.let { NodeId(it) }

    private fun int32(v: Int): ByteArray =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun int16(v: Int): ByteArray =
        byteArrayOf((v ushr 8).toByte(), v.toByte())

    private fun readInt32(b: ByteArray): Int =
        ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
}
