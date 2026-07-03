package com.dtn.mesh.model

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides a single, stable [NodeId] for this app install, shared across every transport
 * (LoRa hub, BLE, WiFi Direct). Independent of Meshtastic — the id is app-generated.
 *
 * The id is derived deterministically from the device's ANDROID_ID (falling back to the build
 * fingerprint) and expressed in canonical `!%08x` form so it round-trips through the
 * self-contained wire header ([DtnWireCodec]).
 */
@Singleton
class LocalNodeIdentity @Inject constructor(
    @ApplicationContext context: Context,
) {
    /** This device's stable DTN node id, e.g. "!3fa27c10". */
    val nodeId: NodeId = deriveStableNodeId(context)

    /** The 32-bit numeric form written on the wire. */
    val nodeNum32: Int get() = nodeId.toNodeNum32()

    @SuppressLint("HardwareIds")
    private fun deriveStableNodeId(context: Context): NodeId {
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()
        val seed = if (androidId.isNotBlank()) "aid:$androidId" else "fp:${Build.FINGERPRINT}"
        // Reuse NodeId's FNV-1a mapping on a non-hex seed to get a stable 32-bit value.
        return NodeId.fromNodeNum(NodeId(seed).toNodeNum32())
    }
}
