package com.voxora.player.data.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.voxora.player.MainActivity
import com.voxora.player.R

class UpdateNotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "voxora_app_updates"
        const val EXTRA_OPEN_UPDATE_DIALOG = "com.voxora.player.EXTRA_OPEN_UPDATE_DIALOG"
        private const val NOTIFICATION_ID_UPDATE_AVAILABLE = 4001
        private const val NOTIFICATION_ID_READY_TO_INSTALL = 4002
    }

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.update_channel_description)
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun showUpdateAvailableNotification(release: AppReleaseInfo) {
        if (!hasNotificationPermission()) return
        ensureChannel()

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_UPDATE_DIALOG, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_UPDATE_AVAILABLE,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val previewNotes = release.releaseNotes
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(3)
            .joinToString("\n")
            .ifBlank { "A new version of VOXORA is ready to download and install." }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_voxora_notification)
            .setColor(0xFFF97316.toInt())
            .setContentTitle("VOXORA v${release.versionName} Update Available")
            .setContentText("Tap to download and install the latest version.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(previewNotes))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_ID_UPDATE_AVAILABLE, notification)
        }
    }

    fun showReadyToInstallNotification(release: AppReleaseInfo) {
        if (!hasNotificationPermission()) return
        ensureChannel()

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_UPDATE_DIALOG, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_READY_TO_INSTALL,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_voxora_notification)
            .setColor(0xFFF97316.toInt())
            .setContentTitle("VOXORA v${release.versionName} Downloaded")
            .setContentText("Tap to install the update now.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_ID_READY_TO_INSTALL, notification)
        }
    }
}
