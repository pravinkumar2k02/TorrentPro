package com.torrentpro.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.torrentpro.app.data.engine.StorageHelper
import com.torrentpro.app.data.model.AppSettings
import com.torrentpro.app.data.model.TorrentItem
import com.torrentpro.app.data.repository.TorrentRepository
import com.torrentpro.app.service.TorrentService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File

class TorrentViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TorrentRepository.getInstance(application)

    val torrents: StateFlow<List<TorrentItem>> = repository.engine.torrents
    val completedTorrents: StateFlow<List<TorrentItem>> = repository.engine.completedTorrents
    val settings: StateFlow<AppSettings> = repository.settings

    val totalDownloadSpeed: StateFlow<Long> = torrents.map { list ->
        list.sumOf { it.downloadSpeed }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val totalUploadSpeed: StateFlow<Long> = torrents.map { list ->
        list.sumOf { it.uploadSpeed }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val activeCount: StateFlow<Int> = torrents.map { list ->
        list.count { !it.isPaused && !it.isFinished }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun addMagnet(magnetUri: String, destinationPath: String? = null) {
        val dest = destinationPath ?: settings.value.downloadDirectory
        repository.engine.downloadMagnet(magnetUri, dest)
        TorrentService.start(getApplication())
    }

    fun addTorrentFile(bytes: ByteArray, destinationPath: String? = null) {
        val dest = destinationPath ?: settings.value.downloadDirectory
        repository.engine.downloadTorrentFile(bytes, dest)
        TorrentService.start(getApplication())
    }

    fun pauseTorrent(infoHash: String) {
        repository.engine.pauseTorrent(infoHash)
    }

    fun resumeTorrent(infoHash: String) {
        repository.engine.resumeTorrent(infoHash)
        TorrentService.start(getApplication())
    }

    fun pauseAll() {
        repository.engine.pauseAll()
    }

    fun resumeAll() {
        repository.engine.resumeAll()
        TorrentService.start(getApplication())
    }

    fun removeTorrent(infoHash: String, deleteFiles: Boolean) {
        repository.engine.removeTorrent(infoHash, deleteFiles)
    }

    fun removeCompletedTorrent(infoHash: String, deleteFiles: Boolean) {
        repository.removeCompletedTorrent(infoHash, deleteFiles)
    }

    fun setSequential(infoHash: String, enable: Boolean) {
        repository.engine.setSequentialDownload(infoHash, enable)
    }

    fun updateSettings(newSettings: AppSettings) {
        repository.updateSettings(newSettings)
    }

    fun testConnection(callback: (Boolean, String) -> Unit) {
        repository.testConnection(callback)
    }

    fun getStreamUrl(filePath: String): String {
        return repository.engine.streamServer.getStreamUrl(filePath)
    }

    fun isFileAvailable(filePath: String?): Boolean {
        return StorageHelper.checkFileExists(filePath)
    }
}
