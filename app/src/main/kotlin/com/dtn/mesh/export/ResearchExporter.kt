package com.dtn.mesh.export

import com.dtn.mesh.database.dao.ContactDao
import com.dtn.mesh.database.dao.EncounterDao
import com.dtn.mesh.database.dao.ForwardingDecisionDao
import com.dtn.mesh.database.dao.MessageDao
import com.dtn.mesh.learning.DoubleQLearningEngine
import com.dtn.mesh.learning.QTableSnapshot
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Research data export hooks for offline experiment analysis.
 *
 * Produces CSV and JSON files that can be pulled off-device via adb or
 * shared through Android's file provider. Designed for:
 * - Contact/encounter history time-series
 * - Q-table snapshots (per-state action values)
 * - Forwarding decision logs (full state vector + action + reward)
 * - Message lifecycle tracking (buffer → forward → delivered/expired/dropped)
 * - Routing strategy state (PRoPHET P-vectors, MaxProp f-tables)
 */
@Singleton
class ResearchExporter @Inject constructor(
    private val messageDao: MessageDao,
    private val contactDao: ContactDao,
    private val encounterDao: EncounterDao,
    private val decisionDao: ForwardingDecisionDao,
    private val qEngine: DoubleQLearningEngine,
) {

    /**
     * Export all data for a time range. Returns a [ResearchExportBundle]
     * containing all datasets as string content (CSV/JSON).
     */
    suspend fun exportAll(fromMs: Long = 0, toMs: Long = System.currentTimeMillis()): ResearchExportBundle {
        return ResearchExportBundle(
            encountersCsv = exportEncountersCsv(fromMs, toMs),
            contactsCsv = exportContactsCsv(),
            decisionsCsv = exportDecisionsCsv(fromMs, toMs),
            messagesCsv = exportMessagesCsv(fromMs, toMs),
            qTableJson = exportQTableJson(),
            metadata = exportMetadata(fromMs, toMs),
        )
    }

    // ── Encounters CSV ───────────────────────────────────────────────────

    suspend fun exportEncountersCsv(fromMs: Long, toMs: Long): String {
        val rows = encounterDao.getInTimeRange(fromMs, toMs)
        return buildString {
            appendLine("id,peer_node_id,start_time_ms,end_time_ms,duration_ms,packet_count,best_rssi,best_snr,avg_rssi,avg_snr,messages_forwarded,messages_received,routing_summary_exchanged")
            for (r in rows) {
                appendLine("${r.id},${r.peerNodeId},${r.startTimeMs},${r.endTimeMs},${r.durationMs},${r.packetCount},${r.bestRssi},${r.bestSnr},${r.avgRssi},${r.avgSnr},${r.messagesForwarded},${r.messagesReceived},${r.routingSummaryExchanged}")
            }
        }
    }

    // ── Contacts CSV ─────────────────────────────────────────────────────

    suspend fun exportContactsCsv(): String {
        val rows = contactDao.getAllForExport()
        return buildString {
            appendLine("node_id,delivery_probability,path_likelihood,total_encounters,total_encounter_duration_ms,first_encounter_ms,last_encounter_ms,best_rssi,best_snr,last_rssi,last_snr,delivery_success_count,delivery_attempt_count,total_inter_contact_time_ms,is_online")
            for (r in rows) {
                appendLine("${r.nodeId},${r.deliveryProbability},${r.pathLikelihood},${r.totalEncounters},${r.totalEncounterDurationMs},${r.firstEncounterMs},${r.lastEncounterMs},${r.bestRssi},${r.bestSnr},${r.lastRssi},${r.lastSnr},${r.deliverySuccessCount},${r.deliveryAttemptCount},${r.totalInterContactTimeMs},${r.isOnline}")
            }
        }
    }

    // ── Forwarding Decisions CSV ─────────────────────────────────────────

    suspend fun exportDecisionsCsv(fromMs: Long, toMs: Long): String {
        val rows = decisionDao.getInTimeRange(fromMs, toMs)
        return buildString {
            appendLine("id,timestamp_ms,message_id,peer_node_id,rssi_norm,snr_norm,encounter_frequency,encounter_duration,delivery_probability,remaining_ttl_fraction,buffer_occupancy,historical_success_rate,duplicate_count_norm,action,q_value_selected,epsilon,was_exploratory,routing_strategy,reward,update_applied")
            for (r in rows) {
                appendLine("${r.id},${r.timestampMs},${r.messageId},${r.peerNodeId},${r.stateRssiNorm},${r.stateSnrNorm},${r.stateEncounterFrequency},${r.stateEncounterDuration},${r.stateDeliveryProbability},${r.stateRemainingTtlFraction},${r.stateBufferOccupancy},${r.stateHistoricalSuccessRate},${r.stateDuplicateCountNorm},${r.action},${r.qValueSelected},${r.epsilon},${r.wasExploratory},${r.routingStrategy},${r.reward ?: ""},${r.updateApplied}")
            }
        }
    }

    // ── Messages CSV ─────────────────────────────────────────────────────

    suspend fun exportMessagesCsv(fromMs: Long, toMs: Long): String {
        val rows = messageDao.getInTimeRange(fromMs, toMs)
        return buildString {
            appendLine("id,origin_node_id,destination_node_id,port_num,channel,created_at_ms,expires_at_ms,ttl_ms,hop_count,forward_count,duplicate_count,last_rssi,last_snr,status,received_at_ms,last_forwarded_to,last_forwarded_at_ms,mesh_packet_id,message_type,payload_size_bytes")
            for (r in rows) {
                appendLine("${r.id},${r.originNodeId},${r.destinationNodeId},${r.portNum},${r.channel},${r.createdAtMs},${r.expiresAtMs},${r.ttlMs},${r.hopCount},${r.forwardCount},${r.duplicateCount},${r.lastRssi},${r.lastSnr},${r.status},${r.receivedAtMs},${r.lastForwardedTo ?: ""},${r.lastForwardedAtMs ?: ""},${r.meshPacketId ?: ""},${r.messageType},${r.payloadSizeBytes}")
            }
        }
    }

    // ── Q-Table JSON ─────────────────────────────────────────────────────

    fun exportQTableJson(): String {
        val snapshot = qEngine.exportSnapshot()
        return buildQTableJson(snapshot)
    }

    private fun buildQTableJson(snapshot: QTableSnapshot): String {
        return buildString {
            appendLine("{")
            appendLine("  \"episodeCount\": ${snapshot.episodeCount},")
            appendLine("  \"totalUpdates\": ${snapshot.totalUpdates},")
            appendLine("  \"capturedAtMs\": ${snapshot.capturedAtMs},")
            appendLine("  \"tableA\": {")
            appendTableEntries(snapshot.tableA)
            appendLine("  },")
            appendLine("  \"tableB\": {")
            appendTableEntries(snapshot.tableB)
            appendLine("  }")
            appendLine("}")
        }
    }

    private fun StringBuilder.appendTableEntries(
        table: Map<String, Map<com.dtn.mesh.model.ForwardingAction, Double>>
    ) {
        val entries = table.entries.toList()
        for ((i, entry) in entries.withIndex()) {
            val actions = entry.value.entries.joinToString(", ") { (a, v) ->
                "\"${a.name}\": ${"%.6f".format(v)}"
            }
            val comma = if (i < entries.size - 1) "," else ""
            appendLine("    \"${entry.key}\": {$actions}$comma")
        }
    }

    // ── Metadata ─────────────────────────────────────────────────────────

    private suspend fun exportMetadata(fromMs: Long, toMs: Long): String {
        val uniquePeers = encounterDao.getUniquePeersEncountered()
        val statusCounts = messageDao.getStatusCounts()
        val actionDist = decisionDao.getActionDistribution()

        return buildString {
            appendLine("{")
            appendLine("  \"exportRangeFromMs\": $fromMs,")
            appendLine("  \"exportRangeToMs\": $toMs,")
            appendLine("  \"exportedAtMs\": ${System.currentTimeMillis()},")
            appendLine("  \"uniquePeersEncountered\": $uniquePeers,")
            appendLine("  \"qEngineEpsilon\": ${qEngine.currentEpsilon},")
            appendLine("  \"qEngineTotalUpdates\": ${qEngine.totalUpdates},")
            appendLine("  \"qTableASize\": ${qEngine.tableASize},")
            appendLine("  \"qTableBSize\": ${qEngine.tableBSize},")
            appendLine("  \"messageStatusCounts\": {")
            for ((i, sc) in statusCounts.withIndex()) {
                val comma = if (i < statusCounts.size - 1) "," else ""
                appendLine("    \"${sc.status}\": ${sc.count}$comma")
            }
            appendLine("  },")
            appendLine("  \"actionDistribution\": {")
            for ((i, ac) in actionDist.withIndex()) {
                val comma = if (i < actionDist.size - 1) "," else ""
                appendLine("    \"${ac.action}\": ${ac.count}$comma")
            }
            appendLine("  }")
            appendLine("}")
        }
    }
}
