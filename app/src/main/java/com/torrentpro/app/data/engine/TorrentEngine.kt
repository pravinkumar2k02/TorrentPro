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
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class TorrentEngine(private val context: Context) {

    private val sessionManager = SessionManager()
    val streamServer = LocalStreamServer(8828)
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    private val _torrents = MutableStateFlow<List<TorrentItem>>(emptyList())
    val torrents: StateFlow<List<TorrentItem>> = _torrents.asStateFlow()

    private val _completedTorrents = MutableStateFlow<List<TorrentItem>>(emptyList())
    val completedTorrents: StateFlow<List<TorrentItem>> = _completedTorrents.asStateFlow()

    private val torrentItemMap = ConcurrentHashMap<String, TorrentItem>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var appSettings = AppSettings(
        downloadDirectory = StorageHelper.getDefaultDownloadDir()
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

        // Apply connection and thermal optimization settings
        settings.connectionsLimit(appSettings.maxConnections)
        settings.activeDownloads(4)
        settings.activeSeeds(if (appSettings.stopSeedingOnComplete) 0 else 2)
        settings.activeLimit(8)
        settings.setEnableDht(appSettings.enableDht)
        settings.setEnableLsd(appSettings.enableLsd)
        settings.listenInterfaces("0.0.0.0:${appSettings.listenPort}")

        if (appSettings.maxDownloadSpeedKbps > 0) {
            settings.downloadRateLimit(appSettings.maxDownloadSpeedKbps * 1024)
        }
        if (appSettings.maxUploadSpeedKbps > 0) {
            settings.uploadRateLimit(appSettings.maxUploadSpeedKbps * 1024)
        }

        settings.setDhtBootstrapNodes(appSettings.dhtBootstrapNodes)

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
        Log.i(TAG, "TorrentEngine started on port ${appSettings.listenPort}")
    }

    fun stop() {
        mainHandler.removeCallbacks(loopRunnable)
        streamServer.stop()
        if (sessionManager.isRunning) {
            // Save resume data and flush cache before shutting down
            for (infoHash in torrentItemMap.keys.toList()) {
                safeFindHandle(infoHash)?.let { handle ->
                    try {
                        handle.saveResumeData(TorrentHandle.FLUSH_DISK_CACHE)
                    } catch (_: Exception) {}
                }
            }
            sessionManager.stop()
        }
        Log.i(TAG, "TorrentEngine stopped")
    }

    private fun applySettingsPack() {
        if (!sessionManager.isRunning) return
        val pack = SettingsPack()
        pack.connectionsLimit(appSettings.maxConnections)
        pack.listenInterfaces("0.0.0.0:${appSettings.listenPort}")
        if (appSettings.maxDownloadSpeedKbps > 0) {
            pack.downloadRateLimit(appSettings.maxDownloadSpeedKbps * 1024)
        } else {
            pack.downloadRateLimit(0)
        }
        if (appSettings.maxUploadSpeedKbps > 0) {
            pack.uploadRateLimit(appSettings.maxUploadSpeedKbps * 1024)
        }
        pack.activeSeeds(if (appSettings.stopSeedingOnComplete) 0 else 2)
        pack.setEnableDht(appSettings.enableDht)
        pack.setEnableLsd(appSettings.enableLsd)
        pack.setDhtBootstrapNodes(appSettings.dhtBootstrapNodes)
        sessionManager.applySettings(pack)
    }

    fun downloadMagnet(magnetUri: String, destinationPath: String? = null): String? {
        if (!sessionManager.isRunning) start()
        val dest = destinationPath ?: appSettings.downloadDirectory
        val saveDir = File(dest)
        if (!saveDir.exists()) saveDir.mkdirs()

        try {
            val flags = torrent_flags_t()
            sessionManager.download(magnetUri, saveDir, flags)

            val infoHash = extractInfoHashFromMagnet(magnetUri) ?: java.util.UUID.randomUUID().toString().replace("-", "")
            val displayDest = StorageHelper.getHumanReadablePath(context, dest)

            val item = TorrentItem(
                id = infoHash,
                infoHash = infoHash,
                name = extractNameFromMagnet(magnetUri) ?: "Fetching metadata...",
                savePath = dest,
                displayDestination = displayDest,
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

    fun downloadTorrentFile(torrentFileBytes: ByteArray, destinationPath: String? = null): String? {
        if (!sessionManager.isRunning) start()
        val dest = destinationPath ?: appSettings.downloadDirectory
        val saveDir = File(dest)
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

            val displayDest = StorageHelper.getHumanReadablePath(context, dest)

            val item = TorrentItem(
                id = infoHash,
                infoHash = infoHash,
                name = ti.name(),
                totalBytes = ti.totalSize(),
                savePath = dest,
                displayDestination = displayDest,
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
        val handle = safeFindHandle(infoHash)
        if (handle != null) {
            handle.pause()
            try {
                // Flush disk cache to release active file locks for external file managers
                handle.saveResumeData(TorrentHandle.FLUSH_DISK_CACHE)
            } catch (_: Exception) {}
        }
        torrentItemMap[infoHash]?.let {
            torrentItemMap[infoHash] = it.copy(isPaused = true, stateString = "Paused")
            syncLists()
        }
    }

    fun resumeTorrent(infoHash: String) {
        val item = torrentItemMap[infoHash]
        // Verify target directory still exists before resuming
        if (item != null) {
            val dir = File(item.savePath)
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }
        val handle = safeFindHandle(infoHash)
        handle?.resume()
        torrentItemMap[infoHash]?.let {
            torrentItemMap[infoHash] = it.copy(isPaused = false, fileMissing = false, stateString = "Downloading")
            syncLists()
        }
    }

    fun pauseAll() {
        for (infoHash in torrentItemMap.keys.toList()) {
            safeFindHandle(infoHash)?.let { handle ->
                handle.pause()
                try {
                    handle.saveResumeData(TorrentHandle.FLUSH_DISK_CACHE)
                } catch (_: Exception) {}
            }
        }
        for ((k, v) in torrentItemMap) {
            if (!v.isFinished) {
                torrentItemMap[k] = v.copy(isPaused = true, stateString = "Paused")
            }
        }
        syncLists()
    }

    fun resumeAll() {
        for (infoHash in torrentItemMap.keys.toList()) {
            safeFindHandle(infoHash)?.resume()
        }
        for ((k, v) in torrentItemMap) {
            if (!v.isFinished) {
                torrentItemMap[k] = v.copy(isPaused = false, fileMissing = false, stateString = "Downloading")
            }
        }
        syncLists()
    }

    fun removeTorrent(infoHash: String, deleteFiles: Boolean = false) {
        val handle = safeFindHandle(infoHash)
        if (handle != null) {
            try {
                if (deleteFiles) {
                    sessionManager.remove(handle, session_handle.delete_files)
                } else {
                    sessionManager.remove(handle)
                }
            } catch (_: Exception) {}
        }

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
        val handle = safeFindHandle(infoHash)
        if (handle != null) {
            try {
                if (enable) {
                    handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                } else {
                    handle.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                }
            } catch (_: Exception) {}
        }
        torrentItemMap[infoHash]?.let {
            torrentItemMap[infoHash] = it.copy(isSequential = enable)
            syncLists()
        }
    }

    fun prioritizeForStreaming(infoHash: String) {
        val handle = safeFindHandle(infoHash) ?: return
        try {
            handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)

            val ti = handle.torrentFile() ?: return
            val numPieces = ti.numPieces()
            if (numPieces > 0) {
                val firstCount = minOf(15, numPieces)
                for (i in 0 until firstCount) {
                    handle.piecePriority(i, Priority.TOP_PRIORITY)
                }
                val lastStart = maxOf(0, numPieces - 6)
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

        // Stop seeding immediately if configured
        if (appSettings.stopSeedingOnComplete) {
            handle.pause()
            try {
                handle.setUploadLimit(0)
                handle.saveResumeData(TorrentHandle.FLUSH_DISK_CACHE)
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

            if (appSettings.autoRemoveOnComplete) {
                torrentItemMap.remove(infoHash)
                try {
                    sessionManager.remove(handle)
                } catch (_: Exception) {}
            } else {
                torrentItemMap[infoHash] = finishedItem
            }

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
        // Snapshot keys to prevent ConcurrentModificationException
        val keyList = torrentItemMap.keys.toList()

        for (infoHash in keyList) {
            val handle = safeFindHandle(infoHash) ?: continue
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

            // Check if file was moved or deleted externally while in progress
            var fileMissing = false
            if (existing.videoFilePath != null && doneBytes > 1024 * 1024) {
                val f = File(existing.videoFilePath)
                if (!f.exists()) {
                    fileMissing = true
                }
            }

            val stateStr = when {
                fileMissing -> "Partial file removed externally"
                existing.isPaused -> "Paused"
                status.state() == TorrentStatus.State.CHECKING_FILES ||
                        status.state() == TorrentStatus.State.CHECKING_RESUME_DATA -> "Checking files"
                status.state() == TorrentStatus.State.DOWNLOADING_METADATA -> "Fetching metadata"
                status.state() == TorrentStatus.State.DOWNLOADING -> "Downloading"
                status.state() == TorrentStatus.State.FINISHED ||
                        status.state() == TorrentStatus.State.SEEDING -> {
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
                fileMissing = fileMissing,
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

    fun testConnection(callback: (Boolean, String) -> Unit) {
        backgroundExecutor.execute {
            try {
                // Parse first node from bootstrap nodes
                val firstNode = appSettings.dhtBootstrapNodes.split(",").firstOrNull()?.trim() ?: "router.bittorrent.com:6881"
                val host = firstNode.substringBefore(":")
                val port = firstNode.substringAfter(":", "6881").toIntOrNull() ?: 6881

                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 4000)
                }
                mainHandler.post { callback(true, "Successfully connected to $host:$port") }
            } catch (e: Exception) {
                mainHandler.post { callback(false, "Connection check: ${e.localizedMessage ?: "Failed to reach bootstrap node"}") }
            }
        }
    }

    private fun safeFindHandle(infoHash: String): TorrentHandle? {
        if (infoHash.length != 40 || !infoHash.all { it in "0123456789abcdefABCDEF" }) {
            return null
        }
        return try {
            val hash = Sha1Hash.parseHex(infoHash)
            sessionManager.find(hash)
        } catch (_: Exception) {
            null
        }
    }

    private fun extractInfoHashFromMagnet(magnet: String): String? {
        val prefix = "xt=urn:btih:"
        val idx = magnet.indexOf(prefix)
        if (idx == -1) return null
        val sub = magnet.substring(idx + prefix.length)
        val end = sub.indexOf('&')
        val raw = if (end != -1) sub.substring(0, end) else sub
        return if (raw.length == 40 && raw.all { it in "0123456789abcdefABCDEF" }) raw else null
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
