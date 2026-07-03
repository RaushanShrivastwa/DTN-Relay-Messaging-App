package com.dtn.mesh.routing

import com.dtn.mesh.model.DtnMessage

/**
 * A message ranked for forwarding to a specific peer.
 * Returned by [RoutingStrategy.rankForForwarding] — highest [priority] first.
 */
data class ForwardCandidate(
    val message: DtnMessage,
    /** Higher = forward sooner. Scale is strategy-dependent. */
    val priority: Double,
)
