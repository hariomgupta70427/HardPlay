package com.hardplay.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Haptics.
 *
 * Compose's own [androidx.compose.ui.hapticfeedback.HapticFeedback] exposes only
 * two constants, neither of which is a crisp tick. Going through the View gives
 * access to CLOCK_TICK — the short, dry click the system uses for time pickers —
 * which is the right feel for scrubbing, skipping and toggling a tag.
 *
 * Every call respects the user's system haptics setting; nothing here passes
 * FLAG_IGNORE_GLOBAL_SETTING.
 */
class Haptics internal constructor(private val view: View) {

    /** Scrubbing, skip steps, chip toggles. Dry and short. */
    fun tick() {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** Button presses. */
    fun press() {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /** Long-press entry into a contextual action. */
    fun hold() {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /** A committed action: tag saved, channel added, download finished. */
    fun confirm() {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(constant)
    }

    /** A refused action: bad OTP, seek past the buffered edge. */
    fun reject() {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(constant)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}
