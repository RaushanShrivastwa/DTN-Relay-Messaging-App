package com.dtn.mesh.ui

import android.Manifest
import android.os.Build

/**
 * Runtime permissions required by the DTN transport layer.
 * Called from [MainActivity] before any connect action.
 */
object PermissionHelper {

    /** All permissions needed for BLE + WiFi + Location. */
    fun requiredPermissions(): Array<String> {
        val perms = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Location required for WiFi Direct peer discovery (all API levels)
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION)

        return perms.toTypedArray()
    }
}
