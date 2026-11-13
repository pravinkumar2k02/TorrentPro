package com.torrentpro.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.torrentpro.app.data.engine.StorageHelper
import com.torrentpro.app.data.engine.TorrentEngine
import com.torrentpro.app.data.model.AppSettings
import com.torrentpro.app.data.model.TorrentItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class TorrentRepository private constructor(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val engine: TorrentEngine = TorrentEngine(context)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        engine.updateSettings(_settings.value)
        loadSavedCompletedTorrents()
    }

    private fun loadSettings(): AppSettings {
        val defaultDir = StorageHelper.getDefaultDownloadDir()
        val dir = prefs.getString(KEY_DOWNLOAD_DIR, defaultDir) ?: defaultDir
        val stopSeeding = prefs.getBoolean(KEY_STOP_SEEDING, true)
        val autoRemove = prefs.getBoolean(KEY_AUTO_REMOVE, true)
        val sequential = prefs.getBoolean(KEY_SEQUENTIAL, true)
        val listenPort = prefs.getInt(KEY_LISTEN_PORT, 6881)
        val maxConn = prefs.getInt(KEY_MAX_CONN, 60)
        val maxConnPerTorrent = prefs.getInt(KEY_MAX_CONN_PER_TORRENT, 25)
        val maxDl = prefs.getInt(KEY_MAX_DL_SPEED, 0)
        val maxUl = prefs.getInt(KEY_MAX_UL_SPEED, 50)
        val dhtNodes = prefs.getString(KEY_DHT_NODES, "router.bittorrent.com:6881,dht.transmissionbt.com:6881,router.utorrent.com:6881,dht.aelitis.com:6881") ?: ""
        val enableDht = prefs.getBoolean(KEY_ENABLE_DHT, true)
        val enableLsd = prefs.getBoolean(KEY_ENABLE_LSD, true)
        val onlyWifi = prefs.getBoolean(KEY_ONLY_WIFI, false)
        val proxyType = prefs.getString(KEY_PROXY_TYPE, "None") ?: "None"
        val proxyHost = prefs.getString(KEY_PROXY_HOST, "") ?: ""
        val proxyPort = prefs.getInt(KEY_PROXY_PORT, 0)

        return AppSettings(
            downloadDirectory = dir,
            stopSeedingOnComplete = stopSeeding,
            autoRemoveOnComplete = autoRemove,
            enableSequentialVideo = sequential,
            listenPort = listenPort,
            maxConnections = maxConn,
            maxConnectionsPerTorrent = maxConnPerTorrent,
            maxDownloadSpeedKbps = maxDl,
            maxUploadSpeedKbps = maxUl,
            dhtBootstrapNodes = dhtNodes,
            enableDht = enableDht,
            enableLsd = enableLsd,
            onlyOnWifi = onlyWifi,
            proxyType = proxyType,
            proxyHost = proxyHost,
            proxyPort = proxyPort
        )
    }

    fun updateSettings(newSettings: AppSettings) {
        _settings.value = newSettings
        engine.updateSettings(newSettings)

        prefs.edit().apply {
            putString(KEY_DOWNLOAD_DIR, newSettings.downloadDirectory)
            putBoolean(KEY_STOP_SEEDING, newSettings.stopSeedingOnComplete)
            putBoolean(KEY_AUTO_REMOVE, newSettings.autoRemoveOnComplete)
            putBoolean(KEY_SEQUENTIAL, newSettings.enableSequentialVideo)
            putInt(KEY_LISTEN_PORT, newSettings.listenPort)
            putInt(KEY_MAX_CONN, newSettings.maxConnections)
            putInt(KEY_MAX_CONN_PER_TORRENT, newSettings.maxConnectionsPerTorrent)
            putInt(KEY_MAX_DL_SPEED, newSettings.maxDownloadSpeedKbps)
            putInt(KEY_MAX_UL_SPEED, newSettings.maxUploadSpeedKbps)
            putString(KEY_DHT_NODES, newSettings.dhtBootstrapNodes)
            putBoolean(KEY_ENABLE_DHT, newSettings.enableDht)
            putBoolean(KEY_ENABLE_LSD, newSettings.enableLsd)
            putBoolean(KEY_ONLY_WIFI, newSettings.onlyOnWifi)
            putString(KEY_PROXY_TYPE, newSettings.proxyType)
            putString(KEY_PROXY_HOST, newSettings.proxyHost)
            putInt(KEY_PROXY_PORT, newSettings.proxyPort)
            apply()
        }
    }

    fun testConnection(callback: (Boolean, String) -> Unit) {
        engine.testConnection(callback)
    }

    fun saveCompletedTorrent(item: TorrentItem) {
        try {
            val jsonArrayStr = prefs.getString(KEY_COMPLETED_LIST, "[]") ?: "[]"
            val array = JSONArray(jsonArrayStr)

            var exists = false
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.optString("infoHash") == item.infoHash) {
                    exists = true
                    break
                }
            }

            if (!exists) {
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("infoHash", item.infoHash)
                    put("name", item.name)
                    put("totalBytes", item.totalBytes)
                    put("downloadedBytes", item.totalBytes)
                    put("savePath", item.savePath)
                    put("displayDestination", item.displayDestination)
                    put("isVideo", item.isVideo)
                    put("videoFilePath", item.videoFilePath ?: "")
                    put("completedTime", item.completedTime ?: System.currentTimeMillis())
                }
                array.put(0, obj)
                prefs.edit().putString(KEY_COMPLETED_LIST, array.toString()).apply()
            }
        } catch (_: Exception) {}
    }

    private fun loadSavedCompletedTorrents() {
        try {
            val jsonArrayStr = prefs.getString(KEY_COMPLETED_LIST, "[]") ?: "[]"
            val array = JSONArray(jsonArrayStr)
            val list = mutableListOf<TorrentItem>()

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val sPath = obj.optString("savePath")
                val name = obj.getString("name")
                val file = File(sPath, name)
                val missing = !file.exists()

                val item = TorrentItem(
                    id = obj.getString("id"),
                    infoHash = obj.getString("infoHash"),
                    name = name,
                    totalBytes = obj.optLong("totalBytes"),
                    downloadedBytes = obj.optLong("downloadedBytes"),
                    progress = 1.0f,
                    isFinished = true,
                    isPaused = true,
                    savePath = sPath,
                    displayDestination = obj.optString("displayDestination", StorageHelper.getHumanReadablePath(context, sPath)),
                    isVideo = obj.optBoolean("isVideo"),
                    videoFilePath = obj.optString("videoFilePath").ifEmpty { null },
                    stateString = if (missing) "File moved or deleted externally" else "Completed",
                    fileMissing = missing,
                    completedTime = obj.optLong("completedTime")
                )
                list.add(item)
            }

            if (list.isNotEmpty()) {
                val current = engine.completedTorrents.value.toMutableList()
                for (item in list) {
                    if (current.none { it.infoHash == item.infoHash }) {
                        current.add(item)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun removeCompletedTorrent(infoHash: String, deleteFiles: Boolean = false) {
        try {
            val jsonArrayStr = prefs.getString(KEY_COMPLETED_LIST, "[]") ?: "[]"
            val array = JSONArray(jsonArrayStr)
            val newArray = JSONArray()

            var itemToDelete: JSONObject? = null
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.optString("infoHash") == infoHash) {
                    itemToDelete = obj
                } else {
                    newArray.put(obj)
                }
            }

            prefs.edit().putString(KEY_COMPLETED_LIST, newArray.toString()).apply()

            if (deleteFiles && itemToDelete != null) {
                val savePath = itemToDelete.optString("savePath")
                val name = itemToDelete.optString("name")
                val file = File(savePath, name)
                if (file.exists()) file.deleteRecursively()
            }
        } catch (_: Exception) {}
    }

    companion object {
        private const val PREFS_NAME = "torrent_pro_prefs"
        private const val KEY_DOWNLOAD_DIR = "download_dir"
        private const val KEY_STOP_SEEDING = "stop_seeding"
        private const val KEY_AUTO_REMOVE = "auto_remove"
        private const val KEY_SEQUENTIAL = "sequential"
        private const val KEY_LISTEN_PORT = "listen_port"
        private const val KEY_MAX_CONN = "max_connections"
        private const val KEY_MAX_CONN_PER_TORRENT = "max_conn_per_torrent"
        private const val KEY_MAX_DL_SPEED = "max_dl_speed"
        private const val KEY_MAX_UL_SPEED = "max_ul_speed"
        private const val KEY_DHT_NODES = "dht_nodes"
        private const val KEY_ENABLE_DHT = "enable_dht"
        private const val KEY_ENABLE_LSD = "enable_lsd"
        private const val KEY_ONLY_WIFI = "only_wifi"
        private const val KEY_PROXY_TYPE = "proxy_type"
        private const val KEY_PROXY_HOST = "proxy_host"
        private const val KEY_PROXY_PORT = "proxy_port"
        private const val KEY_COMPLETED_LIST = "completed_list"

        @Volatile
        private var INSTANCE: TorrentRepository? = null

        fun getInstance(context: Context): TorrentRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TorrentRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
