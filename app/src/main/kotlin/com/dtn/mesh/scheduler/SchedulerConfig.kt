package com.dtn.mesh.scheduler

/**
 * Configuration for the periodic forwarding scheduler.
 */
data class SchedulerConfig(
    /** Interval between forwarding cycles (minutes). WorkManager minimum is 15. */
    val intervalMinutes: Long = 15L,
    /** Maximum messages to send per forwarding cycle (airtime budget). */
    val maxSendsPerCycle: Int = 5,
    /** Whether the worker should run on metered networks. */
    val allowMetered: Boolean = true,
)
