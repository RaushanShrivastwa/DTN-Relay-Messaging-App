package com.dtn.mesh.routing

import kotlin.math.pow

/**
 * Cross-transport distance estimator implementing the log-distance path-loss model.
 *
 * The PROPHET direct-encounter update from the design notes uses a distance-aware boost:
 *   P(a,b) = P(a,b)_old + (1 - P(a,b)_old) * P_init + ε^d
 *
 * Getting an exact `d` from the physical-layer variables in Eq. 1 (transmit power, antenna
 * gains, antenna heights, loss factor) is not possible on stock Android BLE — the only
 * radio metric available at the app layer is RSSI (dBm). We use the widely-used
 * log-distance approximation:
 *
 *   RSSI = TxPower_ref - 10 · n · log10(d / d_ref)
 * ⇒ d   = d_ref · 10^((TxPower_ref - RSSI) / (10 · n))
 *
 * Constants are calibrated per transport because BLE, WiFi Direct and LoRa each have very
 * different reference power levels and path-loss exponents. If we ever add hub-side LoRa
 * telemetry that reports a peer's RSSI over the hub link, [Transport.LORA] is used.
 *
 * ## Design guardrails
 * - Anything outside a reasonable RSSI window ([-30, -120] dBm) is clamped, not extrapolated,
 *   so a noisy scan callback with a garbage reading can't blow up `d` (and thus ε^d).
 * - Result is clamped to `[MIN_DISTANCE_M, MAX_DISTANCE_M]` for the same reason.
 * - Zero-RSSI or missing RSSI (sentinel `Int.MIN_VALUE`) returns [UNKNOWN_DISTANCE] so callers
 *   can decide to skip the distance term entirely rather than get a bogus number.
 */
object RadioDistanceEstimator {

    /** Sentinel used when RSSI is unavailable — callers should skip the distance term. */
    const val UNKNOWN_DISTANCE: Double = -1.0

    /** Minimum plausible distance in metres — floor to avoid `d = 0` singularity in ε^d. */
    private const val MIN_DISTANCE_M = 0.5
    /** Maximum reported distance. Beyond this ε^d is already numerically zero for any ε ∈ (0,1). */
    private const val MAX_DISTANCE_M = 500.0

    /** Sane RSSI window (dBm). Real BLE hardware never reports outside this in normal use. */
    private const val RSSI_MIN_DBM = -120
    private const val RSSI_MAX_DBM = -20

    enum class Transport { BLE, WIFI_DIRECT, LORA }

    /**
     * Per-transport calibration:
     *  - `txPowerAt1m`: expected RSSI when the peer is exactly 1 metre away.
     *  - `pathLossExponent`: `n` in the log-distance model. Free space is 2.0; typical
     *    indoor mixed-obstacle environments sit around 2.5–3.5. Higher = signal fades
     *    faster with distance.
     */
    private data class Calibration(val txPowerAt1m: Double, val pathLossExponent: Double)

    private val calibrations = mapOf(
        // BLE @ 1 m indoor is empirically ~ -59 dBm with a path-loss exponent around 2.5.
        Transport.BLE to Calibration(txPowerAt1m = -59.0, pathLossExponent = 2.5),
        // WiFi Direct typically sits at stronger RSSI at short range; n similar to BLE.
        Transport.WIFI_DIRECT to Calibration(txPowerAt1m = -45.0, pathLossExponent = 2.5),
        // LoRa: very different physics — much higher TX power, long range. n typically 3.0
        // outdoors and up to 3.5–4.0 through walls.
        Transport.LORA to Calibration(txPowerAt1m = -40.0, pathLossExponent = 3.0),
    )

    /**
     * Estimate the peer's physical distance from the last observed RSSI.
     *
     * @param rssi Observed RSSI in dBm. `Int.MIN_VALUE` (or 0) means "unknown".
     * @param transport Which radio produced the reading — controls the calibration.
     * @return Distance in metres, or [UNKNOWN_DISTANCE] if the input is unusable.
     */
    fun estimateMeters(rssi: Int, transport: Transport = Transport.BLE): Double {
        if (rssi == Int.MIN_VALUE || rssi == 0) return UNKNOWN_DISTANCE
        val cal = calibrations[transport] ?: return UNKNOWN_DISTANCE
        val clamped = rssi.coerceIn(RSSI_MIN_DBM, RSSI_MAX_DBM).toDouble()
        val exponent = (cal.txPowerAt1m - clamped) / (10.0 * cal.pathLossExponent)
        val d = 10.0.pow(exponent)
        // Numerical guard: NaN/Infinity should never surface into the routing math.
        if (d.isNaN() || d.isInfinite()) return UNKNOWN_DISTANCE
        return d.coerceIn(MIN_DISTANCE_M, MAX_DISTANCE_M)
    }
}
