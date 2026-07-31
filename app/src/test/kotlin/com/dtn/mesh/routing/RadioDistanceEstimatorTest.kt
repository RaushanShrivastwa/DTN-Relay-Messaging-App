package com.dtn.mesh.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioDistanceEstimatorTest {

    @Test
    fun `unknown RSSI returns sentinel`() {
        assertEquals(RadioDistanceEstimator.UNKNOWN_DISTANCE,
            RadioDistanceEstimator.estimateMeters(Int.MIN_VALUE), 0.001)
        assertEquals(RadioDistanceEstimator.UNKNOWN_DISTANCE,
            RadioDistanceEstimator.estimateMeters(0), 0.001)
    }

    @Test
    fun `RSSI at reference power reports approximately 1 metre for BLE`() {
        // BLE calibration: txPowerAt1m = -59 dBm. At exactly -59 the derivation gives d = 1 m.
        val d = RadioDistanceEstimator.estimateMeters(-59, RadioDistanceEstimator.Transport.BLE)
        assertEquals(1.0, d, 0.05)
    }

    @Test
    fun `weaker RSSI reports larger distance`() {
        val close = RadioDistanceEstimator.estimateMeters(-60, RadioDistanceEstimator.Transport.BLE)
        val far = RadioDistanceEstimator.estimateMeters(-90, RadioDistanceEstimator.Transport.BLE)
        assertTrue("far should be > close ($close < $far)", far > close)
    }

    @Test
    fun `garbage RSSI values are clamped to a sane range`() {
        val d1 = RadioDistanceEstimator.estimateMeters(-999, RadioDistanceEstimator.Transport.BLE)
        val d2 = RadioDistanceEstimator.estimateMeters(999, RadioDistanceEstimator.Transport.BLE)
        assertTrue("d1 finite and > 0 (was $d1)", d1.isFinite() && d1 > 0.0)
        assertTrue("d2 finite and > 0 (was $d2)", d2.isFinite() && d2 > 0.0)
    }

    @Test
    fun `LoRa uses a different calibration than BLE`() {
        // Same RSSI reading, different transports → different derived distances because
        // reference power and path-loss exponent differ. LoRa has a much stronger reference
        // (higher TX power at 1 m), so the same weak received reading implies farther range.
        val dBle = RadioDistanceEstimator.estimateMeters(-80, RadioDistanceEstimator.Transport.BLE)
        val dLora = RadioDistanceEstimator.estimateMeters(-80, RadioDistanceEstimator.Transport.LORA)
        assertTrue("LoRa should differ from BLE at same RSSI ($dLora vs $dBle)", dLora != dBle)
        assertTrue("LoRa should imply greater distance at -80 dBm ($dLora vs $dBle)", dLora > dBle)
    }
}
