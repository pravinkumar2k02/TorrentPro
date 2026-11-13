package com.torrentpro.app.data.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.torrentpro.app.data.model.AppSettings
import com.torrentpro.app.data.model.TorrentItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.MetadataReceivedAlert
import org.libtorrent4j.alerts.TorrentFinishedAlert
import org.libtorrent4j.swig.session_handle
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class TorrentEngine(private val context: Context) {

    private val sessionManager = SessionManager()
    val streamServer = LocalStreamServer(8828)

    private val _torrents = MutableStateFlow<List<TorrentItem>>(emptyList())
    val torrents: StateFlow<List<TorrentItem>> = _torrents.asStateFlow()

    private val _completedTorrents = MutableStateFlow<List<TorrentItem>>(emptyList())
    val completedTorrents: StateFlow<List<TorrentItem>> = _completedTorrents.asStateFlow()

    private val torrentItemMap = ConcurrentHashMap<String, TorrentItem>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var appSettings = AppSettings(
        downloadDirectory = File(context.getExternalFilesDir(null), "Downloads").absolutePath
    )

    private val loopRunnable = object : Runnable {
        override fun run() {
            updateTorrentStatuses()
            mainHandler.postDelayed(this, 1000)
        }
    }

    var onTorrentFinishedListener: ((TorrentItem) -> Unit)? = null

    fun updateSettings(newSettings: AppSettings) {
        this.appSettings = newSettings
        applySettingsPack()
    }

    fun start() {
        if (sessionManager.isRunning) return

        val params = SessionParams()
        val settings = params.settings

        // Thermal and battery optimizations for mobile (prevent phone overheating)
        settings.connectionsLimit(appSettings.maxConnections)
        settings.activeDownloads(4)
        settings.activeSeeds(if (appSettings.stopSeedingOnComplete) 0 else 2)
        settings.activeLimit(6)
        settings.setEnableDht(true)
        settings.setEnableLsd(true)

        if (appSettings.maxDownloadSpeedKbps > 0) {
            settings.downloadRateLimit(appSettings.maxDownloadSpeedKbps * 1024)
        }
        if (appSettings.maxUploadSpeedKbps > 0) {
            settings.uploadRateLimit(appSettings.maxUploadSpeedKbps * 1024)
        }

        settings.setDhtBootstrapNodes(
            "router.bittorrent.com:6881,dht.transmissionbt.com:6881,router.utorrent.com:6881,dht.aelitis.com:6881"
        )

        sessionManager.addListener(object : AlertListener {
            override fun types(): IntArray? = null

            override fun alert(alert: Alert<*>) {
                when (alert.type()) {
                    AlertType.TORRENT_FINISHED -> {
                        val finishedAlert = alert as? TorrentFinishedAlert
                        val handle = finishedAlert?.handle()
                        if (handle != null) {
                            handleTorrentFinished(handle)
                        }
                    }
                    AlertType.METADATA_RECEIVED -> {
                        val metaAlert = alert as? MetadataReceivedAlert
                        val handle = metaAlert?.handle()
                        if (handle != null) {
                            handleMetadataReceived(handle)
                        }
                    }
                    else -> {}
                }
            }
        })

        sessionManager.start(params)
        streamServer.start()
        mainHandler.post(loopRunnable)
        Log.i(TAG, "TorrentEngine and StreamServer started successfully")
    }

    fun stop() {
        mainHandler.removeCallbacks(loopRunnable)
        streamServer.stop()
        if (sessionManager.isRunning) {
            sessionManager.stop()
        }
        Log.i(TAG, "TorrentEngine stopped")
    }

    private fun applySettingsPack() {
        if (!sessionManager.isRunning) return
        val pack = SettingsPack()
        pack.connectionsLimit(appSettings.maxConnections)
        if (appSettings.maxDownloadSpeedKbps > 0) {
            pack.downloadRateLimit(appSettings.maxDownloadSpeedKbps * 1024)
        } else {
            pack.downloadRateLimit(0)
        }
        if (appSettings.maxUploadSpeedKbps > 0) {
            pack.uploadRateLimit(appSettings.maxUploadSpeedKbps * 1024)
        }
        pack.activeSeeds(if (appSettings.stopSeedingOnComplete) 0 else 2)
        sessionManager.applySettings(pack)
    }

    fun downloadMagnet(magnetUri: String, saveDir: File = File(appSettings.downloadDirectory)): String? {
        if (!sessionManager.isRunning) start()
        if (!saveDir.exists()) saveDir.mkdirs()

        try {
            val flags = torrent_flags_t()
            sessionManager.download(magnetUri, saveDir, flags)

            val infoHash = extractInfoHashFromMagnet(magnetUri) ?: java.util.UUID.randomUUID().toString().replace("-", "")
            val item = TorrentItem(
                id = infoHash,
                infoHash = infoHash,
                name = extractNameFromMagnet(magnetUri) ?: "Fetching metadata...",
                savePath = saveDir.absolutePath,
                stateString = "Fetching metadata",
                isSequential = appSettings.enableSequentialVideo
            )
            torrentItemMap[infoHash] = item
            syncLists()
            return infoHash
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download magnet", e)
            return null
        }
    }

    fun downloadTorrentFile(torrentFileBytes: ByteArray, saveDir: File = File(appSettings.downloadDirectory)): String? {
        if (!sessionManager.isRunning) start()
        if (!saveDir.exists()) saveDir.mkdirs()

        try {
            val ti = TorrentInfo(torrentFileBytes)
            val infoHash = ti.infoHash().toHex()
            sessionManager.download(ti, saveDir)

            val handle = sessionManager.find(ti.infoHash())
            if (handle != null && appSettings.enableSequentialVideo) {
                handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
            }

            var isVideo = false
            var videoPath: String? = null
            val numFiles = ti.numFiles()
            val files = ti.files()
            for (i in 0 until numFiles) {
                val fPath = files.filePath(i)
                if (TorrentItem.isVideoFile(fPath)) {
                    isVideo = true
                    videoPath = File(saveDir, fPath).absolutePath
                    break
                }
            }

            val item = TorrentItem(
                id = infoHash,
                infoHash = infoHash,
                name = ti.name(),
                totalBytes = ti.totalSize(),
                savePath = saveDir.absolutePath,
                isVideo = isVideo,
                videoFilePath = videoPath,
                isSequential = true,
                stateString = "Downloading"
            )
            torrentItemMap[infoHash] = item
            syncLists()
            return infoHash
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download torrent file", e)
            return null
        }
    }

    fun pauseTorrent(infoHash: String) {
        val hash = Sha1Hash.parseHex(infoHash)
        val handle = sessionManager.find(hash)
        handle?.pause()
        torrentItemMap[infoHash]?.let {
            torrentItemMap[infoHash] = it.copy(isPaused = true, stateString = "Paused")
            syncLists()
        }
    }

    fun resumeTorrent(infoHash: String) {
        val hash = Sha1Hash.parseHex(infoHash)
        val handle = sessionManager.find(hash)
        handle?.resume()
        torrentItemMap[infoHash]?.let {
            torrentItemMap[infoHash] = it.copy(isPaused = false, stateString = "Downloading")
            syncLists()
        }
    }

    fun pauseAll() {
        for (infoHash in torrentItemMap.keys) {
            val hash = Sha1Hash.parseHex(infoHash)
            sessionManager.find(hash)?.pause()
        }
        for ((k, v) in torrentItemMap) {
            if (!v.isFinished) {
                torrentItemMap[k] = v.copy(isPaused = true, stateString = "Paused")
            }
        }
        syncLists()
    }

    fun resumeAll() {
        for (infoHash in torrentItemMap.keys) {
            val hash = Sha1Hash.parseHex(infoHash)
            sessionManager.find(hash)?.resume()
        }
        for ((k, v) in torrentItemMap) {
            if (!v.isFinished) {
                torrentItemMap[k] = v.copy(isPaused = false, stateString = "Downloading")
            }
        }
        syncLists()
    }

    fun removeTorrent(infoHash: String, deleteFiles: Boolean = false) {
        try {
            val hash = Sha1Hash.parseHex(infoHash)
            val handle = sessionManager.find(hash)
            if (handle != null) {
                if (deleteFiles) {
                    sessionManager.remove(handle, session_handle.delete_files)
                } else {
                    sessionManager.remove(handle)
                }
            }
        } catch (_: Exception) {}

        val item = torrentItemMap.remove(infoHash)
        if (deleteFiles && item != null) {
            try {
                val dir = File(item.savePath)
                val target = File(dir, item.name)
                if (target.exists()) target.deleteRecursively()
            } catch (_: Exception) {}
        }
        syncLists()
    }

    fun setSequentialDownload(infoHash: String, enable: Boolean) {
        try {
            val hash = Sha1Hash.parseHex(infoHash)
            val handle = sessionManager.find(hash)
            if (handle != null) {
                if (enable) {
                    handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                } else {
                    handle.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                }
            }
            torrentItemMap[infoHash]?.let {
                torrentItemMap[infoHash] = it.copy(isSequential = enable)
                syncLists()
            }
        } catch (_: Exception) {}
    }

    fun prioritizeForStreaming(infoHash: String) {
        try {
            val hash = Sha1Hash.parseHex(infoHash)
            val handle = sessionManager.find(hash) ?: return
            handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)

            val ti = handle.torrentFile() ?: return
            val numPieces = ti.numPieces()
            if (numPieces > 0) {
                val firstCount = minOf(10, numPieces)
                for (i in 0 until firstCount) {
                    handle.piecePriority(i, Priority.TOP_PRIORITY)
                }
                val lastStart = maxOf(0, numPieces - 5)
                for (i in lastStart until numPieces) {
                    handle.piecePriority(i, Priority.TOP_PRIORITY)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error prioritizing video pieces", e)
        }
    }

    private fun handleMetadataReceived(handle: TorrentHandle) {
        val ti = handle.torrentFile() ?: return
        val infoHash = handle.infoHash().toHex()
        val existing = torrentItemMap[infoHash] ?: return

        var isVideo = false
        var videoPath: String? = null
        val numFiles = ti.numFiles()
        val files = ti.files()
        for (i in 0 until numFiles) {
            val fPath = files.filePath(i)
            if (TorrentItem.isVideoFile(fPath)) {
                isVideo = true
                videoPath = File(existing.savePath, fPath).absolutePath
                break
            }
        }

        if (isVideo && appSettings.enableSequentialVideo) {
            handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
            prioritizeForStreaming(infoHash)
        }

        val updated = existing.copy(
            name = ti.name(),
            totalBytes = ti.totalSize(),
            isVideo = isVideo,
            videoFilePath = videoPath,
            stateString = "Downloading"
        )
        torrentItemMap[infoHash] = updated
        syncLists()
    }

    private fun handleTorrentFinished(handle: TorrentHandle) {
        val infoHash = handle.infoHash().toHex()
        Log.i(TAG, "Torrent finished: $infoHash")

        // Immediately stop seeding as specifically requested:
        // "once download complete the seeding should stop. and removed from the torrent list."
        if (appSettings.stopSeedingOnComplete) {
            handle.pause()
            try {
                handle.setUploadLimit(0)
            } catch (_: Exception) {}
        }

        val existing = torrentItemMap[infoHash]
        if (existing != null) {
            val finishedItem = existing.copy(
                progress = 1.0f,
                downloadedBytes = existing.totalBytes,
                downloadSpeed = 0,
                uploadSpeed = 0,
                isFinished = true,
                isPaused = true,
                etaSeconds = 0,
                stateString = "Completed",
                completedTime = System.currentTimeMillis()
            )

            // Auto-remove from active torrent list
            if (appSettings.autoRemoveOnComplete) {
                torrentItemMap.remove(infoHash)
                try {
                    sessionManager.remove(handle)
                } catch (_: Exception) {}
            } else {
                torrentItemMap[infoHash] = finishedItem
            }

            // Save to completed history
            val currentCompleted = _completedTorrents.value.toMutableList()
            if (currentCompleted.none { it.infoHash == infoHash }) {
                currentCompleted.add(0, finishedItem)
                _completedTorrents.value = currentCompleted
            }

            syncLists()
            onTorrentFinishedListener?.invoke(finishedItem)
        }
    }

    private fun updateTorrentStatuses() {
        if (!sessionManager.isRunning) return

        var updated = false

        for (infoHash in torrentItemMap.keys) {
            val hash = Sha1Hash.parseHex(infoHash)
            val handle = sessionManager.find(hash) ?: continue
            if (!handle.isValid) continue

            val status = handle.status()
            val existing = torrentItemMap[infoHash] ?: continue

            val isFinished = status.isFinished
            if (isFinished && !existing.isFinished) {
                handleTorrentFinished(handle)
                updated = true
                continue
            }

            val progress = status.progress()
            val dlSpeed = status.downloadRate().toLong()
            val ulSpeed = status.uploadRate().toLong()
            val totalBytes = status.totalWanted()
            val doneBytes = status.totalDone()
            val peers = status.numPeers()
            val seeds = status.numSeeds()

            val remainingBytes = maxOf(0L, totalBytes - doneBytes)
            val eta = if (dlSpeed > 0 && remainingBytes > 0) remainingBytes / dlSpeed else -1L

            val stateStr = when (status.state()) {
                TorrentStatus.State.CHECKING_FILES, TorrentStatus.State.CHECKING_RESUME_DATA -> "Checking files"
                TorrentStatus.State.DOWNLOADING_METADATA -> "Fetching metadata"
                TorrentStatus.State.DOWNLOADING -> if (existing.isPaused) "Paused" else "Downloading"
                TorrentStatus.State.FINISHED, TorrentStatus.State.SEEDING -> {
                    if (appSettings.stopSeedingOnComplete) "Completed" else "Seeding"
                }
                else -> "Active"
            }

            var vPath = existing.videoFilePath
            var isVid = existing.isVideo
            if (vPath == null) {
                val ti = handle.torrentFile()
                if (ti != null) {
                    val numFiles = ti.numFiles()
                    val files = ti.files()
                    for (i in 0 until numFiles) {
                        val p = files.filePath(i)
                        if (TorrentItem.isVideoFile(p)) {
                            isVid = true
                            vPath = File(existing.savePath, p).absolutePath
                            break
                        }
                    }
                }
            }

            torrentItemMap[infoHash] = existing.copy(
                name = if (existing.name == "Fetching metadata..." && handle.torrentFile() != null)
                    handle.torrentFile()!!.name() else existing.name,
                progress = progress,
                downloadedBytes = doneBytes,
                totalBytes = if (totalBytes > 0) totalBytes else existing.totalBytes,
                downloadSpeed = if (existing.isPaused) 0 else dlSpeed,
                uploadSpeed = if (existing.isPaused) 0 else ulSpeed,
                peers = peers,
                seeds = seeds,
                etaSeconds = eta,
                stateString = stateStr,
                isVideo = isVid,
                videoFilePath = vPath
            )
            updated = true
        }

        if (updated) {
            syncLists()
        }
    }

    private fun syncLists() {
        _torrents.value = torrentItemMap.values.toList().sortedByDescending { it.addedTime }
    }

    private fun extractInfoHashFromMagnet(magnet: String): String? {
        val prefix = "xt=urn:btih:"
        val idx = magnet.indexOf(prefix)
        if (idx == -1) return null
        val sub = magnet.substring(idx + prefix.length)
        val end = sub.indexOf('&')
        return if (end != -1) sub.substring(0, end) else sub
    }

    private fun extractNameFromMagnet(magnet: String): String? {
        val prefix = "dn="
        val idx = magnet.indexOf(prefix)
        if (idx == -1) return null
        val sub = magnet.substring(idx + prefix.length)
        val end = sub.indexOf('&')
        val raw = if (end != -1) sub.substring(0, end) else sub
        return try {
            java.net.URLDecoder.decode(raw, "UTF-8")
        } catch (_: Exception) {
            raw
        }
    }

    companion object {
        private const val TAG = "TorrentEngine"
    }
}
