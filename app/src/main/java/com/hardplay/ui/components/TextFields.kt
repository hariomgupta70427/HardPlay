package com.hardplay.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hardplay.ui.theme.HardPlayTheme
import com.hardplay.ui.theme.Motion
import com.hardplay.ui.theme.Space

/**
 * Text entry.
 *
 * [BasicTextField] with our own decoration rather than Material's `TextField`,
 * which arrives with a filled container, a floating label, an indicator line and a
 * 56dp minimum height — none of which belongs in this design system, and all of
 * which would have to be fought rather than configured.
 *
 * The focus treatment is a border that goes ember and a cursor that matches. No
 * label animation: the placeholder simply disappears, because a label sliding
 * upward is the single most recognisable Material gesture there is.
 */
@Composable
fun HardPlayTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: (() -> Unit)? = null,
    leading: @Composable ((focused: Boolean) -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    textStyle: TextStyle? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    focusRequester: FocusRequester? = null,
) {
    val colors = HardPlayTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderAlpha by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = Motion.quick(),
        label = "fieldFocus",
    )

    val resolvedStyle = (textStyle ?: HardPlayTheme.type.body).copy(
        color = if (enabled) colors.type else colors.muted,
    )

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = singleLine,
        textStyle = resolvedStyle,
        cursorBrush = colors.emberGradientVertical,
        interactionSource = interaction,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions { onImeAction?.invoke() },
        visualTransformation = visualTransformation,
        modifier = modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        decorationBox = { field ->
            Row(
                Modifier
                    .clip(HardPlayTheme.shapes.card)
                    .background(colors.surface)
                    .border(
                        Space.hairline,
                        // Interpolated rather than switched, so focus reads as a
                        // transition instead of a flash.
                        lerp(
                            colors.hairline,
                            colors.accent,
                            borderAlpha,
                        ),
                        HardPlayTheme.shapes.card,
                    )
                    .padding(horizontal = Space.md, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                leading?.invoke(focused)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(
                            text = placeholder,
                            style = resolvedStyle.copy(color = colors.muted),
                            maxLines = 1,
                        )
                    }
                    field()
                }
                trailing?.invoke()
            }
        },
    )
}

/**
 * The one-time code field.
 *
 * Cells rather than a single input, because a code is read off a screen digit by
 * digit and cells let the eye track position without counting. Under the hood it is
 * still one field — separate per-digit fields break paste, break the SMS
 * autofill and break backspace across cell boundaries.
 *
 * @param length cells to draw, from TDLib's `codeLength`. Telegram varies it, so
 *   this is never hardcoded to five.
 */
@Composable
fun OtpField(
    value: String,
    onValueChange: (String) -> Unit,
    length: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onComplete: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
) {
    val colors = HardPlayTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    BasicTextField(
        value = value,
        onValueChange = { raw ->
            val digits = raw.filter(Char::isDigit).take(length)
            onValueChange(digits)
            // Submit on the last digit. Making the user reach for a button after
            // typing a code they just read is pure friction.
            if (digits.length == length) onComplete?.invoke()
        },
        enabled = enabled,
        singleLine = true,
        // The real text is invisible; the cells below are the visual. A transparent
        // cursor keeps a caret from appearing over the top of them.
        textStyle = HardPlayTheme.type.timecode.copy(color = Color.Transparent),
        cursorBrush = SolidColor(Color.Transparent),
        interactionSource = interaction,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions { onComplete?.invoke() },
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        decorationBox = {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                repeat(length) { index ->
                    val char = value.getOrNull(index)
                    val isCursor = focused && index == value.length.coerceAtMost(length - 1)
                    Box(
                        Modifier
                            .size(width = 42.dp, height = 52.dp)
                            .clip(HardPlayTheme.shapes.card)
                            .background(colors.surface)
                            .border(
                                Space.hairline,
                                when {
                                    char != null -> colors.border
                                    isCursor -> colors.accent
                                    else -> colors.hairline
                                },
                                HardPlayTheme.shapes.card,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (char != null) {
                            Text(
                                text = char.toString(),
                                style = HardPlayTheme.type.timecode.copy(fontSize = 20.sp),
                                color = colors.type,
                                textAlign = TextAlign.Center,
                            )
                        } else if (isCursor) {
                            // A 2dp ember tick marks the next cell — the same
                            // marker the poster uses for "unseen", reused.
                            Box(
                                Modifier
                                    .width(2.dp)
                                    .height(18.dp)
                                    .background(colors.emberGradientVertical),
                            )
                        }
                    }
                }
            }
        },
    )
}
