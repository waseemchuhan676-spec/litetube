package com.litetube.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Professional Settings screen (v1.5.0): updates, downloads and about,
 * in the light theme, D-Pad-focusable throughout (every control uses
 * the red focus border; the screen scrolls so nothing is ever cut off
 * on a small TV).
 *
 * Every setting is stored in AppSettings ("litetube_settings"
 * SharedPreferences) and ENFORCED in a real flow — auto-update in
 * UpdateManager, default quality / WiFi-only / size limits in
 * MainActivity.downloadStreams(), the download folder in
 * GalleryDownloader. Only the version-info rows are display-only.
 *
 * No new dependency: plain views + SharedPreferences (no Preference
 * library — the library would pull in fragments and a second theming
 * layer for a screen this small, and the plain views match the app's
 * D-Pad focus rules exactly).
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var checkStatus: TextView
    private lateinit var folderPathText: TextView

    /** True while the code sets a spinner position (or attaches its
     *  listener): the listener must not treat the programmatic
     *  selection as a user change and write it back. */
    private var initializingSpinners = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = AppSettings(this)

        findViewById<Button>(R.id.settingsBack).setOnClickListener { finish() }

        // ---- Updates ----

        val autoUpdateSwitch = findViewById<SwitchCompat>(R.id.autoUpdateSwitch)
        // Value first, listener second: setting isChecked fires the
        // listener, and the stored value must not be re-written by it.
        autoUpdateSwitch.isChecked = settings.autoUpdateEnabled
        autoUpdateSwitch.setOnCheckedChangeListener { _, checked ->
            settings.autoUpdateEnabled = checked
        }

        checkStatus = findViewById(R.id.checkStatus)
        findViewById<Button>(R.id.checkNowButton).setOnClickListener {
            (application as LiteTubeApp).updateManager.checkNow()
            checkStatus.text = getString(R.string.settings_checking)
        }
        findViewById<TextView>(R.id.versionValue).text = getString(
            R.string.me_version_format,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE
        )
        findViewById<TextView>(R.id.packageValue).text = packageName
        findViewById<TextView>(R.id.extractorValue).text =
            getString(R.string.settings_extractor_version)
        findViewById<TextView>(R.id.channelValue).text =
            getString(R.string.settings_channel_value)
        observeUpdateState()

        // ---- Downloads ----

        setupQualitySpinner()
        folderPathText = findViewById(R.id.folderPathText)
        refreshFolderPath()
        findViewById<Button>(R.id.changeFolderButton).setOnClickListener {
            showFolderDialog()
        }

        val wifiOnlySwitch = findViewById<SwitchCompat>(R.id.wifiOnlySwitch)
        wifiOnlySwitch.isChecked = settings.wifiOnlyDownloads
        wifiOnlySwitch.setOnCheckedChangeListener { _, checked ->
            settings.wifiOnlyDownloads = checked
        }

        setupLimitSpinner(R.id.mobileLimitSpinner, isMobile = true)
        setupLimitSpinner(R.id.wifiLimitSpinner, isMobile = false)

        // ---- About ----

        findViewById<Button>(R.id.websiteButton).setOnClickListener {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://littletube.vercel.app"))
                )
            } catch (e: ActivityNotFoundException) {
                // No browser at all on this device (rare, some TV boxes):
                // say so instead of crashing.
                Toast.makeText(this, R.string.settings_no_browser, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Shows the live result of the update check (manual or automatic)
     *  in the status line under "Check for updates now". */
    private fun observeUpdateState() {
        lifecycleScope.launch {
            (application as LiteTubeApp).updateManager.state.collect { state ->
                checkStatus.text = when (state) {
                    is UpdateState.Idle -> ""
                    is UpdateState.Checking -> getString(R.string.settings_checking)
                    is UpdateState.UpToDate -> getString(R.string.settings_up_to_date)
                    is UpdateState.Available ->
                        getString(R.string.settings_update_available, state.info.versionName)
                    is UpdateState.Downloading ->
                        getString(R.string.settings_update_downloading, state.percent)
                    is UpdateState.Verifying -> getString(R.string.settings_update_verifying)
                    is UpdateState.NeedsInstallPermission ->
                        getString(R.string.settings_update_needs_permission)
                    is UpdateState.Error ->
                        getString(R.string.settings_update_error, state.reason)
                }
            }
        }
    }

    /** Default download quality: Best available / 1080p / 720p / 480p /
     *  360p. Persists on every user selection; MainActivity reads it
     *  whenever a video loads. */
    private fun setupQualitySpinner() {
        val spinner = findViewById<Spinner>(R.id.defaultQualitySpinner)
        val labels = listOf(
            getString(R.string.settings_quality_best),
            getString(R.string.quality_1080),
            getString(R.string.quality_720),
            getString(R.string.quality_480),
            getString(R.string.quality_360)
        )
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            labels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        initializingSpinners = true
        spinner.setSelection(defaultQualityPosition(settings.defaultQuality))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (initializingSpinners) return
                settings.defaultQuality = when (position) {
                    0 -> DownloadQuality.BEST
                    1 -> DownloadQuality.P1080
                    2 -> DownloadQuality.P720
                    3 -> DownloadQuality.P480
                    else -> DownloadQuality.P360
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        initializingSpinners = false
    }

    private fun defaultQualityPosition(quality: DownloadQuality): Int = when (quality) {
        DownloadQuality.BEST -> 0
        DownloadQuality.P1080 -> 1
        DownloadQuality.P720 -> 2
        DownloadQuality.P480 -> 3
        DownloadQuality.P360 -> 4
        DownloadQuality.AUDIO -> 0
    }

    /** One of the two size-limit spinners (mobile data / WiFi): No limit
     *  or 50 / 100 / 200 / 500 MB. Persists on every user selection. */
    private fun setupLimitSpinner(spinnerId: Int, isMobile: Boolean) {
        val spinner = findViewById<Spinner>(spinnerId)
        val options = AppSettings.LIMIT_OPTIONS_MB
        val labels = options.map { mb ->
            if (mb == 0L) {
                getString(R.string.settings_limit_none)
            } else {
                getString(R.string.settings_limit_mb, mb)
            }
        }
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            labels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val currentMb = if (isMobile) settings.mobileDataLimitMb else settings.wifiLimitMb
        initializingSpinners = true
        spinner.setSelection(options.indexOf(currentMb).takeIf { it >= 0 } ?: 0)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (initializingSpinners) return
                val mb = options[position]
                if (isMobile) {
                    settings.mobileDataLimitMb = mb
                } else {
                    settings.wifiLimitMb = mb
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        initializingSpinners = false
    }

    /**
     * Download folder: a subfolder inside the public Movies / Music
     * directories (see AppSettings for why a free path is not offered).
     * Tapping an option applies it at once and refreshes the display.
     * Cancelling (back / outside tap) changes nothing.
     */
    private fun showFolderDialog() {
        val options = arrayOf(
            getString(R.string.settings_folder_litetube),
            getString(R.string.settings_folder_videos),
            getString(R.string.settings_folder_none)
        )
        val values = listOf("LittleTube", "Videos", "")
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_folder_title)
            .setItems(options) { _, which ->
                settings.downloadSubfolder = values[which]
                refreshFolderPath()
            }
            .setNegativeButton(R.string.me_close, null)
            .show()
    }

    /** "Movies/LittleTube" (video path; audio goes to Music/…). */
    private fun refreshFolderPath() {
        folderPathText.text = settings.downloadPathDisplay(isAudio = false)
    }
}
