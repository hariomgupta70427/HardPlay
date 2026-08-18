package com.hardplay.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.hardplay.telegram.TelegramGateway
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the player.
 *
 * Not a singleton `ExoPlayer`: a player holds codecs and a surface, and keeping one
 * alive behind the library grid means holding a hardware decoder the rest of the
 * device could be using. One per player screen, released with it.
 *
 * The buffer sizes below are the interesting part. Media3's defaults are tuned for
 * HTTP progressive download over a CDN with predictable throughput. This source is
 * MTProto through TDLib, where a range request has noticeably higher latency to
 * first byte and throughput arrives in bursts as 1 MB parts land — so the defaults
 * produce a player that starts fast and then rebuffers every few seconds.
 */
@Singleton
class TelegramPlayerFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gateway: TelegramGateway,
) {

    fun create(): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ MIN_BUFFER_MS,
                /* maxBufferMs = */ MAX_BUFFER_MS,
                // Start playing after two and a half seconds are in hand. Less and
                // a burst-delivered stream stutters on the first frame it needs.
                /* bufferForPlaybackMs = */ 2_500,
                // After a rebuffer, wait longer before resuming: resuming early is
                // what turns one stall into a repeating stutter.
                /* bufferForPlaybackAfterRebufferMs = */ 6_000,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(
                // Keep a little decoded history so a small scrub back doesn't have
                // to re-request bytes TDLib already holds.
                /* backBufferDurationMs = */ 20_000,
                /* retainBackBufferFromKeyframe = */ true,
            )
            .build()

        val renderers = DefaultRenderersFactory(context)
            // Fall back to a software decoder rather than failing outright: HDR and
            // HEVC support varies, and a library of mixed-provenance video will
            // eventually contain something this chip cannot handle in hardware.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        return ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(TelegramDataSource.Factory(gateway)),
            )
            .setTrackSelector(DefaultTrackSelector(context))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                // Take audio focus properly, so a call or another player pauses
                // this one instead of talking over it.
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(DEFAULT_SEEK_MS)
            .setSeekForwardIncrementMs(DEFAULT_SEEK_MS)
            .build()
    }

    private companion object {
        const val MIN_BUFFER_MS = 20_000
        const val MAX_BUFFER_MS = 90_000
        const val DEFAULT_SEEK_MS = 10_000L
    }
}
