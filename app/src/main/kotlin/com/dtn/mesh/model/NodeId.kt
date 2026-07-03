package com.dtn.mesh.model

/**
 * Type-safe wrapper for Meshtastic node identifiers.
 * Format: "!aabbccdd" (hex-encoded 32-bit node number).
 */
@JvmInline
value class NodeId(val value: String) {
    companion object {
        val BROADCAST = NodeId("^all")
        val LOCAL = NodeId("^local")

        /**
         * Reserved 32-bit value for the broadcast address on the wire (0xFFFFFFFF).
         * Chosen because it can never collide with a real node number and lets the hub
         * firmware detect a broadcast bundle and fan it out to every connected phone.
         */
        const val BROADCAST_NODE_NUM32: Int = -1 // 0xFFFFFFFF

        /**
         * Convert a raw 32-bit node number to a [NodeId].
         * [BROADCAST_NODE_NUM32] maps back to [BROADCAST] so broadcast destinations
         * round-trip through the self-contained wire header.
         */
        fun fromNodeNum(num: Int): NodeId =
            if (num == BROADCAST_NODE_NUM32) BROADCAST else NodeId("!%08x".format(num))
    }

    /** Extract the numeric node number, or null if this is a special ID (^all, ^local). */
    fun toNodeNum(): Int? {
        if (!value.startsWith("!")) return null
        return value.substring(1).toLongOrNull(16)?.toInt()
    }

    /**
     * Stable 32-bit representation used by the self-contained DTN wire header.
     *
     * - For canonical "!hex" ids, this is the parsed 32-bit node number (round-trips exactly).
     * - For any other id form (e.g. legacy "!p2p_model" or "^local"), a deterministic FNV-1a
     *   hash of the string is used so the id still maps to a stable 32-bit value on the wire.
     *
     * Because the wire carries the 32-bit value, all nodes should adopt the canonical
     * "!%08x" form (see [fromNodeNum]) so identities round-trip across the mesh.
     */
    fun toNodeNum32(): Int = when (value) {
        BROADCAST.value -> BROADCAST_NODE_NUM32
        else -> toNodeNum() ?: fnv1a32(value)
    }

    override fun toString(): String = value

    private fun fnv1a32(s: String): Int {
        var h: Int = FNV_OFFSET_BASIS
        for (c in s) {
            h = h xor (c.code and 0xFF)
            h *= FNV_PRIME
        }
        return h
    }
}

private const val FNV_OFFSET_BASIS: Int = -0x7EE3623B // 0x811C9DC5
private const val FNV_PRIME: Int = 0x01000193
