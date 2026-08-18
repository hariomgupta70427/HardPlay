package com.hardplay.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hardplay.core.Format
import com.hardplay.ui.components.BufferingMark
import com.hardplay.ui.components.EmberButton
import com.hardplay.ui.components.EmptyState
import com.hardplay.ui.components.GhostIconButton
import com.hardplay.ui.components.HardPlayTextField
import com.hardplay.ui.components.HardPlayTopBar
import com.hardplay.ui.components.Notice
import com.hardplay.ui.components.SectionHeader
import com.hardplay.ui.components.rememberHaptics
import com.hardplay.ui.theme.HardPlaySurface
import com.hardplay.ui.theme.HardPlayTheme
import com.hardplay.ui.theme.Space

/**
 * The source picker.
 *
 * Runs as first-run onboarding and again from Settings, with [firstRun] deciding
 * only the copy and whether there is a back arrow — not the behaviour. Two screens
 * for one job would be two screens to keep in step.
 */
@Composable
fun ChannelPickerScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    firstRun: Boolean = false,
    onBack: (() -> Unit)? = null,
    viewModel: ChannelPickerViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val existing by viewModel.existing.collectAsStateWithLifecycle()
    val colors = HardPlayTheme.colors
    val existingIds = remember(existing) { existing.map { it.chatId }.toSet() }

    HardPlaySurface(modifier = modifier.fillMaxSize(), bloom = firstRun) {
        Column(Modifier.fillMaxSize()) {
            HardPlayTopBar(
                title = if (firstRun) "Choose sources" else "Sources",
                overline = if (firstRun) "Setup" else null,
                scrolled = !firstRun,
                onBack = onBack,
                actions = {
                    GhostIconButton(
                        icon = Icons.Rounded.Refresh,
                        contentDescription = "Reload channel list",
                        onClick = viewModel::refresh,
                        enabled = !ui.loading,
                    )
                },
            )

            LazyColumn(
                Modifier
                    .weight(1f)
                    .imePadding(),
                contentPadding = PaddingValues(bottom = Space.xxxl),
            ) {
                if (firstRun) {
                    item {
                        Text(
                            text = "Pick the channels to build the library from. " +
                                "They merge into one grid, and you can filter by " +
                                "source at any time.",
                            style = HardPlayTheme.type.body,
                            color = colors.muted,
                            modifier = Modifier.padding(
                                horizontal = Space.gutter,
                                vertical = Space.md,
                            ),
                        )
                    }
                }

                item {
                    ManualAddRow(
                        query = ui.manualQuery,
                        resolving = ui.resolving,
                        onQueryChange = viewModel::onQueryChange,
                        onSubmit = viewModel::resolveManual,
                    )
                }

                if (ui.error != null) {
                    item {
                        Box(Modifier.padding(horizontal = Space.gutter, vertical = Space.sm)) {
                            Notice(text = ui.error.orEmpty(), emphasis = true)
                        }
                    }
                }

                if (existing.isNotEmpty()) {
                    item { SectionHeader(title = "In your library", overline = "Added") }
                    items(existing, key = { "added-${it.chatId}" }) { channel ->
                        ChannelRow(
                            title = channel.title,
                            subtitle = channel.username?.let { "@$it" }
                                ?: "Added ${Format.relativeDate(channel.addedAt / 1000)}",
                            selected = channel.enabled,
                            onClick = { viewModel.setEnabled(channel.chatId, !channel.enabled) },
                            trailing = {
                                GhostIconButton(
                                    icon = Icons.Rounded.RemoveCircleOutline,
                                    contentDescription = "Remove ${channel.title}",
                                    onClick = { viewModel.remove(channel.chatId) },
                                    size = 16.dp,
                                    tint = colors.muted,
                                )
                            },
                        )
                    }
                }

                item { SectionHeader(title = "Available", overline = "From this account") }

                when {
                    ui.loading && ui.available.isEmpty() -> item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(Space.xxl),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BufferingMark(markSize = 24.dp)
                            Box(Modifier.width(Space.md))
                            Text(
                                text = "Reading your channel list…",
                                style = HardPlayTheme.type.bodySmall,
                                color = colors.muted,
                            )
                        }
                    }

                    ui.available.isEmpty() -> item {
                        EmptyState(
                            headline = "Nothing to list",
                            body = "This account has no readable channels, or " +
                                "Telegram hasn't sent the list yet. Add one by " +
                                "handle above.",
                            overline = "No channels",
                        )
                    }

                    else -> items(
                        ui.available.filterNot { it.chatId in existingIds },
                        key = { "avail-${it.chatId}" },
                    ) { chat ->
                        ChannelRow(
                            title = chat.title,
                            subtitle = chat.username?.let { "@$it" } ?: "Private channel",
                            selected = chat.chatId in ui.selected,
                            onClick = { viewModel.toggle(chat.chatId) },
                        )
                    }
                }
            }

            // Commit bar. Only present when there is something to commit — a
            // permanently visible disabled button is a permanent reproach.
            if (ui.canConfirm || ui.saving) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.bgRaised)
                        .navigationBarsPadding()
                        .padding(Space.gutter),
                ) {
                    EmberButton(
                        text = if (ui.saving) {
                            "Adding…"
                        } else {
                            "Add ${ui.selected.size} " +
                                if (ui.selected.size == 1) "source" else "sources"
                        },
                        onClick = { viewModel.confirm(onDone) },
                        enabled = ui.canConfirm,
                        fillWidth = true,
                    )
                }
            } else if (firstRun && existing.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.bgRaised)
                        .navigationBarsPadding()
                        .padding(Space.gutter),
                ) {
                    EmberButton(text = "Open library", onClick = onDone, fillWidth = true)
                }
            }
        }
    }
}

@Composable
private fun ManualAddRow(
    query: String,
    resolving: Boolean,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        HardPlayTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = "@handle, t.me link, or chat id",
            enabled = !resolving,
            onImeAction = onSubmit,
            modifier = Modifier.weight(1f),
            trailing = {
                if (resolving) BufferingMark(markSize = 18.dp, strokeWidth = 1.5.dp)
            },
        )
    }
}

/**
 * One channel row.
 *
 * Selection is a 2dp ember bar down the leading edge plus a tick, not a checkbox.
 * Material's checkbox is instantly recognisable and would undo the design system
 * on the first screen a new user sees.
 */
@Composable
private fun ChannelRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = HardPlayTheme.colors
    val haptics = rememberHaptics()

    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter, vertical = Space.xs)
            .clip(HardPlayTheme.shapes.card)
            .background(if (selected) colors.surfaceRaised else colors.surface)
            .border(
                Space.hairline,
                if (selected) colors.accent.copy(alpha = 0.55f) else colors.hairline,
                HardPlayTheme.shapes.card,
            )
            .clickable {
                haptics.tick()
                onClick()
            }
            .padding(horizontal = Space.md, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Box(
            Modifier
                .width(2.dp)
                .height(30.dp)
                .background(
                    if (selected) {
                        colors.emberGradientVertical
                    } else {
                        SolidColor(colors.hairline)
                    },
                ),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = HardPlayTheme.type.title,
                color = colors.type,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = HardPlayTheme.type.labelSmall,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(18.dp),
            )
        }
        trailing?.invoke()
    }
}
