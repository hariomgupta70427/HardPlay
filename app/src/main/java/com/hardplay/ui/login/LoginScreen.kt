package com.hardplay.ui.login

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hardplay.telegram.TelegramAuthState
import com.hardplay.ui.components.BufferingMark
import com.hardplay.ui.components.EmberButton
import com.hardplay.ui.components.EmberRule
import com.hardplay.ui.components.HardPlayTextField
import com.hardplay.ui.components.Notice
import com.hardplay.ui.components.OtpField
import com.hardplay.ui.components.QuietButton
import com.hardplay.ui.theme.HardPlaySurface
import com.hardplay.ui.theme.HardPlayTheme
import com.hardplay.ui.theme.Motion
import com.hardplay.ui.theme.Space

/**
 * Sign-in (PRD §5.1).
 *
 * One screen with three stages rather than three destinations. Telegram can move
 * between them in either direction — a code expires, 2FA turns out to be enabled,
 * a resend restarts the wait — and modelling that as navigation means a back stack
 * that lets the user reach a stage the session has already left.
 */
@Composable
fun LoginScreen(
    modifier: Modifier = Modifier,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val auth by viewModel.authState.collectAsStateWithLifecycle()

    HardPlaySurface(modifier = modifier.fillMaxSize(), bloom = true) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.xxl),
            verticalArrangement = Arrangement.Center,
        ) {
            Header(stage = auth)

            Box(Modifier.height(Space.xxl))

            AnimatedContent(
                targetState = auth::class,
                transitionSpec = {
                    // Forward-only motion: each stage arrives from the right. The
                    // flow only ever advances, so an animation that could go
                    // backwards would be lying about what happened.
                    //
                    // Both halves are on the app's own curves. They were on bare
                    // `tween`s, which is Compose's symmetric FastOutSlowIn — the one
                    // easing in the design system that is explicitly ruled out,
                    // because a symmetric ease reads as software rather than as
                    // something with mass.
                    (
                        slideInHorizontally(Motion.standard()) { it / 6 } +
                            fadeIn(Motion.fade())
                        ) togetherWith
                        (
                            slideOutHorizontally(Motion.standard()) { -it / 8 } +
                                fadeOut(tween(Motion.Quick, easing = Motion.Smooth))
                            )
                },
                label = "loginStage",
            ) { _ ->
                // The target state is deliberately ignored: it is the stage's *class*
                // (see `targetState` above), which exists only so the transition fires
                // when the stage changes rather than on every keystroke that updates the
                // state's fields. The content therefore reads the live `auth` — it needs
                // the payload, and a KClass does not carry one.
                when (val state = auth) {
                    is TelegramAuthState.WaitingForPhoneNumber -> PhoneStage(ui, viewModel)
                    is TelegramAuthState.WaitingForCode -> CodeStage(state, ui, viewModel)
                    is TelegramAuthState.WaitingForPassword -> PasswordStage(state, ui, viewModel)
                    is TelegramAuthState.Unavailable -> UnavailableStage(state.reason)
                    else -> ConnectingStage(state)
                }
            }

            if (ui.error != null) {
                Box(Modifier.height(Space.lg))
                Notice(text = ui.error.orEmpty(), emphasis = true)
            }

            if (ui.isDemo) {
                Box(Modifier.height(Space.xl))
                Notice(
                    text = "Demo mode — no Telegram credentials or TDLib in this " +
                        "build. Any number works; the code is 22222.",
                )
            }
        }
    }
}

@Composable
private fun Header(stage: TelegramAuthState) {
    val colors = HardPlayTheme.colors
    val (overline, title) = when (stage) {
        is TelegramAuthState.WaitingForCode -> "Step 2 of 2" to "Enter the code"
        is TelegramAuthState.WaitingForPassword -> "Two-factor" to "Cloud password"
        is TelegramAuthState.Unavailable -> "Unavailable" to "Can't reach Telegram"
        else -> "Sign in" to "HardPlay"
    }

    Column {
        Text(
            text = overline.uppercase(),
            style = HardPlayTheme.type.overline,
            color = colors.accent,
        )
        Box(Modifier.height(Space.sm))
        Text(text = title, style = HardPlayTheme.type.display, color = colors.type)
        Box(Modifier.height(Space.sm))
        // The one ember rule on the screen, under the title. Shared with the mastheads
        // and the empty states — the mark is only a mark if it is one size.
        EmberRule()
    }
}

@Composable
private fun PhoneStage(ui: LoginUiState, viewModel: LoginViewModel) {
    val colors = HardPlayTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(verticalArrangement = Arrangement.spacedBy(Space.lg)) {
        Text(
            text = "HardPlay reads your own Telegram account. It never posts, " +
                "edits or deletes anything.",
            style = HardPlayTheme.type.body,
            color = colors.muted,
        )
        HardPlayTextField(
            value = ui.phone,
            onValueChange = viewModel::onPhoneChange,
            placeholder = "+91 98765 43210",
            keyboardType = KeyboardType.Phone,
            imeAction = ImeAction.Go,
            onImeAction = viewModel::submitPhone,
            enabled = !ui.busy,
            textStyle = HardPlayTheme.type.timecode,
            focusRequester = focus,
        )
        EmberButton(
            text = if (ui.busy) ui.busyLabel.orEmpty() else "Send code",
            onClick = viewModel::submitPhone,
            enabled = ui.phoneLooksUsable && !ui.busy,
            fillWidth = true,
        )
    }
}

@Composable
private fun CodeStage(
    state: TelegramAuthState.WaitingForCode,
    ui: LoginUiState,
    viewModel: LoginViewModel,
) {
    val colors = HardPlayTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(verticalArrangement = Arrangement.spacedBy(Space.lg)) {
        Text(
            text = "Sent to ${state.phoneNumber}. Telegram may deliver it in the " +
                "app rather than by SMS.",
            style = HardPlayTheme.type.body,
            color = colors.muted,
        )
        OtpField(
            value = ui.code,
            onValueChange = viewModel::onCodeChange,
            length = state.codeLength,
            enabled = !ui.busy,
            onComplete = viewModel::submitCode,
            focusRequester = focus,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            EmberButton(
                text = if (ui.busy) ui.busyLabel.orEmpty() else "Continue",
                onClick = viewModel::submitCode,
                enabled = ui.code.length == state.codeLength && !ui.busy,
                modifier = Modifier.weight(1f),
            )
            QuietButton(
                text = "Resend",
                onClick = viewModel::resendCode,
                enabled = !ui.busy,
            )
        }
    }
}

@Composable
private fun PasswordStage(
    state: TelegramAuthState.WaitingForPassword,
    ui: LoginUiState,
    viewModel: LoginViewModel,
) {
    val colors = HardPlayTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(verticalArrangement = Arrangement.spacedBy(Space.lg)) {
        Text(
            text = state.passwordHint
                ?.let { "Two-factor is on for this account. Hint: $it" }
                ?: "Two-factor is on for this account.",
            style = HardPlayTheme.type.body,
            color = colors.muted,
        )
        HardPlayTextField(
            value = ui.password,
            onValueChange = viewModel::onPasswordChange,
            placeholder = "Cloud password",
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Go,
            onImeAction = viewModel::submitPassword,
            enabled = !ui.busy,
            visualTransformation = PasswordVisualTransformation(),
            focusRequester = focus,
        )
        EmberButton(
            text = if (ui.busy) ui.busyLabel.orEmpty() else "Unlock account",
            onClick = viewModel::submitPassword,
            enabled = ui.password.isNotEmpty() && !ui.busy,
            fillWidth = true,
        )
    }
}

@Composable
private fun ConnectingStage(state: TelegramAuthState) {
    val colors = HardPlayTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        BufferingMark(markSize = 26.dp)
        Text(
            text = when (state) {
                is TelegramAuthState.LoggingOut -> "Signing out…"
                is TelegramAuthState.Closed -> "Reconnecting…"
                else -> "Starting Telegram…"
            },
            style = HardPlayTheme.type.bodySmall,
            color = colors.muted,
        )
    }
}

@Composable
private fun UnavailableStage(reason: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        Text(
            text = reason,
            style = HardPlayTheme.type.body,
            color = HardPlayTheme.colors.typeDim,
        )
    }
}
