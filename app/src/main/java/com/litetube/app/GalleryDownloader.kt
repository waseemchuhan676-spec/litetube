package com.litetube.app

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Running(val percent: Int) : DownloadState
    data class Done(val fileName: String, val actualQuality: String) : DownloadState
    data class Failed(val reason: String) : DownloadState
}

/**
 * Downloads into the PUBLIC media library so the file shows up immediately
 * in the Gallery, VLC and other players — not into an encrypted app cache.
 *
 * Two storage paths, split exactly where Android split them:
 * - API 29+ (scoped storage): MediaStore insert with RELATIVE_PATH and the
 *   IS_PENDING flag, streamed through OkHttp. DownloadManager cannot target
 *   a MediaStore Uri, so OkHttp does the copy here. The file becomes
 *   visible the moment IS_PENDING is cleared — no MediaScanner call needed.
 * - API 21–28 (legacy): DownloadManager into the public Movies/Music
 *   directory, then MediaScannerConnection.scanFile() on completion so the
 *   file is indexed immediately.
 */
class GalleryDownloader(
    private val context: Context,
    private val okHttpClient: OkHttpClient = OkHttpClient()
) {

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    suspend fun downloadVideo(
        title: String,
        streamUrl: String,
        actualLabel: String,
        extension: String
    ) {
        val base = QualitySelector.safeFileName(title)
        val fileName = "$base.$extension"
        val mime = QualitySelector.videoMimeType(extension)
        download(
            streamUrl = streamUrl,
            fileName = fileName,
            mimeType = mime,
            isAudio = false,
            actualQuality = actualLabel
        )
    }

    suspend fun downloadAudio(title: String, streamUrl: String, extension: String) {
        val base = QualitySelector.safeFileName(title)
        val fileName = "$base.$extension"
        val mime = QualitySelector.audioMimeType(extension)
        download(
            streamUrl = streamUrl,
            fileName = fileName,
            mimeType = mime,
            isAudio = true,
            actualQuality = "Audio"
        )
    }

    private suspend fun download(
        streamUrl: String,
        fileName: String,
        mimeType: String,
        isAudio: Boolean,
        actualQuality: String
    ) {
        _state.value = DownloadState.Running(0)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                downloadViaMediaStore(streamUrl, fileName, mimeType, isAudio)
            } else {
                downloadViaDownloadManager(streamUrl, fileName, mimeType, isAudio)
            }
            _state.value = DownloadState.Done(fileName, actualQuality)
        } catch (e: Exception) {
            _state.value = DownloadState.Failed(e.message ?: "Download failed")
        }
    }

    // ---- API 29+: MediaStore + OkHttp streaming copy ----

    private suspend fun downloadViaMediaStore(
        streamUrl: String,
        fileName: String,
        mimeType: String,
        isAudio: Boolean
    ) {
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val collection = if (isAudio) {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val relativePath = if (isAudio) {
                "${Environment.DIRECTORY_MUSIC}/LiteTube"
            } else {
                "${Environment.DIRECTORY_MOVIES}/LiteTube"
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            try {
                val request = Request.Builder().url(streamUrl).build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IllegalStateException("Empty response")
                    val total = body.contentLength()
                    resolver.openOutputStream(uri)?.use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var downloaded = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                out.write(buffer, 0, read)
                                downloaded += read
                                if (total > 0) {
                                    _state.value =
                                        DownloadState.Running((downloaded * 100 / total).toInt())
                                }
                            }
                        }
                    } ?: throw IllegalStateException("Cannot open output stream")
                }
                ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }.let { doneValues ->
                    resolver.update(uri, doneValues, null, null)
                }
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        }
    }

    // ---- API 21–28: DownloadManager + MediaScanner ----

    private fun downloadViaDownloadManager(
        streamUrl: String,
        fileName: String,
        mimeType: String,
        isAudio: Boolean
    ) {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val publicDir = if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
        val request = DownloadManager.Request(Uri.parse(streamUrl)).apply {
            setTitle(fileName)
            setDescription("LiteTube download")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(publicDir, "LiteTube/$fileName")
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
        }
        val downloadId = manager.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val completedId =
                    intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (completedId != downloadId) return
                context.unregisterReceiver(this)
                val path = queryLocalPath(manager, downloadId)
                if (path != null) {
                    MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf(mimeType), null)
                } else {
                    _state.value = DownloadState.Failed("Download finished but file not found")
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
    }

    private fun queryLocalPath(manager: DownloadManager, downloadId: Long): String? {
        manager.query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
            if (cursor.moveToFirst()) {
                val uriString = cursor.getString(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
                )
                val uri = Uri.parse(uriString)
                if (uri.scheme == "file") return uri.path
                if (uri.scheme == "content") {
                    // On some firmwares DownloadManager returns a content Uri;
                    // fall back to the well-known public path.
                    return File(
                        Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_MOVIES
                        ),
                        "LiteTube"
                    ).absolutePath
                }
            }
        }
        return null
    }
}
