package com.dtn.mesh.receiver

import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.NodeId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Generic DTN transport interface — any physical channel that can send/receive
 * DTN wire-format messages between peers.
 *
 * Implementations:
 * - [MeshtasticTransportAdapter]: LoRa mesh via Meshtastic AIDL
 * - [WifiDirectTransportAdapter]: Phone-to-phone via WiFi Direct
 *
 * The [MultiTransportManager] aggregates all active transports and presents
 * a unified view to the [DtnOrchestrator].
 */
interface DtnTransport {

    /** Human-readable name for logging. */
    val transportName: String

    /** Hot Flow of inbound DTN messages from this transport. */
    val inboundMessages: Flow<InboundPacket>

    /** Hot Flow of peer discovery/encounter events from this transport. */
    val nodeEvents: Flow<NodeEncounterEvent>

    /** Hot Flow of delivery status (ACK/NAK) from this transport. */
    val deliveryStatus: Flow<DeliveryStatusEvent>

    /** Current connection state of this transport. */
    val connectionState: StateFlow<MeshConnectionState>

    /** Whether this transport is currently connected and ready to send. */
    val isConnected: Boolean

    /** Our own node ID on this transport. Null if not connected. */
    val localNodeId: NodeId?

    /** Initiate connection. Returns true if started successfully. */
    suspend fun connect(): Boolean

    /** Disconnect and release resources. */
    fun disconnect()

    /**
     * Send a DTN message to a specific peer via this transport.
     * @return A transport-specific packet ID for tracking, or null on failure.
     */
    suspend fun sendMessage(message: DtnMessage, targetPeer: NodeId): Int?
}
