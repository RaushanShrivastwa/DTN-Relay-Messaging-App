package com.dtn.mesh.queue

/**
 * Configuration for the DTN message buffer / queue manager.
 */
data class BufferConfig(
    /** Maximum buffer size in bytes. Default: 1 MB. */
    val maxBufferBytes: Long = 1_048_576L,
    /** Buffer pressure threshold: when occupancy exceeds this fraction, start dropping. */
    val pressureThreshold: Double = 0.85,
    /** How long to wait for a Meshtastic ACK before reverting FORWARDING → BUFFERED (ms). */
    val forwardingTimeoutMs: Long = 30_000L,
    /** Retention period for DELIVERED/EXPIRED/DROPPED messages before purge (ms). Default: 1 hour. */
    val retentionPeriodMs: Long = 3_600_000L,
    /** Default TTL for locally-originated messages (ms). Default: 4 hours. */
    val defaultTtlMs: Long = 4 * 3_600_000L,
)
