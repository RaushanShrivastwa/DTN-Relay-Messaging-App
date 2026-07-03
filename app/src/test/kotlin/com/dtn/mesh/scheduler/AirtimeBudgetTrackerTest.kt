package com.dtn.mesh.scheduler

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirtimeBudgetTrackerTest {

    @Test
    fun `fresh tracker allows sends`() {
        val tracker = AirtimeBudgetTracker(maxSendsPerEncounter = 3, maxSendsPerWindow = 5)
        assertTrue(tracker.canSend("!peer1"))
    }

    @Test
    fun `per-encounter limit enforced`() {
        val tracker = AirtimeBudgetTracker(maxSendsPerEncounter = 2, maxSendsPerWindow = 100)
        tracker.recordSend("!peer1")
        tracker.recordSend("!peer1")
        assertFalse(tracker.canSend("!peer1"))
        // Different peer still allowed
        assertTrue(tracker.canSend("!peer2"))
    }

    @Test
    fun `aggregate limit enforced across peers`() {
        val tracker = AirtimeBudgetTracker(maxSendsPerEncounter = 10, maxSendsPerWindow = 3)
        tracker.recordSend("!peer1")
        tracker.recordSend("!peer2")
        tracker.recordSend("!peer3")
        assertFalse(tracker.canSend("!peer4"))
    }

    @Test
    fun `resetEncounter clears per-peer counter`() {
        val tracker = AirtimeBudgetTracker(maxSendsPerEncounter = 1, maxSendsPerWindow = 100)
        tracker.recordSend("!peer1")
        assertFalse(tracker.canSend("!peer1"))
        tracker.resetEncounter("!peer1")
        assertTrue(tracker.canSend("!peer1"))
    }

    @Test
    fun `tokens refill after window elapses`() {
        val tracker = AirtimeBudgetTracker(
            maxSendsPerEncounter = 10, maxSendsPerWindow = 2, windowDurationMs = 50
        )
        tracker.recordSend("!peer1")
        tracker.recordSend("!peer1")
        assertFalse(tracker.canSend("!peer1"))
        Thread.sleep(60) // exceed window
        assertTrue(tracker.canSend("!peer1"))
    }
}
