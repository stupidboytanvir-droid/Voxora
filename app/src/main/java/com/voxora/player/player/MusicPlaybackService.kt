package com.voxora.player.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.voxora.player.MainActivity
import com.voxora.player.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

object MusicPlaybackBridge {
    @Volatile
    var controller: MusicPlayerController? = null
}

/**
 * Foreground Media Playback Service for VOXORA's dedicated Local Music Player.
 * Keeps audio playing smoothly in the background and renders a lock-screen / shade
 * media control notification with Previous, Play/Pause, Next, and Close controls,
 * using the unified monochrome VOXORA Play-Wave icon and Sunset Orange accent.
 */
class MusicPlaybackService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var cachedArtUri: String? = null
    private var cachedArtBitmap: Bitmap? = null
    private val defaultLogoBitmap: Bitmap? by lazy {
        runCatching { BitmapFactory.decodeResource(resources, R.drawable.ic_voxora_logo) }.getOrNull()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ctrl = MusicPlaybackBridge.controller
        when (intent?.action) {
            ACTION_PREV -> {
                ctrl?.skipToPrevious()
                updateNotificationAsync()
            }
            ACTION_PLAY_PAUSE -> {
                ctrl?.togglePlayPause()
                updateNotificationAsync()
            }
            ACTION_NEXT -> {
                ctrl?.skipToNext()
                updateNotificationAsync()
            }
            ACTION_CLOSE -> {
                ctrl?.stopAndClear()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val notification = buildNotification(cachedArtBitmap)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                updateNotificationAsync()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun updateNotificationAsync() {
        val track = MusicPlaybackBridge.controller?.state?.value?.currentTrack
        val artUri = track?.albumArtUri
        if (artUri == cachedArtUri) {
            postNotification(cachedArtBitmap)
            return
        }
        cachedArtUri = artUri
        if (artUri.isNullOrBlank()) {
            cachedArtBitmap = null
            postNotification(null)
            return
        }
        serviceScope.launch {
            val bmp = loadAlbumArtBitmap(artUri)
            if (cachedArtUri == artUri) {
                cachedArtBitmap = bmp
                postNotification(bmp)
            }
        }
    }

    private fun postNotification(art: Bitmap?) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(art))
    }

    private fun loadAlbumArtBitmap(uriString: String): Bitmap? {
        return runCatching {
            val uri = Uri.parse(uriString)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val src = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(src) { decoder, _, _ ->
                    decoder.setTargetSize(256, 256)
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
        }.getOrNull()
    }

    private fun buildNotification(albumArt: Bitmap?): Notification {
        val state = MusicPlaybackBridge.controller?.state?.value
        val track = state?.currentTrack
        val isPlaying = state?.isPlaying == true

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPending = PendingIntent.getActivity(
            this,
            200,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevPending = serviceActionPendingIntent(ACTION_PREV, 201)
        val playPausePending = serviceActionPendingIntent(ACTION_PLAY_PAUSE, 202)
        val nextPending = serviceActionPendingIntent(ACTION_NEXT, 203)
        val closePending = serviceActionPendingIntent(ACTION_CLOSE, 204)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_voxora_notif_mono)
            .setColor(0xFFF97316.toInt())
            .setContentTitle(track?.title ?: "VOXORA Music")
            .setContentText(
                if (track != null) "${track.artist} · ${track.album}" else "Playing local audio"
            )
            .setSubText(track?.formatBadge ?: "VOXORA")
            .setContentIntent(contentPending)
            .setOngoing(isPlaying)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(
                android.R.drawable.ic_media_previous,
                "Previous",
                prevPending
            )
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Pause" else "Play",
                playPausePending
            )
            .addAction(
                android.R.drawable.ic_media_next,
                "Next",
                nextPending
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Close",
                closePending
            )

        val largeIcon = albumArt?.takeIf { !it.isRecycled } ?: defaultLogoBitmap?.takeIf { !it.isRecycled }
        if (largeIcon != null) {
            builder.setLargeIcon(largeIcon)
        }

        return builder.build()
    }

    private fun serviceActionPendingIntent(actionName: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, MusicPlaybackService::class.java).apply {
            action = actionName
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.music_playback_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.music_playback_channel_description)
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "voxora_music_playback"
        const val NOTIFICATION_ID = 4501
        const val ACTION_START_OR_UPDATE = "com.voxora.player.action.MUSIC_START_OR_UPDATE"
        const val ACTION_PREV = "com.voxora.player.action.MUSIC_PREV"
        const val ACTION_PLAY_PAUSE = "com.voxora.player.action.MUSIC_PLAY_PAUSE"
        const val ACTION_NEXT = "com.voxora.player.action.MUSIC_NEXT"
        const val ACTION_CLOSE = "com.voxora.player.action.MUSIC_CLOSE"

        fun startOrUpdate(context: Context, controller: MusicPlayerController) {
            MusicPlaybackBridge.controller = controller
            val intent = Intent(context, MusicPlaybackService::class.java).apply {
                action = ACTION_START_OR_UPDATE
            }
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MusicPlaybackService::class.java)
            runCatching { context.stopService(intent) }
        }
    }
}
