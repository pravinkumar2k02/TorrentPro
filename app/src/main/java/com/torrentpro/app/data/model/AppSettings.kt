package com.torrentpro.app.data.model

data class AppSettings(
    val downloadDirectory: String,
    val stopSeedingOnComplete: Boolean = true,
    val autoRemoveOnComplete: Boolean = true,
    val enableSequentialVideo: Boolean = true,
    val maxConnections: Int = 60,
    val maxConnectionsPerTorrent: Int = 25,
    val maxDownloadSpeedKbps: Int = 0, // 0 = unlimited
    val maxUploadSpeedKbps: Int = 100, // limited to protect mobile data/battery
    val onlyOnWifi: Boolean = false
)
