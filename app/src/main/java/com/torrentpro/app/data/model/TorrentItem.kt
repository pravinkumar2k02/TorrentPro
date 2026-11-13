package com.torrentpro.app.data.model

data class TorrentItem(
    val id: String,
    val infoHash: String,
    val name: String,
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val downloadSpeed: Long = 0L,
    val uploadSpeed: Long = 0L,
    val progress: Float = 0f, // 0.0 to 1.0
    val etaSeconds: Long = -1L,
    val peers: Int = 0,
    val seeds: Int = 0,
    val isPaused: Boolean = false,
    val isFinished: Boolean = false,
    val isSequential: Boolean = true,
    val isVideo: Boolean = false,
    val videoFilePath: String? = null,
    val savePath: String = "",
    val displayDestination: String = "",
    val stateString: String = "Queued",
    val fileMissing: Boolean = false,
    val addedTime: Long = System.currentTimeMillis(),
    val completedTime: Long? = null
) {
    val progressPercent: String
        get() = String.format("%.1f%%", progress * 100f)

    val formattedDownloadSpeed: String
        get() = "${formatBytes(downloadSpeed)}/s"

    val formattedUploadSpeed: String
        get() = "${formatBytes(uploadSpeed)}/s"

    val formattedDownloaded: String
        get() = formatBytes(downloadedBytes)

    val formattedTotal: String
        get() = if (totalBytes > 0) formatBytes(totalBytes) else "Calculating..."

    val formattedEta: String
        get() = when {
            fileMissing -> "Missing"
            isFinished -> "Completed"
            isPaused -> "Paused"
            etaSeconds < 0 -> "--:--:--"
            etaSeconds > 359999 -> "> 99h"
            else -> {
                val h = etaSeconds / 3600
                val m = (etaSeconds % 3600) / 60
                val s = etaSeconds % 60
                String.format("%02d:%02d:%02d", h, m, s)
            }
        }

    companion object {
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "avi", "mov", "webm", "flv", "ts", "wmv", "m4v", "3gp", "vob"
        )

        fun isVideoFile(fileName: String): Boolean {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return ext in VIDEO_EXTENSIONS
        }

        fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            var b = bytes.toDouble()
            var unitIndex = 0
            while (b >= 1024.0 && unitIndex < units.lastIndex) {
                b /= 1024.0
                unitIndex++
            }
            return String.format("%.2f %s", b, units[unitIndex])
        }
    }
}
