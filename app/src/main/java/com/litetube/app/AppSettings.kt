package com.litetube.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment

/** What kind of network the device is on right now. UNKNOWN covers
 *  Ethernet, VPN and "could not tell" — the download gate never blocks
 *  on an unknown network, it only enforces limits it can verify. */
enum class NetworkType {
    WIFI, MOBILE, UNKNOWN
}

/** Result of the download gate in checkDownload(). */
sealed interface DownloadGate {
    /** Go ahead. */
    data object Allowed : DownloadGate
    /** "Download only on WiFi" is on and the device is on mobile data. */
    data object BlockedWifiOnly : DownloadGate
    /** The (estimated) file is bigger than the limit for this network. */
    data class BlockedSizeLimit(val limitBytes: Long) : DownloadGate
}

/**
 * All user settings (v1.5.0), kept on-device in one SharedPreferences
 * file ("litetube_settings"). Every setting is ENFORCED in a real flow,
 * not display-only:
 * - auto-update: read by UpdateManager before its automatic launch and
 *   network-appeared checks; the manual "Check for updates" button
 *   (checkNow()) always bypasses it.
 * - default quality: read by MainActivity when a video loads (the
 *   quality spinner's default); the user can still override it per
 *   download with the spinner.
 * - download subfolder: read by GalleryDownloader for both the
 *   MediaStore path (API 29+) and the DownloadManager path (API 21-28).
 * - WiFi-only + size limits: read by MainActivity.downloadStreams()
 *   through checkDownload() before any download starts.
 *
 * Download location, honestly: on modern Android (scoped storage) an
 * app cannot write to a freely chosen folder and still have the file
 * show up in the Gallery. The setting therefore picks a SUBFOLDER inside
 * the public Movies / Music directories ("LittleTube" by default, or
 * "Videos", or no subfolder at all) — a path the app can genuinely
 * write to on every API level. There is no file-picker (SAF) option:
 * a SAF tree URI cannot be used with MediaStore RELATIVE_PATH, so
 * offering one would be a fake choice.
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager

    // ---- Updates ----

    /** Default ON: automatic update checks on launch / when the network
     *  appears. OFF means updates only via "Check for updates now". */
    var autoUpdateEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPDATE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_UPDATE, value).apply()

    // ---- Downloads ----

    /** Default download quality. Never AUDIO: the setting offers video
     *  qualities only; audio-only stays a per-download spinner choice. */
    var defaultQuality: DownloadQuality
        get() = when (prefs.getString(KEY_DEFAULT_QUALITY, DownloadQuality.BEST.name)) {
            DownloadQuality.P1080.name -> DownloadQuality.P1080
            DownloadQuality.P720.name -> DownloadQuality.P720
            DownloadQuality.P480.name -> DownloadQuality.P480
            DownloadQuality.P360.name -> DownloadQuality.P360
            else -> DownloadQuality.BEST
        }
        set(value) = prefs.edit().putString(KEY_DEFAULT_QUALITY, value.name).apply()

    /** Subfolder inside Movies / Music. Allowed values only — anything
     *  else stored (e.g. from an older build) falls back to the default,
     *  so a bad value can never produce a weird path. "" = no subfolder. */
    var downloadSubfolder: String
        get() {
            val stored = prefs.getString(KEY_SUBFOLDER, SUBFOLDER_DEFAULT)
                ?: SUBFOLDER_DEFAULT
            return if (stored == SUBFOLDER_DEFAULT || stored == "Videos" || stored.isEmpty()) {
                stored
            } else {
                SUBFOLDER_DEFAULT
            }
        }
        set(value) = prefs.edit().putString(KEY_SUBFOLDER, value).apply()

    /** Human-readable folder the downloads land in, e.g.
     *  "Movies/LittleTube" (video) or "Music/LittleTube" (audio). */
    fun downloadPathDisplay(isAudio: Boolean): String {
        val base = if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
        val sub = downloadSubfolder
        return if (sub.isEmpty()) base else "$base/$sub"
    }

    /** Default OFF: when ON, downloads on mobile data are blocked. */
    var wifiOnlyDownloads: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    /** Mobile-data size limit in MB; 0 = no limit. */
    var mobileDataLimitMb: Long
        get() = prefs.getLong(KEY_MOBILE_LIMIT_MB, 0L)
        set(value) = prefs.edit().putLong(KEY_MOBILE_LIMIT_MB, value).apply()

    /** WiFi size limit in MB; 0 = no limit. */
    var wifiLimitMb: Long
        get() = prefs.getLong(KEY_WIFI_LIMIT_MB, 0L)
        set(value) = prefs.edit().putLong(KEY_WIFI_LIMIT_MB, value).apply()

    // ---- Network detection (minSdk 21 APIs only) ----

    /** Current network type. API 23+: NetworkCapabilities transports;
     *  API 21-22: the deprecated activeNetworkInfo (still the only API
     *  there). Returns UNKNOWN when there is no network or the type
     *  cannot be told — the gate treats UNKNOWN as "allow". */
    fun currentNetworkType(): NetworkType {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val caps = connectivityManager
                .getNetworkCapabilities(connectivityManager.activeNetwork)
            if (caps != null) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    return NetworkType.WIFI
                }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    return NetworkType.MOBILE
                }
            }
            return NetworkType.UNKNOWN
        }
        @Suppress("DEPRECATION")
        val info = connectivityManager.activeNetworkInfo
        if (info != null && info.isConnected) {
            return when (info.type) {
                ConnectivityManager.TYPE_WIFI -> NetworkType.WIFI
                ConnectivityManager.TYPE_MOBILE -> NetworkType.MOBILE
                else -> NetworkType.UNKNOWN
            }
        }
        return NetworkType.UNKNOWN
    }

    /**
     * The download gate, in order: WiFi-only first, then the size limit
     * for the current network. sizeBytes <= 0 means "unknown size"
     * (the extractor reports no content length and the bitrate was
     * unknown) — an unknown size NEVER blocks a download. An unknown
     * network type also never blocks (no limit can be attributed to it).
     */
    fun checkDownload(sizeBytes: Long, network: NetworkType): DownloadGate {
        if (network == NetworkType.MOBILE && wifiOnlyDownloads) {
            return DownloadGate.BlockedWifiOnly
        }
        val limitMb = when (network) {
            NetworkType.WIFI -> wifiLimitMb
            NetworkType.MOBILE -> mobileDataLimitMb
            NetworkType.UNKNOWN -> 0L
        }
        if (limitMb > 0 && sizeBytes > 0 && sizeBytes > limitMb * 1024L * 1024L) {
            return DownloadGate.BlockedSizeLimit(limitMb * 1024L * 1024L)
        }
        return DownloadGate.Allowed
    }

    companion object {
        private const val PREFS_NAME = "litetube_settings"
        const val KEY_AUTO_UPDATE = "auto_update"
        const val KEY_DEFAULT_QUALITY = "default_quality"
        const val KEY_SUBFOLDER = "download_subfolder"
        const val KEY_WIFI_ONLY = "wifi_only_downloads"
        const val KEY_MOBILE_LIMIT_MB = "mobile_data_limit_mb"
        const val KEY_WIFI_LIMIT_MB = "wifi_limit_mb"
        const val SUBFOLDER_DEFAULT = "LittleTube"
        /** Size-limit choices offered in Settings, in MB (0 = no limit). */
        val LIMIT_OPTIONS_MB = listOf(0L, 50L, 100L, 200L, 500L)
    }
}
