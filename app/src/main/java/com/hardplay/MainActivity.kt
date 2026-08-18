package com.hardplay

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.hardplay.data.prefs.SettingsStore
import com.hardplay.ui.player.LocalPipController
import com.hardplay.ui.player.PipController
import com.hardplay.ui.root.HardPlayRoot
import com.hardplay.ui.theme.HardPlayTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The app's single activity.
 *
 * Extends [FragmentActivity] rather than `ComponentActivity` for one reason:
 * `BiometricPrompt` requires a `FragmentActivity` to host its dialog, and the
 * biometric gate is the app's front door (PRD §9). `AppCompatActivity` would also
 * satisfy that, but it insists on an AppCompat window theme, and this app's theme
 * descends from `Theme.SplashScreen` instead.
 *
 * It also owns the two window-level concerns no composable can: whether the window is
 * marked as protected content, and picture-in-picture.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var settings: SettingsStore

    private val pip = WindowPipController()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Closed first, then relaxed if the user asked for it.
        //
        // Screenshot and recents-thumbnail blocking has to be in place before the
        // first frame, and the stored preference lives in DataStore, which is read
        // asynchronously. Starting from the preference would leave a window in which
        // the recents snapshot could be taken of a library screen — the single moment
        // this flag exists to cover. So the flag goes on synchronously and the
        // collector below is only ever able to turn it off.
        applySecureFlag(enabled = true)

        lifecycleScope.launch {
            settings.settings
                .map { it.blockScreenshots }
                .distinctUntilChanged()
                .collect(::applySecureFlag)
        }

        enableEdgeToEdge()

        setContent {
            HardPlayTheme {
                CompositionLocalProvider(LocalPipController provides pip) {
                    HardPlayRoot()
                }
            }
        }
    }

    /**
     * `FLAG_SECURE`: no screenshots, and a blank card in recents.
     *
     * A setting rather than a constant because the same flag marks the window as
     * protected content, and some devices render a picture-in-picture window black as
     * a result. Someone who wants PiP more than they want a blank recents thumbnail
     * can say so in Settings — but the flag is never dropped silently to make a
     * feature work.
     */
    private fun applySecureFlag(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        pip.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pip.onModeChanged(isInPictureInPictureMode)
    }

    /**
     * [PipController], backed by this window.
     *
     * `supported` is `by lazy` rather than an eager initialiser: property initialisers
     * run in the constructor, before the activity has a base context, and
     * `packageManager` is not available that early.
     */
    private inner class WindowPipController : PipController {

        override val supported: Boolean by lazy {
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        }

        private var mode by mutableStateOf(false)
        override val inPipMode: Boolean get() = mode

        /** Non-null while something is playing, and holds the ratio to enter at. */
        private var autoEnterAspect: Float? = null

        fun onModeChanged(inPip: Boolean) {
            mode = inPip
        }

        fun onUserLeaveHint() {
            // From API 31 the system enters on its own because of
            // setAutoEnterEnabled, so doing it here as well would enter twice.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
            enter(autoEnterAspect ?: return)
        }

        override fun setAutoEnter(enabled: Boolean, aspectRatio: Float) {
            if (!supported) return
            autoEnterAspect = if (enabled) aspectRatio else null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Throws if the activity is finishing or the params are rejected;
                // neither is worth taking the app down for a floating window.
                runCatching {
                    setPictureInPictureParams(
                        PictureInPictureParams.Builder()
                            .setAspectRatio(aspectRatio.toPipRational())
                            .setAutoEnterEnabled(enabled)
                            .build(),
                    )
                }
            }
        }

        override fun enter(aspectRatio: Float) {
            if (!supported) return
            runCatching {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder()
                        .setAspectRatio(aspectRatio.toPipRational())
                        .build(),
                )
            }
        }
    }

    /**
     * A ratio Android will actually accept.
     *
     * `setAspectRatio` throws for anything outside roughly 1:2.39 … 2.39:1, and a
     * vertical Telegram clip at 9:16 is 0.56 — inside the range — while a 1080×2400
     * screen recording at 0.45 is not. Clamping is the difference between a squarer
     * PiP window and a crash on the way into it.
     */
    private fun Float.toPipRational(): Rational {
        val safe = if (isFinite() && this > 0f) {
            coerceIn(PIP_MIN_RATIO, PIP_MAX_RATIO)
        } else {
            DEFAULT_RATIO
        }
        return Rational((safe * RATIONAL_SCALE).toInt(), RATIONAL_SCALE)
    }

    private companion object {
        const val PIP_MIN_RATIO = 0.42f
        const val PIP_MAX_RATIO = 2.38f
        const val DEFAULT_RATIO = 16f / 9f
        const val RATIONAL_SCALE = 1000
    }
}
