package com.hardplay.ui.player

import androidx.media3.common.C
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import java.util.Locale
// Media3's Format, aliased because com.hardplay.core.Format — the app's display
// formatter — is the one that reads as `Format` everywhere else in this package.
// Leaving both unaliased in the same file is how you end up calling the wrong one.
import androidx.media3.common.Format as MediaFormat

/**
 * One selectable audio or subtitle track.
 *
 * Holds the [group] and [indexInGroup] rather than a resolved selection, because that
 * pair is what `TrackSelectionOverride` wants and re-deriving it later from a label
 * would break the moment two tracks share a language.
 */
data class PlayerTrack(
    val label: String,
    val detail: String?,
    val selected: Boolean,
    val trackType: Int,
    val group: TrackGroup,
    val indexInGroup: Int,
)

/**
 * What the file actually offers.
 *
 * Note what is absent: any notion of quality or bitrate ladder. A Telegram video is a
 * single file at a single resolution — there is no adaptive rendition to switch to —
 * so a quality menu here could only ever be a control that does nothing. The player
 * states the resolution as a fact instead.
 */
data class PlayerTrackState(
    val audio: List<PlayerTrack> = emptyList(),
    val subtitles: List<PlayerTrack> = emptyList(),
    val subtitlesOff: Boolean = true,
) {
    /** One track is information, not a choice; the UI says so rather than offering a menu of one. */
    val hasAudioChoice: Boolean get() = audio.size > 1
    val hasSubtitles: Boolean get() = subtitles.isNotEmpty()
}

/** Read the current selection out of Media3's view of the media. */
fun Tracks.readPlayerTracks(): PlayerTrackState {
    val audio = mutableListOf<PlayerTrack>()
    val subtitles = mutableListOf<PlayerTrack>()

    groups.forEach { group ->
        val destination = when (group.type) {
            C.TRACK_TYPE_AUDIO -> audio
            C.TRACK_TYPE_TEXT -> subtitles
            else -> return@forEach
        }
        for (index in 0 until group.length) {
            // Unsupported tracks are listed by the extractor but cannot be selected on
            // this device; offering one produces a menu entry that silently fails.
            if (!group.isTrackSupported(index)) continue
            val format = group.getTrackFormat(index)
            destination += PlayerTrack(
                label = trackLabel(format, destination.size + 1),
                detail = trackDetail(format, group.type),
                selected = group.isTrackSelected(index),
                trackType = group.type,
                group = group.mediaTrackGroup,
                indexInGroup = index,
            )
        }
    }

    return PlayerTrackState(
        audio = audio,
        subtitles = subtitles,
        subtitlesOff = subtitles.none { it.selected },
    )
}

/**
 * A human label, in the order the file is likely to have one.
 *
 * The ordinal fallback is deliberate: "Track 2" tells you there is a second one to try,
 * where a blank row or a raw codec string tells you nothing you can act on.
 */
private fun trackLabel(format: MediaFormat, ordinal: Int): String {
    format.label?.takeIf { it.isNotBlank() }?.let { return it }

    val language = format.language?.takeIf { it.isNotBlank() && it != UNDETERMINED }
    if (language != null) {
        val display = Locale.forLanguageTag(language).displayLanguage
        if (display.isNotBlank() && !display.equals(language, ignoreCase = true)) {
            return display.replaceFirstChar { it.uppercase(Locale.getDefault()) }
        }
        return language.uppercase(Locale.US)
    }
    return "Track $ordinal"
}

/** Channel layout and codec — enough to tell a commentary track from the main mix. */
private fun trackDetail(format: MediaFormat, trackType: Int): String? {
    val codec = format.codecs?.substringBefore('.')?.takeIf { it.isNotBlank() }
    if (trackType != C.TRACK_TYPE_AUDIO) return codec

    val channels = when (format.channelCount) {
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> format.channelCount.takeIf { it > 0 }?.let { "$it ch" }
    }
    return listOfNotNull(channels, codec).joinToString(" · ").takeIf { it.isNotBlank() }
}

/** ISO 639-2's "undetermined" — present on most Telegram remuxes, and meaningless. */
private const val UNDETERMINED = "und"
