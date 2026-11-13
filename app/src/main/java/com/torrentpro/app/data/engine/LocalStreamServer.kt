package com.torrentpro.app.data.engine

import android.util.Log
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class LocalStreamServer(private val port: Int = 8828) {

    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val executor: ExecutorService = Executors.newCachedThreadPool()

    fun start() {
        if (isRunning.getAndSet(true)) return

        executor.execute {
            try {
                serverSocket = ServerSocket(port)
                Log.d(TAG, "StreamServer started on 127.0.0.1:$port")
                while (isRunning.get()) {
                    val socket = serverSocket?.accept() ?: break
                    executor.execute { handleClient(socket) }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Server socket error", e)
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
        val encoded = java.net.URLEncoder.encode(filePath, "UTF-8")
        return "http://127.0.0.1:$port/stream?file=$encoded"
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { s ->
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

                if (!file.exists()) {
                    sendError(s.getOutputStream(), 404, "File Not Found")
                    return
                }

                serveFile(s.getOutputStream(), file, rangeHeader)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client connection ended: ${e.message}")
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

        if (start > end || start >= fileLength) {
            val header = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$fileLength\r\n\r\n"
            out.write(header.toByteArray(StandardCharsets.UTF_8))
            out.flush()
            return
        }

        val contentLength = end - start + 1
        val isPartial = rangeHeader != null

        val responseHeader = StringBuilder()
        if (isPartial) {
            responseHeader.append("HTTP/1.1 206 Partial Content\r\n")
            responseHeader.append("Content-Range: bytes $start-$end/$fileLength\r\n")
        } else {
            responseHeader.append("HTTP/1.1 200 OK\r\n")
        }

        responseHeader.append("Content-Type: $mimeType\r\n")
        responseHeader.append("Content-Length: $contentLength\r\n")
        responseHeader.append("Accept-Ranges: bytes\r\n")
        responseHeader.append("Connection: keep-alive\r\n")
        responseHeader.append("\r\n")

        out.write(responseHeader.toString().toByteArray(StandardCharsets.UTF_8))
        out.flush()

        // Stream file content
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buffer = ByteArray(64 * 1024) // 64KB buffer
            var remaining = contentLength

            while (remaining > 0 && isRunning.get()) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = raf.read(buffer, 0, toRead)
                if (read == -1) {
                    // Torrent might still be writing; wait briefly up to 1 second
                    Thread.sleep(100)
                    val newLength = file.length()
                    if (newLength <= raf.filePointer) {
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
            else -> "application/octet-stream"
        }
    }

    companion object {
        private const val TAG = "LocalStreamServer"
    }
}
