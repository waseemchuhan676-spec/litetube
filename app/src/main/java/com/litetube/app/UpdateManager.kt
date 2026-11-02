package com.litetube.app

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** Manifest data from update.json. sha256 is required: an APK is never
 *  installed unless its hash matches the manifest (supply-chain safety). */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val releaseNotes: String
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    data object Verifying : UpdateState
    data object NeedsInstallPermission : UpdateState
    data class Error(val reason: String) : UpdateState
}

/**
 * OTA update manager for the Vercel-hosted update.json manifest.
 *
 * Flow: internet appears -> GET update.json -> remote versionCode >
 * BuildConfig.VERSION_CODE -> DownloadManager downloads the APK ->
 * SHA-256 verified against the manifest -> system installer opens.
 *
 * This is NOT a silent install: stock Android cannot install apps silently.
 * The system installer always shows, and the user taps Install once — that
 * single tap is the "1-click" step. On Android 8+, the user must also have
 * allowed "Install unknown apps" for this app once; if not, the manager
 * opens that exact settings screen instead of failing silently.
 *
 * Host responsibilities (see GUIDE.md): call checkNow() or rely on the
 * NetworkCallback, and call release() when the host is destroyed so the
 * callback is unregistered. Requires REQUEST_INSTALL_PACKAGES, a
 * FileProvider entry, and buildFeatures.buildConfig = true.
 */
class UpdateManager(
    context: Context,
    private val updateJsonUrl: String = "https://my-app.vercel.app/update.json",
    private val autoDownload: Boolean = true,
    /** When true (default), automatic downloads only start on unmetered
     *  networks (Wi-Fi). Manual downloadUpdate() calls always bypass this,
     *  because the tap itself is consent to use metered data. */
    private val requireUnmeteredForAutoDownload: Boolean = true,
    private val okHttpClient: OkHttpClient = OkHttpClient()
) {

    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var lastAutoCheckMs: Long = 0L
    private var currentDownloadId: Long = -1L

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Debounce: at most one automatic check per 6 hours. Manual
            // checkNow() calls always bypass the debounce. The timestamp is
            // deliberately NOT set here: it is set only after a successful
            // manifest fetch inside checkForUpdate(), so a failed check
            // (captive portal, glitch) does not eat the 6-hour window.
            val now = System.currentTimeMillis()
            if (now - lastAutoCheckMs < 6 * 60 * 60 * 1000L) return
            scope.launch { checkForUpdate() }
        }
    }

    /** Start listening for connectivity. Safe to call once at app start. */
    fun start() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
        // Also check immediately: the device may already be online.
        scope.launch { checkForUpdate() }
    }

    /** Stop listening and cancel in-flight work. Call from onDestroy. */
    fun release() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
            // Already unregistered; nothing to do.
        }
        scope.cancel()
    }

    /** Manual check (e.g. a "Check for updates" button). Bypasses debounce. */
    fun checkNow() {
        scope.launch { checkForUpdate() }
    }

    private suspend fun checkForUpdate() {
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        try {
            val info = withContext(Dispatchers.IO) { fetchManifest() }
            // Only a successful fetch consumes the debounce window (see
            // networkCallback). This also covers start()'s immediate check,
            // so an onAvailable firing right after launch cannot double it.
            lastAutoCheckMs = System.currentTimeMillis()
            if (info.versionCode > BuildConfig.VERSION_CODE) {
                _state.value = UpdateState.Available(info)
                if (autoDownload &&
                    (!requireUnmeteredForAutoDownload || isUnmetered())
                ) {
                    startDownload(info)
                }
                // On a metered network the state stays Available: the user
                // can start the download manually via downloadUpdate(info).
            } else {
                _state.value = UpdateState.UpToDate
            }
        } catch (e: Exception) {
            _state.value = UpdateState.Error(e.message ?: "Update check failed")
        }
    }

    /** Manual download trigger (e.g. an "Update now" button). Bypasses the
     *  unmetered-network gate because the tap itself is user consent. */
    fun downloadUpdate(info: UpdateInfo) {
        scope.launch { startDownload(info) }
    }

    /**
     * Installs an already-downloaded update.apk without re-downloading.
     * Call this when returning from the "Install unknown apps" settings
     * screen (e.g. from onResume) so granting permission installs at once
     * instead of forcing a full re-download on the next check.
     * Works best with an Application-scoped UpdateManager (see GUIDE.md),
     * where the Available state survives the settings detour.
     */
    fun installPendingUpdate() {
        val apkFile = File(
            appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "update.apk"
        )
        if (!apkFile.exists()) return
        scope.launch {
            val info = (_state.value as? UpdateState.Available)?.info ?: return@launch
            if (verifySha256(apkFile, info.sha256)) {
                openInstaller(apkFile)
            } else {
                apkFile.delete()
                _state.value = UpdateState.Error("APK signature mismatch — update rejected")
            }
        }
    }

    private fun isUnmetered(): Boolean {
        // activeNetwork exists only from API 23; the app's minSdk is 21, so
        // guard it — otherwise API 21/22 devices would crash here.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val caps = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            if (caps != null) {
                return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
        }
        @Suppress("DEPRECATION")
        return !connectivityManager.isActiveNetworkMetered
    }

    private fun fetchManifest(): UpdateInfo {
        val request = Request.Builder().url(updateJsonUrl).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("update.json HTTP ${response.code}")
            }
            val body = response.body?.string()
                ?: throw IllegalStateException("Empty update.json")
            val json = JSONObject(body)
            return UpdateInfo(
                versionCode = json.getInt("versionCode"),
                versionName = json.getString("versionName"),
                apkUrl = json.getString("apkUrl"),
                sha256 = json.optString("sha256", ""),
                releaseNotes = json.optString("releaseNotes", "")
            )
        }
    }

    private suspend fun startDownload(info: UpdateInfo) {
        val apkFile = File(
            appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "update.apk"
        )
        // Already downloaded (e.g. app restarted after a download, or the
        // user just returned from the install-permission screen)? Verify
        // and install at once instead of downloading the whole APK again.
        if (apkFile.exists()) {
            val verified = withContext(Dispatchers.IO) { verifySha256(apkFile, info.sha256) }
            if (verified) {
                openInstaller(apkFile)
                return
            }
            apkFile.delete()
        }

        val manager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(info.apkUrl)).apply {
            setTitle("LittleTube update ${info.versionName}")
            setDescription(info.releaseNotes)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationUri(Uri.fromFile(apkFile))
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
        }
        currentDownloadId = manager.enqueue(request)
        _state.value = UpdateState.Downloading(0)

        // Poll DownloadManager until the download finishes. Polling (rather
        // than a broadcast receiver) keeps all state in this one class.
        // The loop is BOUNDED on purpose: an unbounded loop would spin
        // forever if the download record vanishes or the download stays
        // paused (battery drain + leaked coroutine). Limits: 30 minutes
        // total, 5 consecutive empty query results, 60 seconds paused.
        // Error paths remove the DownloadManager record so no orphaned
        // download keeps running after we give up.
        withContext(Dispatchers.IO) {
            var polls = 0
            var emptyResults = 0
            var pausedPolls = 0
            while (polls < 1800) {
                polls++
                var sawRecord = false
                val query = DownloadManager.Query().setFilterById(currentDownloadId)
                manager.query(query)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        sawRecord = true
                        emptyResults = 0
                        val status = cursor.getInt(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                        )
                        val downloaded = cursor.getLong(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        )
                        val total = cursor.getLong(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        )
                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                _state.value = UpdateState.Verifying
                                if (verifySha256(apkFile, info.sha256)) {
                                    openInstaller(apkFile)
                                } else {
                                    apkFile.delete()
                                    manager.remove(currentDownloadId)
                                    _state.value = UpdateState.Error(
                                        "APK signature mismatch — update rejected"
                                    )
                                }
                                return@withContext
                            }
                            DownloadManager.STATUS_FAILED -> {
                                manager.remove(currentDownloadId)
                                _state.value = UpdateState.Error("APK download failed")
                                return@withContext
                            }
                            DownloadManager.STATUS_PAUSED -> {
                                pausedPolls++
                                if (pausedPolls > 60) {
                                    manager.remove(currentDownloadId)
                                    _state.value = UpdateState.Error(
                                        "Download paused — check connection and retry"
                                    )
                                    return@withContext
                                }
                            }
                            else -> {
                                pausedPolls = 0
                                if (total > 0) {
                                    _state.value = UpdateState.Downloading(
                                        (downloaded * 100 / total).toInt()
                                    )
                                }
                            }
                        }
                    }
                }
                if (!sawRecord) {
                    emptyResults++
                    if (emptyResults > 5) {
                        manager.remove(currentDownloadId)
                        _state.value = UpdateState.Error("Download record lost")
                        return@withContext
                    }
                }
                delay(1000L)
            }
            manager.remove(currentDownloadId)
            _state.value = UpdateState.Error("Download timed out")
        }
    }

    /** Streams the file through SHA-256 and compares with the manifest. */
    private fun verifySha256(file: File, expectedHex: String): Boolean {
        if (expectedHex.isBlank()) return false
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expectedHex, ignoreCase = true)
    }

    private fun openInstaller(apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !appContext.packageManager.canRequestPackageInstalls()
        ) {
            // First-run gate: send the user to this app's exact
            // "Install unknown apps" switch instead of failing silently.
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            appContext.startActivity(settingsIntent)
            _state.value = UpdateState.NeedsInstallPermission
            return
        }
        val apkUri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(installIntent)
    }
}
