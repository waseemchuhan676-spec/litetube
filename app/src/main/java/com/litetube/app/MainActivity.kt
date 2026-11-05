package com.litetube.app

import android.Manifest
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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
 * Home feed (v1.4.0, personalized): on launch the app shows videos
 * related to the user's OWN recent searches (the most recent terms are
 * searched and merged, VidMate-style), with NO header/title on the home.
 * With no search history yet it falls back to the live kiosk feed so
 * the home is never empty. A search replaces the feed; clearing the
 * field restores the cached feed. Tapping any video auto-plays it
 * (playBestStream runs on every load). Every video row has a ⋮ button
 * opening a Download-only menu that downloads that video to the Gallery
 * via the same path as the player's Download button, and a small
 * thumbnail on the left (ThumbnailLoader, one 4 MB cache for both lists).
 *
 * Landing page (v1.4.0, VidMate-style): category chips under the search
 * bar (All restores the home feed, the others load a feed for that
 * category), a quick-access row (YouTube, WhatsApp, Facebook, Instagram,
 * Sites — each opens the app if installed, else its website), and a
 * bottom navigation bar (HOME / SHORTS / GAME / MY FILES / ME). The
 * player stays hidden until the first video loads, so the landing page
 * is the feed, not an empty black player. No ads, no sponsored cards.
 *
 * Download popup (v1.4.0): when a video starts playing, a card appears
 * directly below the player with a Download button (same download flow)
 * and a × to dismiss it until the next video.
 *
 * Settings (v1.5.0): the gear button opens SettingsActivity. The
 * quality spinner defaults to the Settings default quality on every
 * video load; the user can still override it per download. Every
 * download runs through the Settings gate first (WiFi-only, size
 * limits) — see downloadStreams().
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
    /** User settings (v1.5.0): default quality, WiFi-only, size limits. */
    private lateinit var settings: AppSettings
    /** True once the user has picked a quality in the spinner for the
     *  current video: the spinner then overrides the Settings default.
     *  Reset on every video load (see applyDefaultQuality). */
    private var spinnerTouchedByUser = false
    /** True while the code sets the spinner position: the listener must
     *  not treat the programmatic selection as a user override. */
    private var suppressSpinnerCallback = false

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
    private lateinit var playerView: PlayerView
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
    /** One shared thumbnail loader (one 4 MB cache) for both lists. */
    private lateinit var thumbnailLoader: ThumbnailLoader
    /** Download popup below the player (v1.4.0). */
    private lateinit var downloadPopup: View
    private lateinit var downloadPopupTitle: TextView
    private lateinit var downloadPopupButton: Button
    private lateinit var downloadPopupDismiss: Button
    /** Category chips (v1.4.0). */
    private lateinit var chipAll: TextView
    private lateinit var chipShorts: TextView
    private lateinit var chipMusic: TextView
    private lateinit var chipAction: TextView
    /** Bottom-nav tabs (v1.4.0): labels + icons, same order. */
    private lateinit var bottomTabLabels: List<TextView>
    private lateinit var bottomTabIcons: List<ImageView>

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
        /** Bottom-nav tab indexes, matching selectBottomTab(). */
        private const val TAB_HOME = 0
        private const val TAB_SHORTS = 1
        private const val TAB_GAME = 2
        private const val TAB_FILES = 3
        private const val TAB_ME = 4
        /** Category-feed search terms (chips + bottom nav). These are
         *  navigation, not user searches: they are never saved to the
         *  search history. */
        private const val QUERY_SHORTS = "shorts videos"
        private const val QUERY_MUSIC = "music"
        private const val QUERY_ACTION = "action movie trailers"
        private const val QUERY_GAMING = "gaming videos"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repository = YoutubeRepository()
        playerManager = PlayerManager(this, okHttpClient)
        playerView = findViewById(R.id.playerView)
        playerManager.bind(playerView)
        downloader = GalleryDownloader(this, okHttpClient)
        searchHistory = SearchHistory(this)
        settings = AppSettings(this)
        // One shared thumbnail loader (one 4 MB cache) for both lists.
        thumbnailLoader = ThumbnailLoader(okHttpClient, lifecycleScope)

        searchInput = findViewById(R.id.searchInput)
        statusText = findViewById(R.id.statusText)
        loadingBar = findViewById(R.id.loadingBar)
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
            onMenuClick = { item, anchor -> showRowMenu(item, anchor) },
            thumbnailLoader = thumbnailLoader
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
            onMenuClick = { item, anchor -> showRowMenu(item, anchor) },
            thumbnailLoader = thumbnailLoader
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
                // loaded, leave whatever results are showing. showHome()
                // also resets the selected chip to "All".
                if (s.isNullOrBlank() && !showingHomeFeed && cachedFeedItems.isNotEmpty()) {
                    showHome()
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
            getString(R.string.quality_360),
            getString(R.string.quality_audio)
        )
        qualitySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            qualityLabels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        // A user tap on the spinner overrides the Settings default for
        // this video (see effectiveDownloadQuality). Programmatic
        // selections (applyDefaultQuality) are suppressed so they never
        // count as an override. The listener is attached BEFORE the
        // first programmatic selection, so the attach-time callback is
        // suppressed too, and touched is reset right after.
        qualitySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (!suppressSpinnerCallback) spinnerTouchedByUser = true
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        applyDefaultQuality()

        findViewById<Button>(R.id.searchButton).setOnClickListener { doSearch() }

        // Settings (v1.5.0): gear button next to the search bar.
        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener { openSettings() }

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

        setupLandingPage()

        observeStates()
        ensureLegacyStoragePermission()
        statusText.text = getString(R.string.status_idle)
        // TV first focus: the remote starts on the search field.
        searchInput.requestFocus()
        // Home feed: suggested videos on launch, YouTube/VidMate-style.
        loadHomeFeed()
    }

    /**
     * Fetches the home feed once, on launch: videos related to the user's
     * own recent searches when there is any history, otherwise the live
     * kiosk feed as a fallback so the home is never empty. No header is
     * shown on the home (user choice) — the videos just appear under the
     * search bar. On any failure (or an empty feed) the home falls back
     * to the idle text — never an error state, never blocking search.
     */
    private fun loadHomeFeed() {
        lifecycleScope.launch {
            loadingBar.visibility = View.VISIBLE
            statusText.text = getString(R.string.status_loading)
            val history = searchHistory.load()
            val items = if (history.isEmpty()) {
                repository.loadHomeFeed()
            } else {
                repository.loadPersonalizedFeed(history)
            }
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
        resultsAdapter.submit(items)
        statusText.text = ""
    }

    // ---- Landing page (v1.4.0, VidMate-style) ----

    /**
     * Wires the chips, the quick-access shortcuts, the download popup
     * and the bottom navigation. Called once from onCreate. "All" starts
     * selected and HOME starts as the active tab.
     */
    private fun setupLandingPage() {
        chipAll = findViewById(R.id.chipAll)
        chipShorts = findViewById(R.id.chipShorts)
        chipMusic = findViewById(R.id.chipMusic)
        chipAction = findViewById(R.id.chipAction)
        chipAll.setOnClickListener { showHome() }
        chipShorts.setOnClickListener { loadCategory(QUERY_SHORTS, chipShorts) }
        chipMusic.setOnClickListener { loadCategory(QUERY_MUSIC, chipMusic) }
        chipAction.setOnClickListener { loadCategory(QUERY_ACTION, chipAction) }
        selectChip(chipAll)

        findViewById<View>(R.id.shortcutYouTube).setOnClickListener {
            openAppOrWeb("com.google.android.youtube", "https://www.youtube.com")
        }
        findViewById<View>(R.id.shortcutWhatsApp).setOnClickListener {
            openWhatsApp()
        }
        findViewById<View>(R.id.shortcutFacebook).setOnClickListener {
            openAppOrWeb("com.facebook.katana", "https://www.facebook.com")
        }
        findViewById<View>(R.id.shortcutInstagram).setOnClickListener {
            openAppOrWeb("com.instagram.android", "https://www.instagram.com")
        }
        findViewById<View>(R.id.shortcutSites).setOnClickListener {
            showSitesDialog()
        }

        downloadPopup = findViewById(R.id.downloadPopup)
        downloadPopupTitle = findViewById(R.id.downloadPopupTitle)
        downloadPopupButton = findViewById(R.id.downloadPopupButton)
        downloadPopupDismiss = findViewById(R.id.downloadPopupDismiss)
        downloadPopupButton.setOnClickListener {
            doDownload()
            hideDownloadPopup()
        }
        downloadPopupDismiss.setOnClickListener { hideDownloadPopup() }

        bottomTabLabels = listOf(
            findViewById(R.id.bottomHomeLabel),
            findViewById(R.id.bottomShortsLabel),
            findViewById(R.id.bottomGameLabel),
            findViewById(R.id.bottomFilesLabel),
            findViewById(R.id.bottomMeLabel)
        )
        bottomTabIcons = listOf(
            findViewById(R.id.bottomHomeIcon),
            findViewById(R.id.bottomShortsIcon),
            findViewById(R.id.bottomGameIcon),
            findViewById(R.id.bottomFilesIcon),
            findViewById(R.id.bottomMeIcon)
        )
        findViewById<View>(R.id.bottomHome).setOnClickListener {
            selectBottomTab(TAB_HOME)
            showHome()
        }
        findViewById<View>(R.id.bottomShorts).setOnClickListener {
            selectBottomTab(TAB_SHORTS)
            loadCategory(QUERY_SHORTS, chipShorts)
        }
        findViewById<View>(R.id.bottomGame).setOnClickListener {
            selectBottomTab(TAB_GAME)
            loadCategory(QUERY_GAMING, null)
        }
        findViewById<View>(R.id.bottomFiles).setOnClickListener {
            selectBottomTab(TAB_FILES)
            openDownloadsFolder()
        }
        findViewById<View>(R.id.bottomMe).setOnClickListener {
            selectBottomTab(TAB_ME)
            showMeDialog()
        }
        selectBottomTab(TAB_HOME)
    }

    /**
     * Marks [chip] as the selected category (red pill, white text) and
     * returns the others to the plain card style. Null clears the
     * selection (used by GAME, which has no chip).
     */
    private fun selectChip(chip: TextView?) {
        val allChips = listOf(chipAll, chipShorts, chipMusic, chipAction)
        for (c in allChips) {
            if (c == chip) {
                c.setBackgroundResource(R.drawable.chip_selected)
                c.setTextColor(ContextCompat.getColor(this, R.color.lt_surface))
            } else {
                c.setBackgroundResource(R.drawable.focus_border)
                c.setTextColor(ContextCompat.getColor(this, R.color.lt_text_primary))
            }
        }
    }

    /**
     * HOME (chip "All" or bottom nav): clear the search field and bring
     * the cached home feed back, or reload it when there is none yet.
     */
    private fun showHome() {
        suppressSuggestionRefresh = true
        searchInput.setText("")
        suppressSuggestionRefresh = false
        hideSuggestions()
        selectChip(chipAll)
        if (cachedFeedItems.isNotEmpty()) {
            showHomeFeed(cachedFeedItems)
        } else {
            loadHomeFeed()
        }
    }

    /**
     * A category feed (chip or bottom nav): show the query in the search
     * field and run it as a plain search. Deliberately NOT saved to the
     * search history — categories are navigation, not the user's own
     * searches. On failure the state observer shows the generic error
     * and the previous list stays as it was.
     */
    private fun loadCategory(query: String, chip: TextView?) {
        selectChip(chip)
        suppressSuggestionRefresh = true
        searchInput.setText(query)
        searchInput.setSelection(query.length)
        suppressSuggestionRefresh = false
        hideSuggestions()
        lifecycleScope.launch { repository.search(query) }
    }

    /**
     * Opens [webUrl] inside the app [packageName] when it is installed
     * (YouTube / Facebook / Instagram handle their own URLs), otherwise
     * in the browser. Nothing installed at all (no browser either):
     * the generic error status, never a crash.
     */
    private fun openAppOrWeb(packageName: String, webUrl: String) {
        val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl))
            .setPackage(packageName)
        try {
            startActivity(appIntent)
        } catch (e: ActivityNotFoundException) {
            openWeb(webUrl)
        }
    }

    /**
     * WhatsApp has no web URL that opens the app itself, so open the app
     * directly when installed; otherwise fall back to its website.
     */
    private fun openWhatsApp() {
        val launch = packageManager.getLaunchIntentForPackage("com.whatsapp")
        if (launch != null) {
            try {
                startActivity(launch)
                return
            } catch (e: ActivityNotFoundException) {
                // Fall through to the website below.
            }
        }
        openWeb("https://www.whatsapp.com")
    }

    /** Plain browser open, with the same never-crash fallback. */
    private fun openWeb(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            statusText.text = getString(R.string.status_error)
        }
    }

    /** "Sites" shortcut: a few more sites in a simple D-Pad list. */
    private fun showSitesDialog() {
        val sites = arrayOf("Google", "TikTok", "Dailymotion", "Vimeo")
        val urls = arrayOf(
            "https://www.google.com",
            "https://www.tiktok.com",
            "https://www.dailymotion.com",
            "https://vimeo.com"
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.sites_title)
            .setItems(sites) { _, which -> openWeb(urls[which]) }
            .setNegativeButton(R.string.me_close, null)
            .show()
    }

    /**
     * ME tab: app name, version and three actions — Settings, check for
     * updates (the existing UpdateManager, manual check) and the website.
     * A list dialog, not a new Activity (Settings itself is one).
     */
    private fun showMeDialog() {
        val version = getString(
            R.string.me_version_format,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE
        )
        val items = arrayOf(
            getString(R.string.me_settings),
            getString(R.string.me_check_updates),
            getString(R.string.me_visit_site)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(version)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openSettings()
                    1 -> {
                        (application as LiteTubeApp).updateManager.checkNow()
                        statusText.text = getString(R.string.me_checking)
                    }
                    2 -> openWeb("https://littletube.vercel.app")
                }
            }
            .setNegativeButton(R.string.me_close, null)
            .show()
    }

    /** Opens the professional Settings screen (v1.5.0). */
    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    /**
     * MY FILES: open the system's Downloads folder, where the Gallery
     * downloads land. DownloadManager.ACTION_VIEW_DOWNLOADS is the
     * standard entry point; on a device with no Downloads UI (rare,
     * some TV boxes) the status line says so instead of crashing.
     */
    private fun openDownloadsFolder() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (e: ActivityNotFoundException) {
            statusText.text = getString(R.string.status_no_file_manager)
        }
    }

    /** Highlights bottom tab [index] (red icon + label) and greys the rest. */
    private fun selectBottomTab(index: Int) {
        val selectedColor = ContextCompat.getColor(this, R.color.lt_red)
        val idleColor = ContextCompat.getColor(this, R.color.lt_text_secondary)
        for (i in bottomTabLabels.indices) {
            val active = i == index
            bottomTabLabels[i].setTextColor(if (active) selectedColor else idleColor)
            bottomTabIcons[i].imageTintList =
                ColorStateList.valueOf(if (active) selectedColor else idleColor)
        }
    }

    // ---- Download popup (v1.4.0) ----

    /**
     * Shows the download card below the player when a video starts
     * playing, with the video's title. The Download button runs the same
     * flow as the player's Download button; × hides the card until the
     * next video starts.
     */
    private fun showDownloadPopup(title: String) {
        downloadPopupTitle.text = title
        downloadPopup.visibility = View.VISIBLE
    }

    private fun hideDownloadPopup() {
        downloadPopup.visibility = View.GONE
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
                        hideDownloadPopup()
                    }
                    is VideoState.Results -> {
                        loadingBar.visibility = View.GONE
                        hideDownloadPopup()
                        resultsAdapter.submit(state.items)
                        statusText.text = if (state.items.isEmpty()) {
                            getString(R.string.status_no_results)
                        } else {
                            getString(R.string.status_results_count, state.items.size)
                        }
                        // A search replaces the home feed.
                        showingHomeFeed = false
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
                        // New video: the quality spinner goes back to the
                        // Settings default (v1.5.0) — any override the user
                        // made for the previous video does not carry over.
                        applyDefaultQuality()
                        statusText.text = ""
                        hideSuggestions()
                        // First video: reveal the player (GONE on the
                        // landing page) and the download popup below it.
                        playerView.visibility = View.VISIBLE
                        showDownloadPopup(state.streams.title)
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
                        hideDownloadPopup()
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
     * Sets the quality spinner to the Settings default (v1.5.0) and
     * clears the per-video override: called once at launch and on every
     * video load. The programmatic selection is suppressed so the
     * spinner listener does not mistake it for a user tap.
     * BEST shows as 1080p in the spinner (the closest label): for
     * progressive streams the two pick the same file in practice.
     */
    private fun applyDefaultQuality() {
        val position = when (settings.defaultQuality) {
            DownloadQuality.BEST, DownloadQuality.P1080 -> 0
            DownloadQuality.P720 -> 1
            DownloadQuality.P480 -> 2
            DownloadQuality.P360 -> 3
            DownloadQuality.AUDIO -> 0
        }
        suppressSpinnerCallback = true
        qualitySpinner.setSelection(position)
        suppressSpinnerCallback = false
        spinnerTouchedByUser = false
    }

    /**
     * The quality a download actually uses: the spinner's selection when
     * the user picked one for this video, otherwise the Settings default
     * (v1.5.0). The spinner never offers BEST, so an untouched spinner
     * with a BEST default still downloads BEST, not 1080p.
     */
    private fun effectiveDownloadQuality(): DownloadQuality {
        if (spinnerTouchedByUser) {
            return when (qualitySpinner.selectedItemPosition) {
                0 -> DownloadQuality.P1080
                1 -> DownloadQuality.P720
                2 -> DownloadQuality.P480
                3 -> DownloadQuality.P360
                else -> DownloadQuality.AUDIO
            }
        }
        return settings.defaultQuality
    }

    /**
     * The one download path, shared by the player's Download button, the
     * download popup and a row's ⋮ menu: legacy-storage permission
     * first, then the effective quality, then the Settings gate
     * (v1.5.0) — WiFi-only and the size limit for the current network —
     * and only then the Gallery download. Status messages come from the
     * downloader's state observer; a blocked download reports itself in
     * the same status line.
     *
     * The size used by the gate is an ESTIMATE (bitrate × duration —
     * the extractor reports no content length in v0.26.5, see
     * QualitySelector). An unknown size never blocks: the download goes
     * through, exactly as the spec requires.
     */
    private fun downloadStreams(streams: StreamSet) {
        if (!hasLegacyStoragePermission()) {
            requestLegacyStoragePermission()
            statusText.text = getString(R.string.status_error)
            return
        }
        val quality = effectiveDownloadQuality()
        lifecycleScope.launch {
            if (quality == DownloadQuality.AUDIO) {
                val audio = QualitySelector.selectAudio(streams.audioStreams)
                val audioUrl = audio?.url
                if (audioUrl == null) {
                    statusText.text = getString(R.string.status_download_failed)
                    return@launch
                }
                if (!checkDownloadGate(
                        QualitySelector.estimateAudioSizeBytes(audio, streams.durationSeconds)
                    )
                ) {
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
                if (!checkDownloadGate(
                        QualitySelector.estimateVideoSizeBytes(
                            selected.stream,
                            streams.durationSeconds
                        )
                    )
                ) {
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
     * Runs the Settings download gate for an (estimated) file size.
     * Returns true when the download may proceed; on a block it writes
     * the reason to the status line and returns false.
     */
    private fun checkDownloadGate(sizeBytes: Long): Boolean {
        return when (val gate = settings.checkDownload(sizeBytes, settings.currentNetworkType())) {
            DownloadGate.Allowed -> true
            DownloadGate.BlockedWifiOnly -> {
                statusText.text = getString(R.string.settings_download_blocked_wifi)
                false
            }
            is DownloadGate.BlockedSizeLimit -> {
                statusText.text = getString(
                    R.string.settings_download_blocked_limit,
                    gate.limitBytes / (1024L * 1024L)
                )
                false
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
