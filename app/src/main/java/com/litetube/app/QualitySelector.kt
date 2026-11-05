package com.litetube.app

import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.VideoStream

enum class DownloadQuality {
    /** Best progressive stream available, whatever its height. */
    BEST, P1080, P720, P480, P360, AUDIO
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
        val progressive = streams.filter { !it.isVideoOnly }
        if (progressive.isEmpty()) return null
        if (wanted == DownloadQuality.BEST) {
            // Best available: the tallest progressive stream, no cap.
            // (Progressive streams top out at 720p for most videos, 1080p
            // for some — BEST and P1080 are usually the same file.)
            val chosen = progressive.maxByOrNull { heightOf(it) } ?: return null
            return SelectedVideo(chosen, chosen.resolution)
        }
        val wantedHeight = when (wanted) {
            DownloadQuality.P1080 -> 1080
            DownloadQuality.P720 -> 720
            DownloadQuality.P480 -> 480
            DownloadQuality.P360 -> 360
            DownloadQuality.BEST, DownloadQuality.AUDIO -> return null
        }
        val fitting = progressive.filter { heightOf(it) <= wantedHeight }
        val chosen = fitting.maxByOrNull { heightOf(it) }
            ?: progressive.minByOrNull { heightOf(it) }
            ?: return null
        return SelectedVideo(chosen, chosen.resolution)
    }

    fun selectAudio(streams: List<AudioStream>): AudioStream? =
        streams.maxByOrNull { it.averageBitrate }

    /**
     * Estimated file size in bytes, from the stream's bitrate and the
     * video duration. The extractor reports NO content length
     * (org.schabi.newpipe.extractor.stream.Stream has no such field in
     * v0.26.5 — verified against the extractor source), so this is an
     * ESTIMATE, not an exact size: for progressive YouTube streams the
     * itag bitrate is the combined audio+video bitrate, which is what
     * the file actually contains. Returns -1 when the bitrate or the
     * duration is unknown — the download gate NEVER blocks on an
     * unknown size, it lets the download through.
     */
    fun estimateVideoSizeBytes(stream: VideoStream, durationSeconds: Long): Long {
        val bps = stream.bitrate
        if (bps <= 0 || durationSeconds <= 0) return -1L
        return bps.toLong() * durationSeconds / 8L
    }

    /** Same estimate for an audio stream (average bitrate). */
    fun estimateAudioSizeBytes(stream: AudioStream, durationSeconds: Long): Long {
        val bps = stream.averageBitrate
        if (bps <= 0 || durationSeconds <= 0) return -1L
        return bps.toLong() * durationSeconds / 8L
    }

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
