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
    val audioStreams: List<AudioStream>
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
                extractor.initialPage.items
                    .filterIsInstance<StreamInfoItem>()
                    .map { item ->
                        VideoItem(
                            title = item.name,
                            uploader = item.uploaderName,
                            durationSeconds = item.duration,
                            url = item.url
                        )
                    }
            }
            VideoState.Results(items)
        } catch (e: Exception) {
            VideoState.Error(e.message ?: "Search failed")
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
                    audioStreams = info.audioStreams
                )
            )
        } catch (e: Exception) {
            VideoState.Error(e.message ?: "Could not load this video")
        }
    }
}
