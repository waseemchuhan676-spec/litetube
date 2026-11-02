package com.litetube.app

import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.VideoStream

enum class DownloadQuality {
    P1080, P720, P480, AUDIO
}

/** A progressive (combined audio+video) stream plus the label to show. */
data class SelectedVideo(val stream: VideoStream, val actualLabel: String)

/**
 * Pure stream-picking logic, kept separate so it can be unit-tested without
 * Android. The honest rule that drives it:
 *
 * YouTube only ships combined (progressive) streams up to 720p for most
 * videos. 1080p is usually DASH-only (video and audio on separate streams),
 * which a single-file download cannot reproduce. So a request for 1080p
 * downloads the best progressive stream at or below 1080p and reports the
 * ACTUAL quality in the UI — never a silent 1080p file that is really 720p,
 * and never a silent video-only file.
 */
object QualitySelector {

    private fun heightOf(stream: VideoStream): Int =
        stream.resolution.filter { it.isDigit() }.toIntOrNull() ?: 0

    fun selectVideo(streams: List<VideoStream>, wanted: DownloadQuality): SelectedVideo? {
        val wantedHeight = when (wanted) {
            DownloadQuality.P1080 -> 1080
            DownloadQuality.P720 -> 720
            DownloadQuality.P480 -> 480
            DownloadQuality.AUDIO -> return null
        }
        val progressive = streams.filter { !it.isVideoOnly }
        if (progressive.isEmpty()) return null
        val fitting = progressive.filter { heightOf(it) <= wantedHeight }
        val chosen = fitting.maxByOrNull { heightOf(it) }
            ?: progressive.minByOrNull { heightOf(it) }
            ?: return null
        return SelectedVideo(chosen, chosen.resolution)
    }

    fun selectAudio(streams: List<AudioStream>): AudioStream? =
        streams.maxByOrNull { it.averageBitrate }

    fun videoExtension(stream: VideoStream): String =
        stream.format?.suffix?.takeIf { it.isNotBlank() } ?: "mp4"

    fun audioExtension(stream: AudioStream): String =
        stream.format?.suffix?.takeIf { it.isNotBlank() } ?: "m4a"

    fun videoMimeType(extension: String): String = when (extension.lowercase()) {
        "webm" -> "video/webm"
        else -> "video/mp4"
    }

    fun audioMimeType(extension: String): String = when (extension.lowercase()) {
        "opus" -> "audio/opus"
        "webm" -> "audio/webm"
        else -> "audio/mp4"
    }

    /** File-safe title: strip characters the filesystem and MediaStore reject. */
    fun safeFileName(title: String): String {
        val cleaned = title.replace(Regex("[^A-Za-z0-9._\\- ]"), "_").trim()
        val trimmed = if (cleaned.length > 80) cleaned.substring(0, 80).trim() else cleaned
        return trimmed.ifBlank { "video" }
    }
}
