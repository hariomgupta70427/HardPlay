package com.hardplay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

class FormatTest {

    @Test
    fun `durations below an hour omit the hour field`() {
        assertEquals("0:07", Format.duration(7))
        assertEquals("7:42", Format.duration(462))
        assertEquals("59:59", Format.duration(3599))
    }

    @Test
    fun `durations past an hour include it, zero-padded below`() {
        assertEquals("1:00:00", Format.duration(3600))
        assertEquals("1:07:42", Format.duration(4062))
        assertEquals("10:00:01", Format.duration(36001))
    }

    @Test
    fun `absent or zero duration yields null so the badge is skipped`() {
        assertNull(Format.duration(null))
        assertNull(Format.duration(0))
        assertNull(Format.duration(-5))
    }

    @Test
    fun `position borrows the shape of the duration beside it`() {
        // The reason this exists: formatting the two independently makes
        // "9:59 / 1:00:04" jump a whole field width at the hour mark.
        val hourLong = TimeUnit.MINUTES.toMillis(64)
        assertEquals("0:09:59", Format.position(TimeUnit.SECONDS.toMillis(599), hourLong))

        val shortClip = TimeUnit.MINUTES.toMillis(3)
        assertEquals("0:59", Format.position(TimeUnit.SECONDS.toMillis(59), shortClip))
    }

    @Test
    fun `negative positions clamp to zero`() {
        assertEquals("0:00", Format.position(-1_000, 60_000))
    }

    @Test
    fun `bytes use base 1024 and at most one decimal`() {
        assertEquals("—", Format.bytes(0))
        assertEquals("512 B", Format.bytes(512))
        assertEquals("1.0 KB", Format.bytes(1024))
        assertEquals("640 MB", Format.bytes(640L * 1024 * 1024))
        assertEquals("1.8 GB", Format.bytes((1.8 * 1024 * 1024 * 1024).toLong()))
        // Above ten, the decimal is noise.
        assertEquals("16 GB", Format.bytes(16L * 1024 * 1024 * 1024))
    }

    @Test
    fun `resolution is keyed on the short edge`() {
        assertEquals("1080p", Format.resolution(1920, 1080))
        // A vertical clip must read as 1080p, not as something 4K-adjacent.
        assertEquals("1080p", Format.resolution(1080, 1920))
        assertEquals("4K", Format.resolution(3840, 2160))
        assertEquals("720p", Format.resolution(1280, 720))
        assertNull(Format.resolution(null, 1080))
        assertNull(Format.resolution(1920, null))
        assertNull(Format.resolution(0, 0))
    }

    @Test
    fun `relative dates read the way a library is remembered`() {
        val now = 1_700_000_000_000L
        fun ago(millis: Long) = Format.relativeDate((now - millis) / 1000, now)

        assertEquals("Just now", ago(TimeUnit.SECONDS.toMillis(20)))
        assertEquals("5m ago", ago(TimeUnit.MINUTES.toMillis(5)))
        assertEquals("3h ago", ago(TimeUnit.HOURS.toMillis(3)))
        assertEquals("Yesterday", ago(TimeUnit.DAYS.toMillis(1)))
        assertEquals("4d ago", ago(TimeUnit.DAYS.toMillis(4)))
        assertEquals("2w ago", ago(TimeUnit.DAYS.toMillis(14)))
        assertEquals("3mo ago", ago(TimeUnit.DAYS.toMillis(95)))
        assertEquals("2y ago", ago(TimeUnit.DAYS.toMillis(760)))
    }

    @Test
    fun `a future date does not render as negative time`() {
        val now = 1_700_000_000_000L
        assertEquals("Just now", Format.relativeDate((now + 60_000) / 1000, now))
    }

    @Test
    fun `missing date renders as an em dash`() {
        assertEquals("—", Format.relativeDate(0))
    }

    @Test
    fun `counts get thousands separators`() {
        assertEquals("1,842", Format.count(1842))
        assertEquals("7", Format.count(7))
    }

    @Test
    fun `speed drops a trailing zero and uses a multiplication sign`() {
        assertEquals("1×", Format.speed(1f))
        assertEquals("1.25×", Format.speed(1.25f))
        assertEquals("2×", Format.speed(2f))
    }
}
