package com.hardplay.core

import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Display formatting.
 *
 * Centralised because these strings appear side by side — a duration badge on a
 * poster, the same duration in the player's scrubber — and two implementations of
 * "format a duration" always end up disagreeing about the one-hour boundary.
 *
 * Every numeric string here is rendered in the `tnum` timecode style (see
 * `Type.kt`), so widths stay stable as digits change.
 */
object Format {

    /**
     * `7:42`, or `1:07:42` past an hour.
     *
     * Hours are only shown when there are hours. Zero-padding everything to
     * `00:07:42` makes a grid of two-minute clips read like a spreadsheet.
     */
    fun duration(totalSeconds: Int?): String? {
        if (totalSeconds == null || totalSeconds <= 0) return null
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    fun durationMs(millis: Long): String = duration(
        TimeUnit.MILLISECONDS.toSeconds(millis.coerceAtLeast(0L)).toInt(),
    ) ?: "0:00"

    /**
     * A scrubber position, always the same shape as the total beside it.
     *
     * Formatting position and duration independently makes `9:59 / 1:00:04` jump a
     * whole field width at the hour mark, so the position borrows the duration's
     * shape.
     */
    fun position(positionMs: Long, durationMs: Long): String {
        val showHours = durationMs >= TimeUnit.HOURS.toMillis(1)
        val total = TimeUnit.MILLISECONDS.toSeconds(positionMs.coerceAtLeast(0L))
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (showHours) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /**
     * `1.8 GB`, `640 MB`, `12 KB`.
     *
     * Base 1024 with the short units, which is what a file manager on the same
     * device shows. One decimal below 10 and none above, so the string never
     * exceeds four characters of number.
     */
    fun bytes(value: Long): String {
        if (value <= 0L) return "—"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = value.toDouble()
        var unit = 0
        while (size >= 1024.0 && unit < units.lastIndex) {
            size /= 1024.0
            unit++
        }
        return when {
            unit == 0 -> "${size.toInt()} ${units[unit]}"
            size >= 10.0 -> String.format(Locale.US, "%.0f %s", size, units[unit])
            else -> String.format(Locale.US, "%.1f %s", size, units[unit])
        }
    }

    /** `4K`, `1080p`. Null when the dimensions aren't known. */
    fun resolution(width: Int?, height: Int?): String? {
        // Keyed on the short edge, so a vertical 1080×1920 clip reads as 1080p
        // rather than as 4K-adjacent.
        val shortEdge = minOf(width ?: return null, height ?: return null)
        if (shortEdge <= 0) return null
        return when {
            shortEdge >= 2000 -> "4K"
            shortEdge >= 1400 -> "1440p"
            shortEdge >= 1000 -> "1080p"
            shortEdge >= 700 -> "720p"
            shortEdge >= 460 -> "480p"
            else -> "${shortEdge}p"
        }
    }

    /**
     * Relative date from Telegram's epoch **seconds**.
     *
     * Relative for the recent past because that is how a library of one's own
     * uploads is remembered ("yesterday", "3 weeks ago"), absolute beyond a year
     * where relative stops being informative.
     */
    fun relativeDate(epochSeconds: Long, nowMillis: Long = System.currentTimeMillis()): String {
        if (epochSeconds <= 0L) return "—"
        val elapsed = nowMillis - TimeUnit.SECONDS.toMillis(epochSeconds)
        if (elapsed < 0L) return "Just now"

        val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
        val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
        val days = TimeUnit.MILLISECONDS.toDays(elapsed)

        return when {
            minutes < 1 -> "Just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            days == 1L -> "Yesterday"
            days < 7 -> "${days}d ago"
            days < 30 -> "${days / 7}w ago"
            days < 365 -> "${days / 30}mo ago"
            else -> "${days / 365}y ago"
        }
    }

    /** `1,842`. Thousands separators, because a bare `1842` reads as an id. */
    fun count(value: Int): String = String.format(Locale.US, "%,d", value)

    /** `1.4×`, `1×`. Multiplication sign, not the letter x. */
    fun speed(value: Float): String {
        val trimmed = if (value % 1f == 0f) value.toInt().toString() else value.toString()
        return "$trimmed×"
    }
}
