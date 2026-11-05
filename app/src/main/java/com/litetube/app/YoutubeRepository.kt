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

/** One row in the search results list. Thumbnails are intentionally skipped:
 *  loading images would need an image library and extra RAM on 1 GB TVs. */
data class VideoItem(
    val title: String,
    val uploader: String,
    val durationSeconds: Long,
    val url: String
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
            val items = withContext(Dispatchers.IO) {
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
                while (found.size < MAX_SEARCH_RESULTS && pagesFetched < MAX_SEARCH_PAGES) {
                    pagesFetched++
                    found += page.items
                        .filterIsInstance<StreamInfoItem>()
                        .map { item ->
                            VideoItem(
                                title = item.name,
                                uploader = item.uploaderName,
                                durationSeconds = item.duration,
                                url = item.url
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
                found.take(MAX_SEARCH_RESULTS)
            }
            VideoState.Results(items)
        } catch (e: Exception) {
            VideoState.Error(e.message ?: "Search failed")
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
                                url = item.url
                            )
                        }
                )
            )
        } catch (e: Exception) {
            VideoState.Error(e.message ?: "Could not load this video")
        }
    }

    companion object {
        /** How many search results to gather (initial page + next pages). */
        private const val MAX_SEARCH_RESULTS = 30
        /** Page-fetch cap for one search, so an empty-but-paged result set
         *  cannot loop forever. Initial page + up to 3 following pages. */
        private const val MAX_SEARCH_PAGES = 4
        /** How many related videos to keep for one loaded video. */
        private const val MAX_RELATED_ITEMS = 20
    }
}
