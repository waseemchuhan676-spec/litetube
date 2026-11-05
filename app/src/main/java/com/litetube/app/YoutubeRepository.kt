package com.litetube.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.VideoStream

/** One row in the search results list. [thumbnailUrl] is the smallest
 *  thumbnail the extractor returned for the video (v1.4.0, VidMate-style
 *  landing page) — empty when the extractor gave none, in which case
 *  the row shows the light-grey placeholder instead. */
data class VideoItem(
    val title: String,
    val uploader: String,
    val durationSeconds: Long,
    val url: String,
    val thumbnailUrl: String = ""
)

/** Everything the player and the downloader need for one video. */
data class StreamSet(
    val title: String,
    val uploader: String,
    val durationSeconds: Long,
    val pageUrl: String,
    val dashMpdUrl: String?,
    val videoStreams: List<VideoStream>,
    val audioStreams: List<AudioStream>,
    /** Videos YouTube lists as related to this one (empty when none). */
    val relatedItems: List<VideoItem> = emptyList()
)

sealed interface VideoState {
    data object Idle : VideoState
    data object Loading : VideoState
    data class Results(val items: List<VideoItem>) : VideoState
    data class Ready(val streams: StreamSet) : VideoState
    data class Error(val message: String) : VideoState
}

/**
 * All NewPipeExtractor access lives here, always on Dispatchers.IO
 * (every extractor call is blocking network I/O).
 * NewPipe is initialized exactly once for the whole process.
 */
class YoutubeRepository {

    private val _state = MutableStateFlow<VideoState>(VideoState.Idle)
    val state: StateFlow<VideoState> = _state.asStateFlow()

    init {
        synchronized(NewPipeInitLock) {
            if (!NewPipeInitLock.initialized) {
                NewPipe.init(OkHttpDownloader())
                NewPipeInitLock.initialized = true
            }
        }
    }

    private object NewPipeInitLock {
        var initialized: Boolean = false
    }

    suspend fun search(query: String) {
        _state.value = VideoState.Loading
        _state.value = try {
            VideoState.Results(fetchSearchItems(query, MAX_SEARCH_RESULTS))
        } catch (e: Exception) {
            VideoState.Error(e.message ?: "Search failed")
        }
    }

    /**
     * The search fetch itself, without touching the shared state flow:
     * initial page + following pages until [maxResults] are gathered (or
     * the pages run out). Throws on a first-page failure, exactly like
     * search() used to; callers that must not fail (the home feed) catch
     * per term. A failed next-page fetch keeps the results gathered so
     * far instead of throwing them away.
     */
    private suspend fun fetchSearchItems(query: String, maxResults: Int): List<VideoItem> {
        return withContext(Dispatchers.IO) {
            val extractor = ServiceList.YouTube.getSearchExtractor(query)
            extractor.fetchPage()
            val found = mutableListOf<VideoItem>()
            var page = extractor.initialPage
            var pagesFetched = 0
            // The initial page alone often holds only a handful of videos
            // (YouTube's layout changes break single-page parsing), so keep
            // fetching following pages until we have enough or run out.
            // The page cap stops an endless walk if a page keeps coming
            // back empty but still claims a next page.
            while (found.size < maxResults && pagesFetched < MAX_SEARCH_PAGES) {
                pagesFetched++
                found += page.items
                    .filterIsInstance<StreamInfoItem>()
                    .map { item ->
                        VideoItem(
                            title = item.name,
                            uploader = item.uploaderName,
                            durationSeconds = item.duration,
                            url = item.url,
                            thumbnailUrl = smallestThumbnail(item)
                        )
                    }
                val nextPage = try {
                    if (page.hasNextPage()) page.nextPage else null
                } catch (e: Exception) {
                    null
                } ?: break
                page = try {
                    extractor.getPage(nextPage)
                } catch (e: Exception) {
                    // A failed next-page fetch must not throw away the
                    // results already gathered: stop here and return them.
                    break
                }
            }
            found.take(maxResults)
        }
    }

    /**
     * Personalized home feed (v1.4.0): videos related to the user's OWN
     * searches, not generic live videos. The caller passes the saved
     * recent searches (newest first); the most recent terms are searched
     * one by one (a few results each), merged in term order and deduped
     * by video URL. A term whose fetch fails is skipped, never crashing
     * the feed; an empty result here means the caller falls back (to the
     * live kiosk feed when there is no history, to the idle text
     * otherwise). Sequential fetches on purpose: light on 1 GB TVs and
     * on slow connections, and only the initial page is usually needed
     * for a handful of results per term.
     */
    suspend fun loadPersonalizedFeed(terms: List<String>): List<VideoItem> {
        val seen = LinkedHashSet<String>()
        val merged = mutableListOf<VideoItem>()
        for (term in terms.take(MAX_FEED_TERMS)) {
            if (merged.size >= MAX_HOME_FEED_ITEMS) break
            val items = try {
                fetchSearchItems(term, MAX_PERSONALIZED_PER_TERM)
            } catch (e: Exception) {
                emptyList()
            }
            for (item in items) {
                if (seen.add(item.url)) merged += item
            }
        }
        return merged.take(MAX_HOME_FEED_ITEMS)
    }

    /**
     * Home feed (v1.3.0): YouTube's suggested videos for the app's home
     * screen, so the main page is not empty like VidMate/YouTube.
     *
     * API (NewPipeExtractor v0.26.5, read from the extractor's own source
     * at that tag): ServiceList.YouTube.getKioskList() returns a KioskList;
     * KioskList.getDefaultKioskExtractor() returns the default kiosk's
     * extractor (KioskExtractor extends ListExtractor, so fetchPage() and
     * initialPage work exactly like the search extractor). NOTE — the
     * default kiosk is NOT "Trending": YoutubeService.getKioskList()
     * registers Trending as deprecated ("removed from the interface of
     * YouTube since July 21, 2025") and calls
     * setDefaultKiosk(YoutubeLiveLinkHandlerFactory.KIOSK_ID) — i.e. the
     * default feed is the currently-live videos (KIOSK_ID = "live"). The
     * old "Trending" kiosk is therefore NOT used; using the default keeps
     * the feed working whatever YouTube's default kiosk is. There is no
     * getKioskExtractorById() in v0.26.5 (the method is getExtractorById).
     *
     * Returns an empty list on any failure (no network, YouTube blocks
     * it, empty kiosk): the caller falls back to the idle text, never
     * an error state. Only the initial page is fetched — light enough
     * for 1 GB TVs, and the list is capped like search results.
     */
    suspend fun loadHomeFeed(): List<VideoItem> {
        return try {
            withContext(Dispatchers.IO) {
                val extractor = ServiceList.YouTube.getKioskList()
                    .getDefaultKioskExtractor()
                    ?: return@withContext emptyList()
                extractor.fetchPage()
                extractor.initialPage.items
                    .filterIsInstance<StreamInfoItem>()
                    .take(MAX_HOME_FEED_ITEMS)
                    .map { item ->
                        VideoItem(
                            title = item.name,
                            uploader = item.uploaderName,
                            durationSeconds = item.duration,
                            url = item.url,
                            thumbnailUrl = smallestThumbnail(item)
                        )
                    }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Resolves a list item's page URL to its streams WITHOUT touching the
     * shared state flow, so a row Download never starts playback or
     * changes what the screen shows. Returns null when resolution fails.
     * Only the streams are needed here (related items are left empty).
     */
    suspend fun resolveStream(pageUrl: String): StreamSet? {
        return try {
            val info = withContext(Dispatchers.IO) {
                StreamInfo.getInfo(ServiceList.YouTube, pageUrl)
            }
            StreamSet(
                title = info.name,
                uploader = info.uploaderName,
                durationSeconds = info.duration,
                pageUrl = pageUrl,
                dashMpdUrl = info.dashMpdUrl,
                videoStreams = info.videoStreams,
                audioStreams = info.audioStreams
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * YouTube's autocomplete keywords for [query] — what other apps show
     * under the search bar while typing. Returns an empty list when the
     * service has no suggestion extractor or the request fails; callers
     * treat that as "no suggestions", never as an error.
     *
     * API (NewPipeExtractor, stable for years and used by the NewPipe app
     * itself): StreamingService.getSuggestionExtractor() returns a
     * SuggestionExtractor (nullable); SuggestionExtractor.suggestionList(query)
     * returns List<String>.
     */
    suspend fun suggestions(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        return try {
            withContext(Dispatchers.IO) {
                ServiceList.YouTube.getSuggestionExtractor()
                    ?.suggestionList(query)
                    .orEmpty()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun loadStream(pageUrl: String) {
        _state.value = VideoState.Loading
        _state.value = try {
            val info = withContext(Dispatchers.IO) {
                StreamInfo.getInfo(ServiceList.YouTube, pageUrl)
            }
            VideoState.Ready(
                StreamSet(
                    title = info.name,
                    uploader = info.uploaderName,
                    durationSeconds = info.duration,
                    pageUrl = pageUrl,
                    dashMpdUrl = info.dashMpdUrl,
                    videoStreams = info.videoStreams,
                    audioStreams = info.audioStreams,
                    relatedItems = info.relatedItems.orEmpty()
                        .filterIsInstance<StreamInfoItem>()
                        .take(MAX_RELATED_ITEMS)
                        .map { item ->
                            VideoItem(
                                title = item.name,
                                uploader = item.uploaderName,
                                durationSeconds = item.duration,
                                url = item.url,
                                thumbnailUrl = smallestThumbnail(item)
                            )
                        }
                )
            )
        } catch (e: Exception) {
            VideoState.Error(e.message ?: "Could not load this video")
        }
    }

    /**
     * The smallest thumbnail the extractor returned for a video (v1.4.0):
     * YouTube usually lists several sizes per video (120x90 default.jpg,
     * 320x180 mqdefault, 480x360 hqdefault, ...). The smallest keeps the
     * download tiny and the bitmap ~120x90 on 1 GB TVs. Unknown sizes
     * (-1) are ignored; a "default.jpg" URL is the fallback; empty when
     * the extractor gave no thumbnails at all (the row then shows the
     * placeholder). Never throws.
     */
    private fun smallestThumbnail(item: StreamInfoItem): String {
        val images = try {
            item.thumbnails
        } catch (e: Exception) {
            emptyList()
        }
        if (images.isEmpty()) return ""
        val smallest = images
            .filter { it.width > 0 && it.height > 0 }
            .minByOrNull { it.width * it.height }
            ?: images.firstOrNull { it.url.contains("default.jpg") }
            ?: images.firstOrNull()
        return smallest?.url ?: ""
    }

    companion object {
        /** How many search results to gather (initial page + next pages). */
        private const val MAX_SEARCH_RESULTS = 30
        /** Page-fetch cap for one search, so an empty-but-paged result set
         *  cannot loop forever. Initial page + up to 3 following pages. */
        private const val MAX_SEARCH_PAGES = 4
        /** How many related videos to keep for one loaded video. */
        private const val MAX_RELATED_ITEMS = 20
        /** How many videos the home feed shows (initial kiosk page only). */
        private const val MAX_HOME_FEED_ITEMS = 20
        /** How many of the user's most recent search terms the
         *  personalized home feed is built from. */
        private const val MAX_FEED_TERMS = 3
        /** How many videos each feed term contributes at most. */
        private const val MAX_PERSONALIZED_PER_TERM = 8
    }
}
