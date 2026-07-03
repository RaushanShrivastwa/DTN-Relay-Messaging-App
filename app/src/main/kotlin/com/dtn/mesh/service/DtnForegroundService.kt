package com.dtn.mesh.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.dtn.mesh.scheduler.SchedulerConfig
import com.dtn.mesh.scheduler.WorkManagerSetup
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground service keeping the DTN relay alive when the app is backgrounded.
 *
 * Hosts the [DtnOrchestrator] and keeps transports active (BLE advertising/scanning,
 * WiFi Direct discovery, and the LoRa hub socket) across screen-off and task removal.
 *
 * Lifecycle:
 * - Started by the UI when the user taps Connect.
 * - Stopped by the UI on Disconnect, or on task removal if the user hasn't connected.
 */
@AndroidEntryPoint
class DtnForegroundService : Service() {

    companion object {
        private const val TAG = "DtnFGService"
        private const val CHANNEL_ID = "dtn_relay_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, DtnForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DtnForegroundService::class.java))
        }
    }

    @Inject lateinit var orchestrator: DtnOrchestrator
    @Inject lateinit var schedulerConfig: SchedulerConfig

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification("DTN Relay active")

        val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgType)

        orchestrator.start()
        WorkManagerSetup.enqueueForwardingWorker(this, schedulerConfig)
        Log.i(TAG, "Foreground service started")
    }

    override fun onDestroy() {
        Log.i(TAG, "Foreground service stopping")
        orchestrator.stop()
        WorkManagerSetup.cancelForwardingWorker(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "DTN Relay", NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Keeps the DTN store-carry-forward relay running" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DTN Mesh Relay")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
}
