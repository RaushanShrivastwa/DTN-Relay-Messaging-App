package com.dtn.mesh.export

/**
 * Container for all research export data produced by [ResearchExporter].
 * Each field contains the file content as a string (CSV or JSON).
 */
data class ResearchExportBundle(
    /** Encounter time-series (CSV). */
    val encountersCsv: String,
    /** Contact aggregate state (CSV). */
    val contactsCsv: String,
    /** Forwarding decision log with full state vectors (CSV). */
    val decisionsCsv: String,
    /** Message lifecycle tracking (CSV). */
    val messagesCsv: String,
    /** Q-table snapshot: both tables, episode/update counters (JSON). */
    val qTableJson: String,
    /** Experiment metadata: peer counts, action distribution, Q-engine stats (JSON). */
    val metadata: String,
)
