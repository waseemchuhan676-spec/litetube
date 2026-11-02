package com.litetube.app

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient

/**
 * Thin Media3 wrapper. The only tuning that matters on 1–2 GB TV boxes is
 * the buffer: small enough to never OOM, large enough to survive slow
 * connections. 15s min / 30s max / 2.5s to start / 5s after a rebuffer is
 * the balance used here. Playback itself is hardware-accelerated
 * MediaCodec rendering via ExoPlayer — no WebView anywhere in the app.
 */
class PlayerManager(context: Context, okHttpClient: OkHttpClient) {

    private val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
        .setUserAgent("Mozilla/5.0 (Linux; Android 10) LittleTube/1.0")

    private val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ 15_000,
            /* maxBufferMs = */ 30_000,
            /* bufferForPlaybackMs = */ 2_500,
            /* bufferForPlaybackAfterRebufferMs = */ 5_000
        )
        .build()

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setLoadControl(loadControl)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        .build()

    fun bind(view: PlayerView) {
        view.player = player
    }

    fun playUrl(url: String) {
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.play()
    }

    fun pause() {
        player.pause()
    }

    fun release() {
        player.release()
    }
}
