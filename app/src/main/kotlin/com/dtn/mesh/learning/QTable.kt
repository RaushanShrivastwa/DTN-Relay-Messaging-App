package com.dtn.mesh.learning

import com.dtn.mesh.model.ForwardingAction

/**
 * A single Q-table: maps discretised state keys to action-value estimates.
 *
 * Thread safety: not internally synchronised. The engine serialises access.
 * This is acceptable because the Q-update path is single-threaded (batched
 * in ForwardingWorker on a sequential coroutine).
 */
class QTable {

    private val table: MutableMap<String, MutableMap<ForwardingAction, Double>> = mutableMapOf()

    /** Get Q(state, action). Returns 0.0 for unseen state-action pairs (optimistic init). */
    fun get(stateKey: String, action: ForwardingAction): Double =
        table[stateKey]?.get(action) ?: 0.0

    /** Set Q(state, action) = value. */
    fun set(stateKey: String, action: ForwardingAction, value: Double) {
        table.getOrPut(stateKey) { mutableMapOf() }[action] = value
    }

    /** Get all Q-values for a state. Returns empty map for unseen states. */
    fun getAll(stateKey: String): Map<ForwardingAction, Double> =
        table[stateKey] ?: emptyMap()

    /** Argmax action for a given state. Breaks ties randomly via the provided RNG seed. */
    fun argmax(stateKey: String): ForwardingAction {
        val values = table[stateKey]
        if (values.isNullOrEmpty()) return ForwardingAction.STORE // default for unseen states

        val maxVal = values.maxOf { it.value }
        val ties = values.filter { it.value == maxVal }.keys.toList()
        return ties[ties.indices.random()]
    }

    /** Max Q-value for a given state. Returns 0.0 for unseen states. */
    fun maxValue(stateKey: String): Double {
        val values = table[stateKey] ?: return 0.0
        return if (values.isEmpty()) 0.0 else values.maxOf { it.value }
    }

    /** Export as an immutable map for snapshotting. */
    fun export(): Map<String, Map<ForwardingAction, Double>> =
        table.mapValues { it.value.toMap() }

    /** Import from a snapshot, replacing all current entries. */
    fun import(data: Map<String, Map<ForwardingAction, Double>>) {
        table.clear()
        data.forEach { (key, actions) ->
            table[key] = actions.toMutableMap()
        }
    }

    /** Number of unique states stored. */
    val size: Int get() = table.size

    /** Reset to empty. */
    fun clear() = table.clear()
}
