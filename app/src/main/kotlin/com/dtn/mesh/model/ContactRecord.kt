package com.dtn.mesh.model

/**
 * Summary of a single encounter with a peer node.
 * Passed into routing strategies and the Q-learning engine on each contact event.
 */
data class ContactRecord(
    /** The peer we encountered. */
    val peerId: NodeId,
    /** When this encounter began (epoch ms). */
    val startTimeMs: Long,
    /** Duration of the encounter window (ms). 0 if we only saw a single packet. */
    val durationMs: Long = 0,
    /** Best RSSI observed during this encounter. */
    val rssi: Int,
    /** Best SNR observed during this encounter. */
    val snr: Float,
    /** Number of packets exchanged during this encounter. */
    val packetCount: Int = 1,
)
