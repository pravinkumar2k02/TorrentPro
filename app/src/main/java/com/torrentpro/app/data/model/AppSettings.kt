package com.torrentpro.app.data.model

data class AppSettings(
    val downloadDirectory: String,
    val stopSeedingOnComplete: Boolean = true,
    val autoRemoveOnComplete: Boolean = true,
    val enableSequentialVideo: Boolean = true,
    val listenPort: Int = 6881,
    val maxConnections: Int = 60,
    val maxConnectionsPerTorrent: Int = 25,
    val maxDownloadSpeedKbps: Int = 0, // 0 = unlimited
    val maxUploadSpeedKbps: Int = 50, // default 50 KB/s saves battery/data
    val dhtBootstrapNodes: String = "router.bittorrent.com:6881,dht.transmissionbt.com:6881,router.utorrent.com:6881,dht.aelitis.com:6881",
    val enableDht: Boolean = true,
    val enableLsd: Boolean = true,
    val enableUpnp: Boolean = true,
    val onlyOnWifi: Boolean = false,
    val proxyType: String = "None", // None, SOCKS5, HTTP
    val proxyHost: String = "",
    val proxyPort: Int = 0
) {
    fun validate(): String? {
        if (listenPort !in 1..65535) {
            return "Port must be between 1 and 65535"
        }
        if (maxConnections !in 10..500) {
            return "Max connections must be between 10 and 500"
        }
        if (maxConnectionsPerTorrent !in 5..200) {
            return "Max connections per torrent must be between 5 and 200"
        }
        if (maxDownloadSpeedKbps < 0 || maxUploadSpeedKbps < 0) {
            return "Speed limits cannot be negative"
        }
        if (proxyType != "None" && proxyHost.isNotBlank()) {
            if (proxyPort !in 1..65535) {
                return "Proxy port must be between 1 and 65535"
            }
        }
        return null
    }
}
