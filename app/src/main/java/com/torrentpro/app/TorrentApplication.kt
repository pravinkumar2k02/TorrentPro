package com.torrentpro.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class TorrentApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val downloadChannel = NotificationChannel(
                CHANNEL_DOWNLOADS,
                "Torrent Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows ongoing torrent download progress and speeds"
                setShowBadge(false)
            }

            val completedChannel = NotificationChannel(
                CHANNEL_COMPLETED,
                "Download Completed",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies when a torrent download finishes"
                enableVibration(true)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(downloadChannel)
            manager.createNotificationChannel(completedChannel)
        }
    }

    companion object {
        const val CHANNEL_DOWNLOADS = "torrent_downloads"
        const val CHANNEL_COMPLETED = "torrent_completed"
    }
}
