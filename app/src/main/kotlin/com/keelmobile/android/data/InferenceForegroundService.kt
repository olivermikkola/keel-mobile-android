package com.keelmobile.android.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.keelmobile.android.KeelApplication
import com.keelmobile.android.MainActivity

/**
 * Android Foreground Service that keeps local model replies running cleanly
 * after the user switches apps or locks the screen — with a live Stop action
 * right in the notification shade (no iOS 30-second background limit).
 */
class InferenceForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CANCEL_INFERENCE -> {
                (application as? KeelApplication)?.controller?.stopGeneration()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val model = intent?.getStringExtra(EXTRA_MODEL_NAME) ?: "Local model"
                val status = intent?.getStringExtra(EXTRA_STATUS) ?: "Working locally…"
                startForeground(NOTIFICATION_ID, buildNotification(model, status))
                return START_NOT_STICKY
            }
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Local AI Replies", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun buildNotification(modelName: String, status: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, InferenceForegroundService::class.java).apply {
            action = ACTION_CANCEL_INFERENCE
        }
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Keel · $modelName")
            .setContentText(status)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPi)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "keel_local_inference"
        const val NOTIFICATION_ID = 1002
        const val ACTION_UPDATE = "com.keelmobile.android.INFERENCE_UPDATE"
        const val ACTION_STOP_SERVICE = "com.keelmobile.android.INFERENCE_STOP_SERVICE"
        const val ACTION_CANCEL_INFERENCE = "com.keelmobile.android.INFERENCE_CANCEL"
        const val EXTRA_MODEL_NAME = "model_name"
        const val EXTRA_STATUS = "status"

        fun update(context: Context, modelName: String, status: String, running: Boolean) {
            val intent = Intent(context, InferenceForegroundService::class.java).apply {
                action = if (running) ACTION_UPDATE else ACTION_STOP_SERVICE
                putExtra(EXTRA_MODEL_NAME, modelName)
                putExtra(EXTRA_STATUS, status)
            }
            runCatching {
                if (!running) context.startService(intent)
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            }
        }
    }
}
