package com.dtn.mesh.learning

import com.dtn.mesh.model.ForwardingAction
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Double Q-Learning forwarding engine for DTN store-carry-forward decisions.
 *
 * Maintains two independent Q-tables (QA and QB) to reduce overestimation bias
 * (van Hasselt, 2010). On each update step one table is chosen at random to update;
 * the other evaluates the bootstrapped target action.
 *
 * ## State discretisation
 * Continuous [ForwardingState] features in [0,1] are bucketed into [config.stateBuckets]
 * discrete levels. The state key is the concatenation of all bucket indices, producing
 * a string like "3-7-2-5-8-1-4-6-0" (9 dimensions × N buckets each).
 *
 * ## Action space
 * [ForwardingAction]: STORE, FORWARD, WAIT, DROP
 *
 * ## Thread safety
 * Not internally synchronised. All calls must be serialised by the caller
 * (ForwardingWorker runs on a single sequential coroutine).
 *
 * ## Dependencies
 * Zero Android framework dependencies. Independently unit-testable with synthetic data.
 */
class DoubleQLearningEngine(
    val config: QLearningConfig = QLearningConfig(),
    private val random: Random = Random.Default,
) {
    private val tableA = QTable()
    private val tableB = QTable()

    private var _epsilon: Double = config.epsilonInitial
    private var _episodeCount: Long = 0
    private var _totalUpdates: Long = 0

    /** Current exploration epsilon (read-only, for logging/export). */
    val currentEpsilon: Double get() = _epsilon

    /** Total Q-update steps performed. */
    val totalUpdates: Long get() = _totalUpdates

    /** Number of states visited in table A. */
    val tableASize: Int get() = tableA.size

    /** Number of states visited in table B. */
    val tableBSize: Int get() = tableB.size

    // ──────────────────────────────────────────────────────────────────────
    // Action selection
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Greedy action selection using averaged Q-values: Q̄ = (QA + QB) / 2.
     * Used for inference (exploitation only, no exploration).
     */
    fun selectAction(state: ForwardingState): ForwardingAction {
        val key = discretise(state)
        return bestActionByAverage(key)
    }

    /**
     * ε-greedy action selection for training.
     * With probability [epsilon]: choose uniformly at random (exploration).
     * Otherwise: greedy w.r.t. averaged Q-values (exploitation).
     *
     * @return Pair of (action chosen, whether it was exploratory)
     */
    fun selectActionEpsilonGreedy(state: ForwardingState): Pair<ForwardingAction, Boolean> {
        val explore = random.nextDouble() < _epsilon
        return if (explore) {
            val actions = ForwardingAction.entries
            actions[random.nextInt(actions.size)] to true
        } else {
            selectAction(state) to false
        }
    }

    private fun bestActionByAverage(stateKey: String): ForwardingAction {
        var bestAction = ForwardingAction.STORE
        var bestValue = Double.NEGATIVE_INFINITY

        for (action in ForwardingAction.entries) {
            val avg = (tableA.get(stateKey, action) + tableB.get(stateKey, action)) / 2.0
            if (avg > bestValue) {
                bestValue = avg
                bestAction = action
            }
        }
        return bestAction
    }

    // ──────────────────────────────────────────────────────────────────────
    // Q-update (Double Q-Learning core)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Perform one Double Q-Learning update step.
     *
     * Algorithm (van Hasselt 2010):
     * 1. Flip a fair coin to choose the "update" table (A or B).
     * 2. Use the OTHER table to evaluate the best next action (argmax).
     * 3. Update the chosen table with the TD target:
     *    Q_update(s,a) ← Q_update(s,a) + α * [r + γ * Q_other(s', argmax_a' Q_update(s',a')) - Q_update(s,a)]
     *
     * @param state      Observation before the action.
     * @param action     Action that was taken.
     * @param reward     Scalar reward signal.
     * @param nextState  Observation after action (null if terminal/episode end).
     */
    fun update(
        state: ForwardingState,
        action: ForwardingAction,
        reward: Double,
        nextState: ForwardingState?,
    ) {
        val stateKey = discretise(state)
        val nextStateKey = nextState?.let { discretise(it) }

        val updateA = random.nextBoolean()

        if (updateA) {
            updateTable(
                updateTable = tableA,
                evalTable = tableB,
                stateKey = stateKey,
                action = action,
                reward = reward,
                nextStateKey = nextStateKey,
            )
        } else {
            updateTable(
                updateTable = tableB,
                evalTable = tableA,
                stateKey = stateKey,
                action = action,
                reward = reward,
                nextStateKey = nextStateKey,
            )
        }

        _totalUpdates++
        decayEpsilon()
    }

    private fun updateTable(
        updateTable: QTable,
        evalTable: QTable,
        stateKey: String,
        action: ForwardingAction,
        reward: Double,
        nextStateKey: String?,
    ) {
        val currentQ = updateTable.get(stateKey, action)

        val target = if (nextStateKey == null) {
            // Terminal state — no future reward
            reward
        } else {
            // Double Q trick: argmax from update table, value from eval table
            val bestNextAction = updateTable.argmax(nextStateKey)
            reward + config.discountFactor * evalTable.get(nextStateKey, bestNextAction)
        }

        val newQ = currentQ + config.learningRate * (target - currentQ)
        updateTable.set(stateKey, action, newQ)
    }

    private fun decayEpsilon() {
        _epsilon = (_epsilon * config.epsilonDecay).coerceAtLeast(config.epsilonMin)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Utility computation
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Compute a utility score in [0.0, 1.0] representing the desirability
     * of forwarding a message given the current state.
     *
     * Uses the averaged Q-value of FORWARD normalised against all actions.
     * Used by routing strategies as a tie-breaker between candidates.
     */
    fun computeUtility(state: ForwardingState): Double {
        val key = discretise(state)
        val forwardQ = (tableA.get(key, ForwardingAction.FORWARD) +
            tableB.get(key, ForwardingAction.FORWARD)) / 2.0

        // Normalise against max possible Q to get [0,1]
        val allQs = ForwardingAction.entries.map { action ->
            (tableA.get(key, action) + tableB.get(key, action)) / 2.0
        }
        val maxQ = allQs.maxOrNull() ?: 0.0
        val minQ = allQs.minOrNull() ?: 0.0
        val range = maxQ - minQ

        return if (range == 0.0) 0.5 // No information yet — neutral utility
        else ((forwardQ - minQ) / range).coerceIn(0.0, 1.0)
    }

    // ──────────────────────────────────────────────────────────────────────
    // State discretisation
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Discretise a continuous [ForwardingState] into a string key.
     *
     * Each dimension uses its own bucket count from [QLearningConfig], producing
     * a key like "1-0-2-1-4-3-1-0-1" where each position has a range determined
     * by its feature's granularity.
     *
     * Effective state space with defaults: 5×5×3×3×3×2×2×2×2 = 10,800
     */
    fun discretise(state: ForwardingState): String {
        return buildString {
            append(bucket(state.rssiNorm, config.bucketsRssi)); append('-')
            append(bucket(state.snrNorm, config.bucketsSnr)); append('-')
            append(bucket(state.encounterFrequency, config.bucketsEncounterFrequency)); append('-')
            append(bucket(state.encounterDuration, config.bucketsEncounterDuration)); append('-')
            append(bucket(state.deliveryProbability, config.bucketsDeliveryProbability)); append('-')
            append(bucket(state.remainingTtlFraction, config.bucketsRemainingTtl)); append('-')
            append(bucket(state.bufferOccupancy, config.bucketsBufferOccupancy)); append('-')
            append(bucket(state.historicalSuccessRate, config.bucketsHistoricalSuccess)); append('-')
            append(bucket(state.duplicateCountNorm, config.bucketsDuplicateCount))
        }
    }

    private fun bucket(value: Double, numBuckets: Int): Int =
        (value.coerceIn(0.0, 1.0) * (numBuckets - 1)).roundToInt()

    // ──────────────────────────────────────────────────────────────────────
    // Snapshot / persistence
    // ──────────────────────────────────────────────────────────────────────

    /** Export a snapshot of both Q-tables for research export or persistence. */
    fun exportSnapshot(): QTableSnapshot = QTableSnapshot(
        tableA = tableA.export(),
        tableB = tableB.export(),
        episodeCount = _episodeCount,
        totalUpdates = _totalUpdates,
        capturedAtMs = System.currentTimeMillis(),
    )

    /** Restore Q-tables from a previously exported snapshot. Allows warm-start. */
    fun importSnapshot(snapshot: QTableSnapshot) {
        tableA.import(snapshot.tableA)
        tableB.import(snapshot.tableB)
        _episodeCount = snapshot.episodeCount
        _totalUpdates = snapshot.totalUpdates
    }

    /** Reset both tables to zero. Useful for ablation experiments. */
    fun reset() {
        tableA.clear()
        tableB.clear()
        _epsilon = config.epsilonInitial
        _episodeCount = 0
        _totalUpdates = 0
    }

    /** Increment episode counter (called when a full encounter window concludes). */
    fun endEpisode() {
        _episodeCount++
    }

    /** Set epsilon directly (for experiment scripting). */
    fun setEpsilon(value: Double) {
        _epsilon = value.coerceIn(0.0, 1.0)
    }
}
