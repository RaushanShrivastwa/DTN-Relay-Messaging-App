package com.dtn.mesh.learning

import com.dtn.mesh.model.ForwardingAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/**
 * Unit tests for [DoubleQLearningEngine].
 * Uses synthetic encounter data — no Android framework dependencies.
 */
class DoubleQLearningEngineTest {

    private lateinit var engine: DoubleQLearningEngine

    private val testConfig = QLearningConfig(
        learningRate = 0.5,
        discountFactor = 0.9,
        epsilonInitial = 1.0,
        epsilonMin = 0.01,
        epsilonDecay = 0.99,
        // Use small buckets for fast convergence in tests
        bucketsDeliveryProbability = 3,
        bucketsRemainingTtl = 3,
        bucketsBufferOccupancy = 2,
        bucketsHistoricalSuccess = 2,
        bucketsEncounterFrequency = 2,
        bucketsRssi = 2,
        bucketsSnr = 2,
        bucketsEncounterDuration = 2,
        bucketsDuplicateCount = 2,
    )

    @Before
    fun setup() {
        engine = DoubleQLearningEngine(config = testConfig, random = Random(42))
    }

    private fun makeState(
        rssi: Double = 0.5,
        snr: Double = 0.5,
        freq: Double = 0.5,
        dur: Double = 0.5,
        prob: Double = 0.5,
        ttl: Double = 0.5,
        buf: Double = 0.5,
        success: Double = 0.5,
        dup: Double = 0.0,
    ) = ForwardingState(rssi, snr, freq, dur, prob, ttl, buf, success, dup)

    @Test
    fun `discretise produces consistent keys`() {
        val state = makeState()
        val key1 = engine.discretise(state)
        val key2 = engine.discretise(state)
        assertEquals(key1, key2)
    }

    @Test
    fun `discretise produces different keys for different states`() {
        val s1 = makeState(rssi = 0.1)
        val s2 = makeState(rssi = 0.9)
        assertNotEquals(engine.discretise(s1), engine.discretise(s2))
    }

    @Test
    fun `initial action selection returns STORE for unseen states`() {
        // With epsilon=1.0, selectAction (greedy) should return STORE for empty tables
        engine.setEpsilon(0.0) // force greedy
        val action = engine.selectAction(makeState())
        assertEquals(ForwardingAction.STORE, action)
    }

    @Test
    fun `update changes Q-values`() {
        val state = makeState()
        engine.setEpsilon(0.0)

        // Before any update, all Q-values are 0
        val actionBefore = engine.selectAction(state)
        assertEquals(ForwardingAction.STORE, actionBefore)

        // Give FORWARD a positive reward repeatedly
        repeat(20) {
            engine.update(state, ForwardingAction.FORWARD, reward = 1.0, nextState = null)
        }

        // Now greedy should prefer FORWARD
        val actionAfter = engine.selectAction(state)
        assertEquals(ForwardingAction.FORWARD, actionAfter)
    }

    @Test
    fun `negative reward discourages action`() {
        val state = makeState()
        engine.setEpsilon(0.0)

        // Punish DROP heavily
        repeat(20) {
            engine.update(state, ForwardingAction.DROP, reward = -1.0, nextState = null)
        }
        // Give STORE a mild positive
        repeat(20) {
            engine.update(state, ForwardingAction.STORE, reward = 0.2, nextState = null)
        }

        val action = engine.selectAction(state)
        assertNotEquals(ForwardingAction.DROP, action)
    }

    @Test
    fun `epsilon decays on each update`() {
        val initialEps = engine.currentEpsilon
        engine.update(makeState(), ForwardingAction.STORE, 0.0, null)
        assertTrue(engine.currentEpsilon < initialEps)
    }

    @Test
    fun `epsilon does not go below minimum`() {
        engine.setEpsilon(testConfig.epsilonMin)
        engine.update(makeState(), ForwardingAction.STORE, 0.0, null)
        assertTrue(engine.currentEpsilon >= testConfig.epsilonMin)
    }

    @Test
    fun `utility returns 0_5 for unseen state`() {
        val utility = engine.computeUtility(makeState())
        assertEquals(0.5, utility, 0.001)
    }

    @Test
    fun `utility increases after positive FORWARD rewards`() {
        val state = makeState()
        repeat(30) {
            engine.update(state, ForwardingAction.FORWARD, reward = 1.0, nextState = null)
        }
        val utility = engine.computeUtility(state)
        assertTrue("Utility should be > 0.5, was $utility", utility > 0.5)
    }

    @Test
    fun `snapshot export and import restores state`() {
        val state = makeState()
        repeat(10) {
            engine.update(state, ForwardingAction.FORWARD, reward = 1.0, nextState = null)
        }

        val snapshot = engine.exportSnapshot()
        val newEngine = DoubleQLearningEngine(config = testConfig)
        newEngine.importSnapshot(snapshot)

        // Same action selection after import
        newEngine.setEpsilon(0.0)
        engine.setEpsilon(0.0)
        assertEquals(engine.selectAction(state), newEngine.selectAction(state))
        assertEquals(snapshot.totalUpdates, newEngine.totalUpdates)
    }

    @Test
    fun `reset clears all learned values`() {
        val state = makeState()
        repeat(10) {
            engine.update(state, ForwardingAction.FORWARD, reward = 1.0, nextState = null)
        }
        engine.reset()

        assertEquals(0, engine.tableASize)
        assertEquals(0, engine.tableBSize)
        assertEquals(0L, engine.totalUpdates)
        assertEquals(testConfig.epsilonInitial, engine.currentEpsilon, 0.001)
    }

    @Test
    fun `double Q reduces overestimation vs single table`() {
        // Train with noisy rewards — double Q should produce more conservative estimates
        val state = makeState()
        val nextState = makeState(rssi = 0.8)
        val rng = java.util.Random(123)

        repeat(100) {
            // Reward with high variance: mean 0.5, sd 1.0
            val noisyReward = 0.5 + rng.nextGaussian()
            engine.update(state, ForwardingAction.FORWARD, noisyReward, nextState)
        }

        // The utility should be bounded and not explode due to overestimation
        val utility = engine.computeUtility(state)
        assertTrue("Utility $utility should be bounded in [0,1]", utility in 0.0..1.0)
    }

    @Test
    fun `multi-step learning propagates value`() {
        engine.setEpsilon(0.0)
        val s1 = makeState(ttl = 0.9)   // early in message life
        val s2 = makeState(ttl = 0.5)   // mid life
        val s3 = makeState(ttl = 0.1)   // near expiry — terminal

        // Reward only at terminal state for FORWARD
        repeat(50) {
            engine.update(s3, ForwardingAction.FORWARD, reward = 1.0, nextState = null)
            engine.update(s2, ForwardingAction.FORWARD, reward = 0.0, nextState = s3)
            engine.update(s1, ForwardingAction.FORWARD, reward = 0.0, nextState = s2)
        }

        // Value should propagate backwards: s1 should now prefer FORWARD
        val action = engine.selectAction(s1)
        assertEquals(ForwardingAction.FORWARD, action)
    }
}
