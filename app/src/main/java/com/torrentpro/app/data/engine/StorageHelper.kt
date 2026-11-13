package com.torrentpro.app.data.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import java.io.File

object StorageHelper {
    private const val TAG = "StorageHelper"

    fun getDefaultDownloadDir(): String {
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(publicDownloads, "TorrentPro")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir.absolutePath
    }

    fun getHumanReadablePath(context: Context, pathOrUri: String): String {
        if (pathOrUri.isBlank()) return "Not selected"

        if (pathOrUri.startsWith("content://")) {
            return try {
                val uri = Uri.parse(pathOrUri)
                val doc = DocumentFile.fromTreeUri(context, uri)
                val docName = doc?.name
                val docId = Uri.decode(uri.toString()).substringAfter("document/", "").substringAfter("tree/", "")

                if (docId.contains(":")) {
                    val split = docId.split(":", limit = 2)
                    val storageType = if (split[0].equals("primary", ignoreCase = true)) "Internal Storage" else "SD Card (${split[0]})"
                    val subPath = split.getOrNull(1)?.replace("/", " > ") ?: ""
                    if (subPath.isNotEmpty()) "$storageType > $subPath" else storageType
                } else if (!docName.isNullOrBlank()) {
                    "Folder: $docName"
                } else {
                    "Custom Folder"
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error formatting URI: ${e.message}")
                "Custom Storage"
            }
        }

        // Standard POSIX paths
        val internalPrefix = "/storage/emulated/0"
        return when {
            pathOrUri.startsWith(internalPrefix) -> {
                val rel = pathOrUri.removePrefix(internalPrefix).trim('/')
                if (rel.isEmpty()) "Internal Storage" else "Internal Storage > " + rel.replace("/", " > ")
            }
            pathOrUri.contains("Android/data/") -> {
                "App Storage > Downloads"
            }
            else -> {
                pathOrUri.trim('/').replace("/", " > ")
            }
        }
    }

    fun isWritable(context: Context, pathOrUri: String): Boolean {
        if (pathOrUri.isBlank()) return false

        if (pathOrUri.startsWith("content://")) {
            return try {
                val uri = Uri.parse(pathOrUri)
                val doc = DocumentFile.fromTreeUri(context, uri)
                doc != null && doc.exists() && doc.canWrite()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to check write permission on tree URI: ${e.message}")
                false
            }
        }

        return try {
            val file = File(pathOrUri)
            if (!file.exists()) {
                file.mkdirs()
            }
            file.exists() && file.canWrite()
        } catch (_: Exception) {
            false
        }
    }

    fun persistTreeUri(context: Context, uri: Uri): Boolean {
        return try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException taking persistable permission: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Failed taking persistable permission: ${e.message}")
            false
        }
    }

    fun getSharableUri(context: Context, file: File): Uri {
        return try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            Log.e(TAG, "FileProvider error: ${e.message}, falling back to Uri.fromFile")
            Uri.fromFile(file)
        }
    }

    fun checkFileExists(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return try {
            val f = File(path)
            f.exists() && f.length() > 0
        } catch (_: Exception) {
            false
        }
    }
}
