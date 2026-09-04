package com.dtn.mesh.receiver

import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.NodeId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstracts all communication with the Meshtastic radio transport layer.
 *
 * Only [MeshtasticTransportAdapter] implements this. Nothing in routing/,
 * learning/, queue/, scheduler/, or service/ should import Meshtastic types directly.
 *
 * ## Transport contract (verified from meshtastic/Meshtastic-Android reference)
 * - Bind via: Intent("com.geeksville.mesh.Service") resolved through PackageManager
 * - Receive via: BroadcastReceiver for "com.geeksville.mesh.RECEIVED.PRIVATE_APP"
 * - Send via: IMeshService.send(DataPacket) over AIDL
 * - ACK tracking via: "com.geeksville.mesh.MESSAGE_STATUS" broadcast
 * - All DTN traffic uses PortNum.PRIVATE_APP (256)
 * - Targeted unicast only (no broadcast forwarding)
 */
interface MeshTransport {

    // ──────────────────────────────────────────────────────────────────────
    // Inbound streams
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Hot Flow of inbound DTN messages decoded from Meshtastic PRIVATE_APP broadcasts.
     * Includes both DATA and control-plane messages (ROUTING_SUMMARY, BUNDLE_OFFER, etc.)
     * — the orchestrator decides what to persist vs process ephemerally.
     */
    val inboundMessages: Flow<InboundPacket>

    /**
     * Hot Flow of node encounter events (NODE_CHANGE broadcasts from Meshtastic).
     * Emits when a peer appears, disappears, or updates its signal metrics.
     */
    val nodeEvents: Flow<NodeEncounterEvent>

    /**
     * Hot Flow of message delivery status updates.
     * Emits when Meshtastic confirms delivery (ACK) or reports failure (NAK/timeout)
     * for a packet we previously sent.
     */
    val deliveryStatus: Flow<DeliveryStatusEvent>

    /**
     * Current connection state to the Meshtastic service.
     * Starts as [MeshConnectionState.DISCONNECTED] until user initiates connect.
     */
    val connectionState: StateFlow<MeshConnectionState>

    // ──────────────────────────────────────────────────────────────────────
    // Lifecycle (user-initiated)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Bind to the Meshtastic MeshService AIDL interface.
     * Must be explicitly called from a UI action (not auto-bind).
     * After the first successful connect, automatic reconnect on disconnect is handled internally.
     *
     * @return true if bind was initiated successfully, false if Meshtastic is not installed.
     */
    suspend fun connect(): Boolean

    /**
     * Unbind from MeshService. Stops receiving broadcasts.
     * Called on user request or when the foreground service is stopping.
     */
    fun disconnect()

    // ──────────────────────────────────────────────────────────────────────
    // Outbound
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Send a DTN message to a specific peer via Meshtastic AIDL.
     * This NEVER touches BLE directly — it delegates to IMeshService.send().
     *
     * @param message The DTN message (DATA or control-plane) to transmit.
     * @param targetPeer The specific peer to send to (targeted unicast, never broadcast).
     * @return The Meshtastic packet ID assigned for delivery tracking, or null if send failed.
     */
    suspend fun sendMessage(message: DtnMessage, targetPeer: NodeId): Int?

    // ──────────────────────────────────────────────────────────────────────
    // Query
    // ──────────────────────────────────────────────────────────────────────

    /** Whether the AIDL service is currently bound and the radio reports CONNECTED. */
    val isConnected: Boolean

    /** Our own node ID (populated after successful bind). Null if not connected. */
    val localNodeId: NodeId?
}

// ──────────────────────────────────────────────────────────────────────────
// Event data classes
// ──────────────────────────────────────────────────────────────────────────

/**
 * An inbound packet received from the mesh, already decoded from the DTN wire format.
 * Contains the parsed [DtnMessage] plus transport-level metadata not part of the DTN header.
 */
data class InboundPacket(
    /** The decoded DTN message (DATA or control-plane). */
    val message: DtnMessage,
    /** Meshtastic channel the packet arrived on. */
    val channel: Int,
    /** Timestamp of reception at the local device (epoch ms). */
    val receivedAtMs: Long = System.currentTimeMillis(),
    /**
     * The immediate previous hop we received this bundle from, when the transport can
     * identify it (BLE resolves it from the writer's advertised node id). Null when the
     * transport can't attribute a sender (e.g. LoRa hub flood). Used by the orchestrator to
     * avoid the reverse-echo bug — re-sending a bundle straight back to whoever just gave it
     * to us, which wastes half-duplex BLE airtime and stalls forward progress in a chain.
     */
    val viaPeer: NodeId? = null,
)

/**
 * A node presence/signal event derived from Meshtastic NODE_CHANGE broadcasts.
 */
data class NodeEncounterEvent(
    /** The peer node ID. */
    val nodeId: NodeId,
    /** Signal strength indicator from last packet. */
    val rssi: Int,
    /** Signal-to-noise ratio from last packet. */
    val snr: Float,
    /** When this event was observed (epoch ms). */
    val timestampMs: Long,
    /** Whether the peer is currently online. */
    val isOnline: Boolean,
    /** Human-readable long name, if available. */
    val longName: String? = null,
    /** Short name, if available. */
    val shortName: String? = null,
    /** Hardware model string, if available. */
    val hwModel: String? = null,
)

/**
 * Delivery status update for a previously sent packet.
 * Correlated back to DTN messages via the Meshtastic packet ID.
 */
data class DeliveryStatusEvent(
    /** The Meshtastic packet ID this status applies to. */
    val meshPacketId: Int,
    /** The delivery outcome. */
    val status: DeliveryOutcome,
    /** When this status was reported (epoch ms). */
    val timestampMs: Long = System.currentTimeMillis(),
)

enum class DeliveryOutcome {
    /** Meshtastic confirmed the peer received the packet (ACK). */
    DELIVERED,
    /** Meshtastic reported a failure (NAK or internal error). */
    FAILED,
    /** Status unknown / still in progress. */
    UNKNOWN,
}

enum class MeshConnectionState {
    /** Not bound to MeshService. */
    DISCONNECTED,
    /** Bind initiated, waiting for onServiceConnected callback. */
    CONNECTING,
    /** AIDL bound and Meshtastic reports radio is CONNECTED. */
    CONNECTED,
    /** Meshtastic reports the radio is in light sleep. */
    DEVICE_SLEEP,
}
