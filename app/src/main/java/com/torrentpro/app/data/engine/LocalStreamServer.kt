package com.torrentpro.app.data.engine

import android.util.Log
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class LocalStreamServer(preferredPort: Int = 8828) {

    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    var activePort: Int = preferredPort
        private set
    private val executor: ExecutorService = Executors.newCachedThreadPool()

    fun start() {
        if (isRunning.getAndSet(true)) return

        executor.execute {
            try {
                // Try preferred port first; fallback to ephemeral port 0 if in use
                serverSocket = try {
                    ServerSocket(activePort)
                } catch (e: IOException) {
                    Log.w(TAG, "Preferred port $activePort busy, picking open port: ${e.message}")
                    ServerSocket(0)
                }
                activePort = serverSocket!!.localPort
                Log.d(TAG, "StreamServer started on 127.0.0.1:$activePort")

                while (isRunning.get()) {
                    val socket = serverSocket?.accept() ?: break
                    executor.execute { handleClient(socket) }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "StreamServer error: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        executor.shutdownNow()
    }

    fun getStreamUrl(filePath: String): String {
        val encoded = URLEncoder.encode(filePath, "UTF-8")
        return "http://127.0.0.1:$activePort/stream?file=$encoded"
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 10000 // 10s socket read timeout
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
                val firstLine = reader.readLine() ?: return
                val parts = firstLine.split(" ")
                if (parts.size < 2 || parts[0] != "GET") {
                    sendError(s.getOutputStream(), 400, "Bad Request")
                    return
                }

                val uri = parts[1]
                var rangeHeader: String? = null

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line.isNullOrBlank()) break
                    val headerLower = line!!.lowercase()
                    if (headerLower.startsWith("range:")) {
                        rangeHeader = line!!.substring(6).trim()
                    }
                }

                if (!uri.startsWith("/stream?file=")) {
                    sendError(s.getOutputStream(), 404, "Not Found")
                    return
                }

                val rawFilePath = uri.substring("/stream?file=".length)
                val filePath = URLDecoder.decode(rawFilePath, "UTF-8")
                val file = File(filePath)

                // Wait up to 3 seconds for file creation if torrent just started allocating
                var waitCount = 0
                while (!file.exists() && waitCount < 15 && isRunning.get()) {
                    Thread.sleep(200)
                    waitCount++
                }

                if (!file.exists() || !file.canRead()) {
                    sendError(s.getOutputStream(), 404, "File Not Available")
                    return
                }

                serveFile(s.getOutputStream(), file, rangeHeader)
            }
        } catch (_: SocketException) {
            // Client closed connection during scrub or pause, expected behavior
        } catch (e: Exception) {
            Log.d(TAG, "Client stream finished: ${e.message}")
        }
    }

    private fun serveFile(out: OutputStream, file: File, rangeHeader: String?) {
        val fileLength = file.length()
        val mimeType = getMimeType(file.name)

        var start: Long = 0
        var end: Long = fileLength - 1

        if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=")) {
            val rangeVal = rangeHeader.substring(6).trim()
            val dashIdx = rangeVal.indexOf('-')
            if (dashIdx != -1) {
                val startStr = rangeVal.substring(0, dashIdx).trim()
                val endStr = rangeVal.substring(dashIdx + 1).trim()
                if (startStr.isNotEmpty()) {
                    start = startStr.toLongOrNull() ?: 0L
                }
                if (endStr.isNotEmpty()) {
                    end = endStr.toLongOrNull() ?: (fileLength - 1)
                }
            }
        }

        if (fileLength > 0 && (start > end || start >= fileLength)) {
            val header = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$fileLength\r\n\r\n"
            out.write(header.toByteArray(StandardCharsets.UTF_8))
            out.flush()
            return
        }

        val contentLength = if (fileLength > 0) (end - start + 1) else 0
        val isPartial = rangeHeader != null

        val responseHeader = StringBuilder()
        if (isPartial) {
            responseHeader.append("HTTP/1.1 206 Partial Content\r\n")
            if (fileLength > 0) {
                responseHeader.append("Content-Range: bytes $start-$end/$fileLength\r\n")
            }
        } else {
            responseHeader.append("HTTP/1.1 200 OK\r\n")
        }

        responseHeader.append("Content-Type: $mimeType\r\n")
        if (contentLength > 0) {
            responseHeader.append("Content-Length: $contentLength\r\n")
        }
        responseHeader.append("Accept-Ranges: bytes\r\n")
        responseHeader.append("Access-Control-Allow-Origin: *\r\n")
        responseHeader.append("Connection: keep-alive\r\n")
        responseHeader.append("\r\n")

        out.write(responseHeader.toString().toByteArray(StandardCharsets.UTF_8))
        out.flush()

        if (fileLength == 0L) return

        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buffer = ByteArray(64 * 1024)
            var remaining = contentLength

            while (remaining > 0 && isRunning.get()) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = raf.read(buffer, 0, toRead)
                if (read == -1) {
                    // Torrent might still be downloading; pause briefly waiting for new bytes
                    Thread.sleep(150)
                    val updatedLen = file.length()
                    if (updatedLen <= raf.filePointer) {
                        break
                    }
                    continue
                }
                out.write(buffer, 0, read)
                remaining -= read
            }
            out.flush()
        }
    }

    private fun sendError(out: OutputStream, code: Int, status: String) {
        val msg = "HTTP/1.1 $code $status\r\nContent-Type: text/plain\r\nContent-Length: ${status.length}\r\n\r\n$status"
        out.write(msg.toByteArray(StandardCharsets.UTF_8))
        out.flush()
    }

    private fun getMimeType(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "ts" -> "video/mp2t"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "ogg" -> "audio/ogg"
            else -> "application/octet-stream"
        }
    }

    companion object {
        private const val TAG = "LocalStreamServer"
    }
}
