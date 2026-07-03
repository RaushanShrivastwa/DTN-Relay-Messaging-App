package com.dtn.mesh.routing

/**
 * Opaque per-strategy routing summary exchanged between peers on encounter.
 * Serialised into the DTN wire payload for ROUTING_SUMMARY messages.
 *
 * Receivers ignore summaries with a mismatched [strategyTag] — this allows
 * mixed-strategy networks to coexist without crashing.
 */
data class RoutingSummary(
    /** Strategy identifier: "PROPHET" or "MAXPROP". */
    val strategyTag: String,
    /** Strategy-specific serialised state (e.g. P-vector for PRoPHET). */
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RoutingSummary) return false
        return strategyTag == other.strategyTag && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = strategyTag.hashCode() * 31 + payload.contentHashCode()
}
