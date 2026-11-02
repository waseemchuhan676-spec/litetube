package com.litetube.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Orchestrates everything, in this order:
 * search input -> repository (NewPipeExtractor) -> PlayerManager for
 * playback -> GalleryDownloader for the Download button.
 *
 * Update checks live in LiteTubeApp (Application scope), not here. This
 * activity only calls installPendingUpdate() on resume: after the user
 * returns from the "Install unknown apps" settings screen, the
 * already-downloaded APK installs at once instead of re-downloading.
 */
class MainActivity : AppCompatActivity() {

    private val okHttpClient = OkHttpClient()
    private lateinit var repository: YoutubeRepository
    private lateinit var playerManager: PlayerManager
    private lateinit var downloader: GalleryDownloader
    private lateinit var resultsAdapter: SearchResultsAdapter

    private var currentStreams: StreamSet? = null

    private lateinit var searchInput: EditText
    private lateinit var statusText: TextView
    private lateinit var loadingBar: ProgressBar
    private lateinit var videoTitle: TextView
    private lateinit var videoMeta: TextView
    private lateinit var qualitySpinner: Spinner
    private lateinit var downloadButton: Button

    companion object {
        private const val LEGACY_STORAGE_REQUEST = 1001
        private val VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repository = YoutubeRepository()
        playerManager = PlayerManager(this, okHttpClient)
        playerManager.bind(findViewById(R.id.playerView))
        downloader = GalleryDownloader(this, okHttpClient)

        searchInput = findViewById(R.id.searchInput)
        statusText = findViewById(R.id.statusText)
        loadingBar = findViewById(R.id.loadingBar)
        videoTitle = findViewById(R.id.videoTitle)
        videoMeta = findViewById(R.id.videoMeta)
        qualitySpinner = findViewById(R.id.qualitySpinner)
        downloadButton = findViewById(R.id.downloadButton)

        val resultsList = findViewById<RecyclerView>(R.id.resultsList)
        resultsList.layoutManager = LinearLayoutManager(this)
        resultsAdapter = SearchResultsAdapter { item ->
            lifecycleScope.launch { repository.loadStream(item.url) }
        }
        resultsList.adapter = resultsAdapter

        val qualityLabels = listOf(
            getString(R.string.quality_1080),
            getString(R.string.quality_720),
            getString(R.string.quality_480),
            getString(R.string.quality_audio)
        )
        qualitySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            qualityLabels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        findViewById<Button>(R.id.searchButton).setOnClickListener { doSearch() }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else {
                false
            }
        }

        downloadButton.setOnClickListener { doDownload() }

        observeStates()
        ensureLegacyStoragePermission()
        statusText.text = getString(R.string.status_idle)
        // TV first focus: the remote starts on the search field.
        searchInput.requestFocus()
    }

    private fun observeStates() {
        lifecycleScope.launch {
            repository.state.collect { state ->
                when (state) {
                    is VideoState.Idle -> {
                        loadingBar.visibility = View.GONE
                    }
                    is VideoState.Loading -> {
                        loadingBar.visibility = View.VISIBLE
                        statusText.text = getString(R.string.status_loading)
                    }
                    is VideoState.Results -> {
                        loadingBar.visibility = View.GONE
                        resultsAdapter.submit(state.items)
                        statusText.text = if (state.items.isEmpty()) {
                            getString(R.string.status_no_results)
                        } else {
                            ""
                        }
                    }
                    is VideoState.Ready -> {
                        loadingBar.visibility = View.GONE
                        currentStreams = state.streams
                        videoTitle.text = state.streams.title
                        videoMeta.text = state.streams.uploader
                        downloadButton.isEnabled = true
                        statusText.text = ""
                        playBestStream(state.streams)
                    }
                    is VideoState.Error -> {
                        loadingBar.visibility = View.GONE
                        statusText.text = getString(R.string.status_error)
                    }
                }
            }
        }
        lifecycleScope.launch {
            downloader.state.collect { state ->
                when (state) {
                    is DownloadState.Idle -> Unit
                    is DownloadState.Running -> {
                        statusText.text =
                            "${getString(R.string.status_downloading)} ${state.percent}%"
                    }
                    is DownloadState.Done -> {
                        statusText.text =
                            "${getString(R.string.status_download_done)} ${state.fileName} (${state.actualQuality})"
                    }
                    is DownloadState.Failed -> {
                        statusText.text =
                            "${getString(R.string.status_download_failed)} ${state.reason}"
                    }
                }
            }
        }
    }

    private fun doSearch() {
        val input = searchInput.text.toString().trim()
        if (input.isEmpty()) return
        val watchUrl = normalizeToWatchUrl(input)
        lifecycleScope.launch {
            if (watchUrl != null) {
                repository.loadStream(watchUrl)
            } else {
                repository.search(input)
            }
        }
    }

    private fun doDownload() {
        val streams = currentStreams ?: return
        if (!hasLegacyStoragePermission()) {
            requestLegacyStoragePermission()
            statusText.text = getString(R.string.status_error)
            return
        }
        val quality = when (qualitySpinner.selectedItemPosition) {
            0 -> DownloadQuality.P1080
            1 -> DownloadQuality.P720
            2 -> DownloadQuality.P480
            else -> DownloadQuality.AUDIO
        }
        lifecycleScope.launch {
            if (quality == DownloadQuality.AUDIO) {
                val audio = QualitySelector.selectAudio(streams.audioStreams)
                val audioUrl = audio?.url
                if (audioUrl == null) {
                    statusText.text = getString(R.string.status_download_failed)
                    return@launch
                }
                downloader.downloadAudio(
                    title = streams.title,
                    streamUrl = audioUrl,
                    extension = QualitySelector.audioExtension(audio)
                )
            } else {
                val selected = QualitySelector.selectVideo(streams.videoStreams, quality)
                val videoUrl = selected?.stream?.url
                if (videoUrl == null) {
                    statusText.text = getString(R.string.status_download_failed)
                    return@launch
                }
                downloader.downloadVideo(
                    title = streams.title,
                    streamUrl = videoUrl,
                    actualLabel = selected.actualLabel,
                    extension = QualitySelector.videoExtension(selected.stream)
                )
            }
        }
    }

    /**
     * Playback URL choice: best progressive stream first (audio included),
     * otherwise the DASH manifest (Media3 plays DASH natively with audio),
     * otherwise the first video stream as a last resort.
     */
    private fun playBestStream(streams: StreamSet) {
        val progressive = QualitySelector.selectVideo(streams.videoStreams, DownloadQuality.P1080)
        val url = progressive?.stream?.url
            ?: streams.dashMpdUrl
            ?: streams.videoStreams.firstOrNull()?.url
            ?: return
        playerManager.playUrl(url)
    }

    /**
     * Accepts a full watch URL, a youtu.be short link, or a bare 11-character
     * video id. Anything else is treated as a search term (returns null).
     */
    private fun normalizeToWatchUrl(input: String): String? {
        if (VIDEO_ID_REGEX.matches(input)) {
            return "https://www.youtube.com/watch?v=$input"
        }
        if (input.contains("youtu.be/")) {
            val id = input.substringAfter("youtu.be/").substringBefore("?").substringBefore("&")
            if (VIDEO_ID_REGEX.matches(id)) {
                return "https://www.youtube.com/watch?v=$id"
            }
        }
        if (input.contains("youtube.com/watch")) {
            return input
        }
        return null
    }

    // ---- Legacy storage permission (API 23–28 only) ----

    private fun hasLegacyStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT !in 23..28) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureLegacyStoragePermission() {
        if (Build.VERSION.SDK_INT in 23..28 && !hasLegacyStoragePermission()) {
            requestLegacyStoragePermission()
        }
    }

    private fun requestLegacyStoragePermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
            LEGACY_STORAGE_REQUEST
        )
    }

    override fun onResume() {
        super.onResume()
        // Returning from the "Install unknown apps" settings screen?
        // Install the already-downloaded update at once, no re-download.
        (application as LiteTubeApp).updateManager.installPendingUpdate()
    }

    override fun onPause() {
        super.onPause()
        playerManager.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        playerManager.release()
    }
}
