package com.dtn.mesh.service

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central message-lifecycle event logger. Every state transition of every message goes
 * through here — the dedicated "Lifecycle" page in the UI observes [entries] to give
 * a real-time stream of exactly what the DTN layer is doing.
 *
 * Also maintains per-message hop views in the form each node sees them:
 *     source → prevHop → me → nextHop
 * That is, the node's own perspective on the route. Each phone only knows what it directly
 * observed (the message came FROM someone, was handled BY it, and went TO someone) — it
 * doesn't try to reconstruct the full cross-network path (that would require a protocol
 * change to append hop lists to the wire).
 */
@Singleton
class MessageLifecycleLog @Inject constructor() {

    companion object {
        private const val TAG = "MsgLifecycle"
        private const val MAX_ENTRIES = 200
        private const val MAX_ROUTES = 64
    }

    private val _entries = MutableStateFlow<List<LifecycleEntry>>(emptyList())
    val entries: StateFlow<List<LifecycleEntry>> = _entries.asStateFlow()

    /**
     * Per-message route as seen from THIS node's perspective:
     * `source → prevHop → me → nextHop`.
     *
     * - `source`: origin node id from the wire header (fixed for the life of the message).
     * - `prevHop`: the peer we received the message FROM. Null if we are the source.
     * - `me`: our own node id (fixed once known).
     * - `nextHop`: the peer we most recently forwarded/delivered TO. Null until we send it.
     * - `delivered`: true if the message reached its destination (either we are the
     *    destination, or we sent it directly to the destination peer).
     */
    data class RouteRecord(
        val msgId: String,
        val source: String,
        val destination: String,
        val prevHop: String? = null,
        val me: String,
        val nextHop: String? = null,
        val delivered: Boolean = false,
        val timestampMs: Long = System.currentTimeMillis(),
        /** Routing algorithm active when this node last forwarded the message (e.g. "PROPHET"). */
        val strategy: String = "",
    ) {
        /** Human-readable view: `source → prevHop → me → nextHop`, omitting nulls. */
        fun asPath(): String = buildString {
            append(source)
            if (!prevHop.isNullOrBlank() && prevHop != source && prevHop != me) {
                append(" → "); append(prevHop)
            }
            if (me.isNotBlank() && me != source) {
                append(" → "); append(me)
            }
            if (!nextHop.isNullOrBlank() && nextHop != me) {
                append(" → "); append(nextHop)
            }
        }
    }

    private val _routes = MutableStateFlow<List<RouteRecord>>(emptyList())
    val routes: StateFlow<List<RouteRecord>> = _routes.asStateFlow()

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * Guards the read-modify-write of [_entries] and [_routes]. Both are updated from multiple
     * threads (GATT server callback on RX, orchestrator dispatcher on TX/forward), and the
     * "read value → build new list → set value" sequence is not atomic. Without this lock two
     * threads could each miss an existing route for the same message id and both prepend a new
     * record — producing two entries with the same key, which crashes the Compose LazyColumn
     * ("Key X was already used"). Serialising the mutations removes the duplication at the source.
     */
    private val lock = Any()

    fun log(
        event: LifecycleEvent,
        msgId: String,
        origin: String = "",
        dest: String = "",
        hopCount: Int = -1,
        forwardCount: Int = -1,
        ttlRemainingMs: Long = -1,
        extra: String = "",
    ) {
        val entry = LifecycleEntry(
            timestamp = timeFmt.format(Date()),
            event = event,
            msgId = msgId.take(8),
            origin = origin.takeLast(8),
            dest = if (dest == "^all" || dest == "!ffffffff") "BROADCAST" else dest.takeLast(8),
            hopCount = hopCount,
            forwardCount = forwardCount,
            ttlRemainingMin = if (ttlRemainingMs > 0) (ttlRemainingMs / 60_000L).toInt() else -1,
            extra = extra,
        )
        synchronized(lock) {
            _entries.value = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
        }
        Log.d(TAG, entry.toLogLine())
    }

    // ══════════════════════════════════════════════════════════════════════
    // Route tracking (source → prevHop → me → nextHop)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Called at the origin when a message is first created locally. Sets `source = me`
     * and clears prev/next. Subsequent forwards update `nextHop`.
     */
    fun recordAsOrigin(msgId: String, source: String, dest: String, me: String) {
        upsertRoute(msgId) { existing ->
            (existing ?: newRecord(msgId, source, dest, me)).copy(
                source = source.takeLast(8),
                destination = normaliseDest(dest),
                prevHop = null,
                me = me.takeLast(8),
            )
        }
    }

    /**
     * Called when a message arrives at this node (either as final recipient or as a mule).
     * Records the peer we received it from as `prevHop`. If `delivered=true` this node
     * IS the destination.
     */
    fun recordIncoming(
        msgId: String, source: String, dest: String, prevHop: String, me: String, delivered: Boolean,
    ) {
        upsertRoute(msgId) { existing ->
            val base = existing ?: newRecord(msgId, source, dest, me)
            base.copy(
                source = source.takeLast(8),
                destination = normaliseDest(dest),
                prevHop = prevHop.takeLast(8),
                me = me.takeLast(8),
                delivered = base.delivered || delivered,
            )
        }
    }

    /**
     * Called when we forward or directly deliver a message to a next hop. Sets `nextHop`.
     * If `delivered=true` we handed it directly to the final destination.
     */
    fun recordOutgoing(
        msgId: String, source: String, dest: String, me: String, nextHop: String, delivered: Boolean,
        strategy: String = "",
    ) {
        upsertRoute(msgId) { existing ->
            val base = existing ?: newRecord(msgId, source, dest, me)
            base.copy(
                source = source.takeLast(8),
                destination = normaliseDest(dest),
                me = me.takeLast(8),
                nextHop = nextHop.takeLast(8),
                delivered = base.delivered || delivered,
                strategy = strategy.ifBlank { base.strategy },
            )
        }
    }

    private fun upsertRoute(msgId: String, mutate: (RouteRecord?) -> RouteRecord) {
        val key = msgId.take(8)
        // Entire read-modify-write must be atomic: concurrent RX (recordIncoming) and TX
        // (recordOutgoing) for the same message would otherwise both see idx == -1 and each
        // prepend a fresh record with the same msgId key — the exact duplicate-key that
        // crashes the route LazyColumn.
        synchronized(lock) {
            val current = _routes.value.toMutableList()
            val idx = current.indexOfFirst { it.msgId == key }
            val updated = if (idx >= 0) mutate(current[idx]) else mutate(null)
            if (idx >= 0) {
                current[idx] = updated
                _routes.value = current
            } else {
                _routes.value = (listOf(updated) + current).take(MAX_ROUTES)
            }
        }
    }

    private fun newRecord(msgId: String, source: String, dest: String, me: String) = RouteRecord(
        msgId = msgId.take(8),
        source = source.takeLast(8),
        destination = normaliseDest(dest),
        me = me.takeLast(8),
    )

    private fun normaliseDest(dest: String): String =
        if (dest == "^all" || dest == "!ffffffff") "BROADCAST" else dest.takeLast(8)
}

enum class LifecycleEvent {
    CREATED,
    BUFFERED,
    DUPLICATE,
    FORWARDED,
    IGNORED,
    ACK_GENERATED,
    ACK_RECEIVED,
    BUFFER_CLEARED,
    DELIVERED,
    EXPIRED,
    HOP_LIMIT,

    /** Removed from the buffer without delivery by the housekeeping policy (low P / buffer pressure). */
    DROPPED,
}

data class LifecycleEntry(
    val timestamp: String,
    val event: LifecycleEvent,
    val msgId: String,
    val origin: String,
    val dest: String,
    val hopCount: Int,
    val forwardCount: Int,
    val ttlRemainingMin: Int,
    val extra: String,
) {
    fun toLogLine(): String = buildString {
        append("[$timestamp] ${event.name.padEnd(14)} msg=$msgId")
        if (origin.isNotEmpty()) append(" from=$origin")
        if (dest.isNotEmpty()) append(" to=$dest")
        if (hopCount >= 0) append(" hop=$hopCount")
        if (forwardCount >= 0) append(" fwd=$forwardCount")
        if (ttlRemainingMin >= 0) append(" ttl=${ttlRemainingMin}m")
        if (extra.isNotEmpty()) append(" [$extra]")
    }
}
