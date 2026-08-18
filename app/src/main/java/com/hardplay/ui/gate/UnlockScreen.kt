package com.hardplay.ui.gate

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.fragment.app.FragmentActivity
import com.hardplay.R
import com.hardplay.ui.components.EmberButton
import com.hardplay.ui.components.GhostButton
import com.hardplay.ui.components.Notice
import com.hardplay.ui.components.rememberHaptics
import com.hardplay.ui.theme.HardPlaySurface
import com.hardplay.ui.theme.HardPlayTheme
import com.hardplay.ui.theme.Space
import java.util.concurrent.Executor

/**
 * The local app lock (PRD §9).
 *
 * There is no HardPlay account, so this gate is the only thing between an unlocked
 * phone in someone else's hand and the library. It is also where the discreet
 * disguise pays off: until it clears, the screen shows the same neutral identity as
 * the launcher icon, and the real branding appears only after the gate does.
 */

/**
 * Authenticator selection is version-dependent, and getting it wrong throws.
 *
 * `BIOMETRIC_WEAK or DEVICE_CREDENTIAL` is rejected outright by `BiometricPrompt`
 * on API 28 and 29 — it is a documented gap, not a device quirk — so those
 * versions get biometrics with an explicit negative button instead. Offering the
 * combination everywhere would crash on exactly the older devices most likely to be
 * used as a spare.
 */
private fun allowedAuthenticators(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
    } else {
        BiometricManager.Authenticators.BIOMETRIC_WEAK
    }

/** What the device can actually do, which decides what the gate offers. */
enum class GateCapability {
    /** A prompt can be shown. */
    AVAILABLE,

    /** Hardware exists but nothing is enrolled — the user must set a lock up. */
    NOT_ENROLLED,

    /** No usable hardware. The gate cannot be enforced on this device. */
    UNAVAILABLE,
}

fun gateCapability(context: android.content.Context): GateCapability =
    when (BiometricManager.from(context).canAuthenticate(allowedAuthenticators())) {
        BiometricManager.BIOMETRIC_SUCCESS -> GateCapability.AVAILABLE
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> GateCapability.NOT_ENROLLED
        else -> GateCapability.UNAVAILABLE
    }

/**
 * The lock screen.
 *
 * @param onUnlocked called once, on success. The caller owns the unlocked flag —
 *   this composable deliberately holds no "am I unlocked" state of its own, so that
 *   a recomposition can never accidentally re-open the library.
 */
@Composable
fun UnlockScreen(
    onUnlocked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = HardPlayTheme.colors
    val haptics = rememberHaptics()

    val activity = context as? FragmentActivity
    val capability = remember(context) { gateCapability(context) }

    var error by remember { mutableStateOf<String?>(null) }
    var prompting by remember { mutableStateOf(false) }

    val executor = remember(context) { Executor { command -> command.run() } }

    val authenticate: () -> Unit = {
        if (activity == null) {
            error = "This build can't show the system prompt."
        } else {
            prompting = true
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        prompting = false
                        haptics.confirm()
                        onUnlocked()
                    }

                    override fun onAuthenticationError(code: Int, message: CharSequence) {
                        prompting = false
                        // A cancel is not an error worth shouting about: the user
                        // dismissed the sheet and is looking at the button that
                        // brings it back.
                        error = when (code) {
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                            BiometricPrompt.ERROR_CANCELED,
                            -> null
                            else -> message.toString()
                        }
                    }

                    override fun onAuthenticationFailed() {
                        haptics.reject()
                        error = "Not recognised."
                    }
                },
            )

            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(context.getString(R.string.unlock_prompt_title))
                .setSubtitle(context.getString(R.string.unlock_prompt_subtitle))
                .setAllowedAuthenticators(allowedAuthenticators())
                .setConfirmationRequired(false)
                .apply {
                    // A negative button is required when device credential is not
                    // among the authenticators, and forbidden when it is.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                        setNegativeButtonText(context.getString(R.string.unlock_cancel))
                    }
                }
                .build()

            runCatching { prompt.authenticate(info) }
                .onFailure {
                    prompting = false
                    error = it.message ?: "The system prompt refused to open."
                }
        }
    }

    // Offer the prompt immediately. Making someone tap "unlock" to reach the
    // unlock sheet is a tap that carries no information.
    LaunchedEffect(capability) {
        if (capability == GateCapability.AVAILABLE) authenticate()
    }

    HardPlaySurface(modifier = modifier.fillMaxSize(), bloom = true) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = Space.xxl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The *discreet* name, matching the launcher. The real identity is on
            // the other side of this gate.
            Text(
                text = stringResource(R.string.app_name_discreet),
                style = HardPlayTheme.type.display,
                color = colors.type,
            )
            Box(Modifier.height(Space.sm))
            Text(
                text = "Locked",
                style = HardPlayTheme.type.editorialSmall,
                color = colors.muted,
            )

            Box(Modifier.height(Space.xxxl))

            when (capability) {
                GateCapability.AVAILABLE -> {
                    // No extra alpha animation here. There used to be one, at 0.35 while
                    // the system sheet was up, and it multiplied with the 0.4 that
                    // `enabled = false` already applies — so the only control on the
                    // screen faded to 14% opacity at exactly the moment the user was
                    // looking for it. The label change carries the state on its own.
                    EmberButton(
                        text = if (prompting) "Waiting…" else "Unlock",
                        icon = Icons.Rounded.Fingerprint,
                        onClick = authenticate,
                        enabled = !prompting,
                        fillWidth = true,
                    )
                }

                GateCapability.NOT_ENROLLED -> {
                    Text(
                        text = "This device has no screen lock set up. " +
                            "Add a PIN, pattern or fingerprint in Settings to " +
                            "gate the library.",
                        style = HardPlayTheme.type.body,
                        color = colors.typeDim,
                        textAlign = TextAlign.Center,
                    )
                    Box(Modifier.height(Space.lg))
                    GhostButton(text = "Continue anyway", onClick = onUnlocked, fillWidth = true)
                }

                GateCapability.UNAVAILABLE -> {
                    Text(
                        text = "No biometric or device credential is available here, " +
                            "so the lock can't be enforced.",
                        style = HardPlayTheme.type.body,
                        color = colors.typeDim,
                        textAlign = TextAlign.Center,
                    )
                    Box(Modifier.height(Space.lg))
                    GhostButton(text = "Continue", onClick = onUnlocked, fillWidth = true)
                }
            }

            if (error != null) {
                Box(Modifier.height(Space.lg))
                // A Notice, not danger-coloured text. Severity in this design system is
                // carried by weight and the ember edge rather than by a hue, and every
                // other failure in the app is reported through this component — a bare
                // red line here was the one place that rule was broken.
                Notice(text = error.orEmpty(), emphasis = true)
            }

            Box(Modifier.height(Space.xxxl))
            // A hairline rule rather than a footer: nothing else on this screen
            // should compete with the prompt.
            Box(
                Modifier
                    .width(Space.xxl)
                    .height(Space.hairline)
                    .background(colors.hairline),
            )
        }
    }
}
