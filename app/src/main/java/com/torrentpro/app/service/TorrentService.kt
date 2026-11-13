package com.torrentpro.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.torrentpro.app.R
import com.torrentpro.app.TorrentApplication
import com.torrentpro.app.data.model.TorrentItem
import com.torrentpro.app.data.repository.TorrentRepository
import com.torrentpro.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TorrentService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repository: TorrentRepository
    private var wakeLock: PowerManager.WakeLock? = null
    private var isForeground = false

    override fun onCreate() {
        super.onCreate()
        repository = TorrentRepository.getInstance(this)
        repository.engine.start()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TorrentPro::DownloadWakeLock")

        repository.engine.onTorrentFinishedListener = { finishedItem ->
            repository.saveCompletedTorrent(finishedItem)
            showCompletionNotification(finishedItem)
        }

        observeTorrents()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_ALL -> repository.engine.pauseAll()
            ACTION_RESUME_ALL -> repository.engine.resumeAll()
            ACTION_STOP_SERVICE -> {
                stopForegroundService()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun observeTorrents() {
        serviceScope.launch {
            repository.engine.torrents.collect { list ->
                val activeList = list.filter { !it.isPaused && !it.isFinished }
                if (activeList.isNotEmpty()) {
                    acquireWakeLock()
                    updateNotification(list)
                } else {
                    releaseWakeLock()
                    if (list.isNotEmpty()) {
                        updateNotification(list)
                    } else if (isForeground) {
                        stopForegroundService()
                    }
                }
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(3 * 60 * 60 * 1000L) // Max 3 hours safeguard
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    private fun updateNotification(list: List<TorrentItem>) {
        val totalDlSpeed = list.sumOf { it.downloadSpeed }
        val totalUlSpeed = list.sumOf { it.uploadSpeed }
        val activeCount = list.count { !it.isPaused && !it.isFinished }

        val title = if (activeCount > 0) {
            "Downloading ($activeCount active)"
        } else {
            "TorrentPro (Paused)"
        }

        val contentText = "↓ ${TorrentItem.formatBytes(totalDlSpeed)}/s   ↑ ${TorrentItem.formatBytes(totalUlSpeed)}/s"

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseAllIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TorrentService::class.java).apply { action = ACTION_PAUSE_ALL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val resumeAllIntent = PendingIntent.getService(
            this, 2,
            Intent(this, TorrentService::class.java).apply { action = ACTION_RESUME_ALL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, TorrentApplication.CHANNEL_DOWNLOADS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(contentText)
            .setContentIntent(openIntent)
            .setOngoing(activeCount > 0)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "Pause All", pauseAllIntent)
            .addAction(android.R.drawable.ic_media_play, "Resume All", resumeAllIntent)
            .build()

        if (!isForeground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
        } else {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun showCompletionNotification(item: TorrentItem) {
        val openIntent = PendingIntent.getActivity(
            this, item.infoHash.hashCode(),
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, TorrentApplication.CHANNEL_COMPLETED)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download Complete")
            .setContentText("${item.name} has finished downloading. Seeding stopped.")
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(item.infoHash.hashCode(), notification)
    }

    private fun stopForegroundService() {
        releaseWakeLock()
        isForeground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_PAUSE_ALL = "com.torrentpro.app.ACTION_PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.torrentpro.app.ACTION_RESUME_ALL"
        const val ACTION_STOP_SERVICE = "com.torrentpro.app.ACTION_STOP_SERVICE"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, TorrentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
