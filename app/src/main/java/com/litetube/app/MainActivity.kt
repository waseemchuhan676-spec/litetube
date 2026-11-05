package com.litetube.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Orchestrates everything, in this order:
 * search input -> repository (NewPipeExtractor) -> PlayerManager for
 * playback -> GalleryDownloader for the Download button.
 *
 * Search extras (v1.1.0): while typing, YouTube suggestions merged with
 * the user's own recent searches show under the search bar; after a video
 * loads, its related videos show in a second list under the player.
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
    private lateinit var relatedAdapter: SearchResultsAdapter
    private lateinit var suggestionsAdapter: SuggestionAdapter
    private lateinit var searchHistory: SearchHistory

    private var currentStreams: StreamSet? = null
    private var suggestionJob: Job? = null
    /** True while the code itself sets the search text (picking a
     *  suggestion): the TextWatcher must not re-fire a suggestion fetch. */
    private var suppressSuggestionRefresh = false

    private lateinit var searchInput: EditText
    private lateinit var statusText: TextView
    private lateinit var loadingBar: ProgressBar
    private lateinit var videoTitle: TextView
    private lateinit var videoMeta: TextView
    private lateinit var qualitySpinner: Spinner
    private lateinit var downloadButton: Button
    private lateinit var suggestionsHeaderRow: View
    private lateinit var suggestionsHeader: TextView
    private lateinit var clearHistory: TextView
    private lateinit var suggestionsList: RecyclerView
    private lateinit var relatedHeader: TextView
    private lateinit var relatedList: RecyclerView

    companion object {
        private const val LEGACY_STORAGE_REQUEST = 1001
        private val VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")
        /** Wait after the last keystroke before asking YouTube for
         *  suggestions, so one request per pause instead of per letter. */
        private const val SUGGESTION_DEBOUNCE_MS = 300L
        /** Most keyword rows shown under the search bar at once. */
        private const val MAX_SUGGESTIONS = 8
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repository = YoutubeRepository()
        playerManager = PlayerManager(this, okHttpClient)
        playerManager.bind(findViewById(R.id.playerView))
        downloader = GalleryDownloader(this, okHttpClient)
        searchHistory = SearchHistory(this)

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

        suggestionsHeaderRow = findViewById(R.id.suggestionsHeaderRow)
        suggestionsHeader = findViewById(R.id.suggestionsHeader)
        clearHistory = findViewById(R.id.clearHistory)
        suggestionsList = findViewById(R.id.suggestionsList)
        suggestionsList.layoutManager = LinearLayoutManager(this)
        suggestionsAdapter = SuggestionAdapter { keyword -> pickSuggestion(keyword) }
        suggestionsList.adapter = suggestionsAdapter

        relatedHeader = findViewById(R.id.relatedHeader)
        relatedList = findViewById(R.id.relatedList)
        relatedList.layoutManager = LinearLayoutManager(this)
        relatedAdapter = SearchResultsAdapter { item ->
            lifecycleScope.launch { repository.loadStream(item.url) }
        }
        relatedList.adapter = relatedAdapter

        clearHistory.setOnClickListener {
            searchHistory.clear()
            refreshSuggestions()
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (suppressSuggestionRefresh) return
                refreshSuggestions()
            }
        })
        searchInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                refreshSuggestions()
            } else {
                // Don't hide while the user moves focus into the suggestions
                // themselves (D-Pad DOWN from the field): the list must stay
                // up while its rows are being browsed.
                val next = currentFocus
                val browsingSuggestions = next === clearHistory ||
                    next === suggestionsList || suggestionsList.findFocus() != null
                if (!browsingSuggestions) hideSuggestions()
            }
        }

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
                            getString(R.string.status_results_count, state.items.size)
                        }
                        // A fresh search makes the previous video's related
                        // list stale: hide it until a video loads again.
                        relatedAdapter.submit(emptyList())
                        relatedHeader.visibility = View.GONE
                        relatedList.visibility = View.GONE
                    }
                    is VideoState.Ready -> {
                        loadingBar.visibility = View.GONE
                        currentStreams = state.streams
                        videoTitle.text = state.streams.title
                        videoMeta.text = state.streams.uploader
                        downloadButton.isEnabled = true
                        statusText.text = ""
                        hideSuggestions()
                        if (state.streams.relatedItems.isEmpty()) {
                            relatedHeader.visibility = View.GONE
                            relatedList.visibility = View.GONE
                        } else {
                            relatedAdapter.submit(state.streams.relatedItems)
                            relatedHeader.visibility = View.VISIBLE
                            relatedList.visibility = View.VISIBLE
                        }
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
        hideSuggestions()
        val watchUrl = normalizeToWatchUrl(input)
        lifecycleScope.launch {
            if (watchUrl != null) {
                repository.loadStream(watchUrl)
            } else {
                // A real search term (not a link or video id): remember it
                // for the recent-searches list under the search bar.
                searchHistory.save(input)
                repository.search(input)
            }
        }
    }

    /**
     * Refresh what shows under the search bar, for the text that is there
     * right now. Field focused and empty: the user's own recent searches.
     * Typing: matching recent searches first, then YouTube's recommended
     * keywords for what was typed (debounced, one request per pause).
     */
    private fun refreshSuggestions() {
        val text = searchInput.text.toString().trim()
        suggestionJob?.cancel()
        if (text.isEmpty()) {
            showSuggestions(
                items = searchHistory.load(),
                header = getString(R.string.recent_searches_header),
                showClear = true
            )
            return
        }
        if (normalizeToWatchUrl(text) != null) {
            // A link or video id gets no keyword suggestions.
            hideSuggestions()
            return
        }
        suggestionJob = lifecycleScope.launch {
            delay(SUGGESTION_DEBOUNCE_MS)
            val query = searchInput.text.toString().trim()
            if (query.isEmpty() || query != text) return@launch
            val remote = repository.suggestions(query)
            val history = searchHistory.load()
                .filter { it.startsWith(query, ignoreCase = true) }
            val merged = (history + remote.filter { suggestion ->
                history.none { it.equals(suggestion, ignoreCase = true) }
            }).take(MAX_SUGGESTIONS)
            showSuggestions(
                items = merged,
                header = getString(R.string.suggestions_header),
                showClear = false
            )
        }
    }

    private fun showSuggestions(items: List<String>, header: String, showClear: Boolean) {
        if (items.isEmpty()) {
            hideSuggestions()
            return
        }
        suggestionsHeader.text = header
        clearHistory.visibility = if (showClear) View.VISIBLE else View.GONE
        suggestionsHeaderRow.visibility = View.VISIBLE
        suggestionsAdapter.submit(items)
        suggestionsList.visibility = View.VISIBLE
    }

    private fun hideSuggestions() {
        suggestionJob?.cancel()
        suggestionsHeaderRow.visibility = View.GONE
        suggestionsList.visibility = View.GONE
    }

    /** A suggestion (or recent search) was tapped: run that search. */
    private fun pickSuggestion(keyword: String) {
        suppressSuggestionRefresh = true
        searchInput.setText(keyword)
        searchInput.setSelection(keyword.length)
        suppressSuggestionRefresh = false
        hideSuggestions()
        doSearch()
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
