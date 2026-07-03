package com.dtn.mesh.model

/**
 * The four possible actions the Double Q-Learning engine can recommend
 * for a given (message, peer, state) decision point.
 */
enum class ForwardingAction {
    /** Keep the message in our buffer; do not transmit now. */
    STORE,

    /** Transmit the message to the candidate peer immediately. */
    FORWARD,

    /** Defer the decision — re-evaluate on the next scheduler cycle. */
    WAIT,

    /** Permanently discard the message from our buffer. */
    DROP,
}
