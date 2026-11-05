package com.litetube.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
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
import java.util.Locale

/**
 * Orchestrates everything, in this order:
 * search input -> repository (NewPipeExtractor) -> PlayerManager for
 * playback -> GalleryDownloader for the Download button.
 *
 * Search extras (v1.1.0): while typing, YouTube suggestions merged with
 * the user's own recent searches show under the search bar; after a video
 * loads, its related videos show in a second list under the player.
 *
 * Voice search (v1.2.0): the mic button in the search bar listens with
 * SpeechRecognizer and searches what was heard, exactly like typed text.
 * The button is hidden on devices with no recognizer (common on TV boxes
 * without the Google app), and the mic permission is asked only when the
 * button is tapped, never at app start.
 *
 * Home feed (v1.3.0): on launch the app shows YouTube's suggested videos
 * (the default kiosk feed) in the results list under a "Suggested videos"
 * header, YouTube/VidMate-style, so the home is not empty. A search
 * replaces the feed; clearing the field restores the cached feed.
 * Tapping any video auto-plays it (playBestStream runs on every load).
 * Every video row has a ⋮ button opening a Download-only menu that
 * downloads that video to the Gallery via the same path as the player's
 * Download button.
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
    /** True while the results list shows the home feed (not search results). */
    private var showingHomeFeed = false
    /** Last successfully loaded feed, so clearing the search field can
     *  restore the home feed without another network call. */
    private var cachedFeedItems: List<VideoItem> = emptyList()
    /** True while the code itself sets the search text (picking a
     *  suggestion): the TextWatcher must not re-fire a suggestion fetch. */
    private var suppressSuggestionRefresh = false
    /** Voice search state: the live recognizer, if one is listening. */
    private var speechRecognizer: SpeechRecognizer? = null
    private var listening = false

    private lateinit var searchInput: EditText
    private lateinit var voiceButton: ImageButton
    private lateinit var statusText: TextView
    private lateinit var loadingBar: ProgressBar
    private lateinit var feedHeader: TextView
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
        private const val VOICE_REQUEST = 1002
        /** Menu item id for the row ⋮ menu's only entry, Download. */
        private const val MENU_DOWNLOAD_ID = 1
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
        feedHeader = findViewById(R.id.feedHeader)
        videoTitle = findViewById(R.id.videoTitle)
        videoMeta = findViewById(R.id.videoMeta)
        qualitySpinner = findViewById(R.id.qualitySpinner)
        downloadButton = findViewById(R.id.downloadButton)

        val resultsList = findViewById<RecyclerView>(R.id.resultsList)
        resultsList.layoutManager = LinearLayoutManager(this)
        resultsAdapter = SearchResultsAdapter(
            onItemClick = { item ->
                lifecycleScope.launch { repository.loadStream(item.url) }
            },
            onMenuClick = { item, anchor -> showRowMenu(item, anchor) }
        )
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
        relatedAdapter = SearchResultsAdapter(
            onItemClick = { item ->
                lifecycleScope.launch { repository.loadStream(item.url) }
            },
            onMenuClick = { item, anchor -> showRowMenu(item, anchor) }
        )
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
                // Field cleared back to empty: bring the home feed back
                // from the cache (no network call). If the feed never
                // loaded, leave whatever results are showing.
                if (s.isNullOrBlank() && !showingHomeFeed && cachedFeedItems.isNotEmpty()) {
                    showHomeFeed(cachedFeedItems)
                }
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

        voiceButton = findViewById(R.id.voiceButton)
        voiceButton.setOnClickListener { onVoiceButtonClicked() }
        // No speech recognizer on this device (no Google app / no mic,
        // common on TV boxes): hide the mic entirely so the UI never
        // shows a dead button. GONE views are skipped by D-Pad focus.
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            voiceButton.visibility = View.GONE
        }
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
        // Home feed: suggested videos on launch, YouTube/VidMate-style.
        loadHomeFeed()
    }

    /**
     * Fetches the home feed once, on launch. On success the videos show
     * in the results list under the "Suggested videos" header. On any
     * failure (or an empty feed) the home falls back to the idle text —
     * never an error state, never blocking search.
     */
    private fun loadHomeFeed() {
        lifecycleScope.launch {
            loadingBar.visibility = View.VISIBLE
            statusText.text = getString(R.string.status_loading)
            val items = repository.loadHomeFeed()
            // Remember the feed even if it arrives too late to show: the
            // user may still clear the field later and restore it.
            if (items.isNotEmpty()) cachedFeedItems = items
            // The user may have searched (or loaded a video) while the
            // feed was loading: never let the late feed overwrite search
            // results or a loaded video — only show it on a still-empty home.
            if (searchInput.text.isNullOrBlank()) {
                loadingBar.visibility = View.GONE
                if (items.isEmpty()) {
                    statusText.text = getString(R.string.status_idle)
                } else {
                    showHomeFeed(items)
                }
            }
        }
    }

    /** Shows [items] as the home feed and remembers them for restore. */
    private fun showHomeFeed(items: List<VideoItem>) {
        cachedFeedItems = items
        showingHomeFeed = true
        feedHeader.visibility = View.VISIBLE
        resultsAdapter.submit(items)
        statusText.text = ""
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
                        // A search replaces the home feed: hide its header.
                        showingHomeFeed = false
                        feedHeader.visibility = View.GONE
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

    // ---- Voice search (mic button in the search bar) ----

    /**
     * Mic tap: if already listening, tapping again stops. Otherwise the
     * mic permission is requested first (API 23+ only, and only on tap —
     * never at app start); once granted, listening starts.
     */
    private fun onVoiceButtonClicked() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        if (listening) {
            speechRecognizer?.stopListening()
            return
        }
        if (Build.VERSION.SDK_INT >= 23 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                VOICE_REQUEST
            )
            return
        }
        startListening()
    }

    /**
     * Listens with the system speech recognizer, in the device default
     * language (never hardcoded). The transcript goes into the search
     * field and runs the normal search, so history saving, suggestions
     * and the link/id path all behave exactly like typed text.
     */
    private fun startListening() {
        val recognizer = try {
            SpeechRecognizer.createSpeechRecognizer(this)
        } catch (e: Exception) {
            null
        } ?: run {
            statusText.text = getString(R.string.status_error)
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                statusText.text = getString(R.string.status_listening)
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onError(error: Int) {
                // onPause cancels the recognizer and clears `listening`
                // first: that late error is not a real failure, ignore it.
                if (!listening) return
                listening = false
                speechRecognizer?.destroy()
                speechRecognizer = null
                statusText.text = getString(R.string.status_error)
            }

            override fun onResults(results: Bundle?) {
                if (!listening) return
                listening = false
                speechRecognizer?.destroy()
                speechRecognizer = null
                val heard = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                if (heard.isEmpty()) {
                    statusText.text = getString(R.string.status_error)
                    return
                }
                suppressSuggestionRefresh = true
                searchInput.setText(heard)
                searchInput.setSelection(heard.length)
                suppressSuggestionRefresh = false
                doSearch()
            }
        })
        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            recognizer.destroy()
            statusText.text = getString(R.string.status_error)
            return
        }
        speechRecognizer = recognizer
        listening = true
        statusText.text = getString(R.string.status_listening)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == VOICE_REQUEST) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                startListening()
            } else {
                // Clear rationale: voice search cannot work without the mic.
                statusText.text = getString(R.string.status_voice_permission_denied)
            }
        }
        // NOTE: the legacy storage request has no result handling here by
        // design — it was fire-and-forget before this change and stays so.
    }

    private fun doDownload() {
        val streams = currentStreams ?: return
        downloadStreams(streams)
    }

    /**
     * The one download path, shared by the player's Download button and by
     * a row's ⋮ menu: legacy-storage permission first, then the quality
     * the spinner currently shows, then the Gallery download. Status
     * messages come from the downloader's state observer.
     */
    private fun downloadStreams(streams: StreamSet) {
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
     * Row ⋮ menu -> Download: the list item has no resolved streams yet,
     * so resolve them first (one network call, no playback and no state
     * change — resolveStream never touches the shared state flow), then
     * download through the same path as the player's Download button.
     */
    private fun downloadRowVideo(item: VideoItem) {
        lifecycleScope.launch {
            statusText.text = getString(R.string.status_loading)
            val streams = repository.resolveStream(item.url)
            if (streams == null) {
                statusText.text = getString(R.string.status_error)
                return@launch
            }
            downloadStreams(streams)
        }
    }

    /** Row ⋮ menu (VidMate-style): Download only, nothing else. */
    private fun showRowMenu(item: VideoItem, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_DOWNLOAD_ID, 0, getString(R.string.download))
        popup.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == MENU_DOWNLOAD_ID) {
                downloadRowVideo(item)
                true
            } else {
                false
            }
        }
        popup.show()
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
        // Never leave the mic open while the activity is not visible:
        // clear `listening` first so the cancel's late onError is ignored.
        listening = false
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        playerManager.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        speechRecognizer = null
        playerManager.release()
    }
}
