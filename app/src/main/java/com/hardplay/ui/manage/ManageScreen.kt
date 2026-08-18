package com.hardplay.ui.manage

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
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
import com.hardplay.data.db.projection.LibraryTotals
import com.hardplay.ui.components.BufferingMark
import com.hardplay.ui.components.EmberButton
import com.hardplay.ui.components.EmptyState
import com.hardplay.ui.components.GhostButton
import com.hardplay.ui.components.GhostIconButton
import com.hardplay.ui.components.HardPlaySwitch
import com.hardplay.ui.components.HardPlayTopBar
import com.hardplay.ui.components.Notice
import com.hardplay.ui.components.QuietButton
import com.hardplay.ui.components.ScreenHeader
import com.hardplay.ui.components.SettingGroup
import com.hardplay.ui.components.SettingRow
import com.hardplay.sync.SyncPhase
import com.hardplay.sync.SyncProgress
import com.hardplay.ui.theme.HardPlaySurface
import com.hardplay.ui.theme.HardPlayTheme
import com.hardplay.ui.theme.Space

/**
 * Sources, indexing state and the way into Settings.
 *
 * The tab exists to answer two questions the library itself cannot: what is this made
 * of, and has it finished reading. Everything here is either a fact about the index or
 * a control that changes which channels feed it — the long tail of preferences stays in
 * Settings, because duplicating rows across two screens is how two screens end up
 * disagreeing about what a setting currently is.
 */
@Composable
fun ManageScreen(
    onAddSources: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ManageViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val sync by viewModel.syncProgress.collectAsStateWithLifecycle()
    val pendingRemoval by viewModel.pendingRemoval.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    val scrolled by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 8
        }
    }

    HardPlaySurface(modifier = modifier.fillMaxSize(), bloom = false) {
        Column(Modifier.fillMaxSize()) {
            HardPlayTopBar(
                title = "Manage",
                overline = sourcesOverline(ui.sources.size),
                scrolled = scrolled,
                showTitle = scrolled,
                actions = {
                    GhostIconButton(
                        icon = Icons.Rounded.Refresh,
                        contentDescription = "Re-read channel names from Telegram",
                        onClick = viewModel::refreshSources,
                        enabled = !ui.isDemo,
                    )
                },
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                // Clearance above the bottom bar, not a window inset: the bar is a
                // sibling of this screen and applies the navigation-bar inset itself, so
                // adding one here would leave a dead gap between the two.
                contentPadding = PaddingValues(bottom = Space.xxxl),
            ) {
                item {
                    Box(Modifier.padding(horizontal = Space.gutter)) {
                        ScreenHeader(
                            title = "Manage",
                            overline = sourcesOverline(ui.sources.size),
                            subtitle = "What the library is made of, and how much has been read.",
                        )
                    }
                }

                ui.totals?.let { totals ->
                    item { StatStrip(totals) }
                }

                if (ui.hasUnfinishedBackfill ||
                    sync is SyncProgress.Running ||
                    sync is SyncProgress.Failed
                ) {
                    item {
                        IndexingBlock(
                            progress = sync,
                            onKeepIndexing = viewModel::keepIndexing,
                        )
                    }
                }

                item { SettingGroup("Sources") }

                if (ui.sources.isEmpty()) {
                    item {
                        EmptyState(
                            overline = "No sources",
                            headline = "Nothing feeding the library.",
                            body = "Add a Telegram channel and HardPlay indexes its " +
                                "captions, tags and artwork.",
                        )
                    }
                } else {
                    items(ui.sources, key = { "src-${it.chatId}" }) { source ->
                        SourceCard(
                            source = source,
                            confirming = pendingRemoval == source.chatId,
                            onToggle = { enabled -> viewModel.setEnabled(source.chatId, enabled) },
                            onAskRemove = { viewModel.askToRemove(source.chatId) },
                            onCancelRemove = viewModel::cancelRemoval,
                            onConfirmRemove = viewModel::confirmRemoval,
                        )
                    }
                }

                item {
                    Box(Modifier.padding(horizontal = Space.gutter, vertical = Space.md)) {
                        EmberButton(
                            text = "Add a source",
                            onClick = onAddSources,
                            fillWidth = true,
                        )
                    }
                }

                item { SettingGroup("Engine") }
                item {
                    SettingRow(
                        title = "Telegram engine",
                        subtitle = ui.engineSummary,
                    )
                }

                item { SettingGroup("Everything else") }
                item {
                    SettingRow(
                        title = "Settings",
                        subtitle = "Presence, playback, storage and diagnostics",
                        onClick = onOpenSettings,
                        trailing = {
                            GhostButton(text = "Open", onClick = onOpenSettings, small = true)
                        },
                    )
                }
            }
        }
    }
}

private fun sourcesOverline(count: Int): String = when (count) {
    0 -> "No sources"
    1 -> "1 source"
    else -> "$count sources"
}

/**
 * The library in four figures.
 *
 * Set in the tabular timecode style so the numbers align down the strip rather than
 * jittering as digits change. The size deliberately says *on Telegram*: it is the
 * weight of the media the index points at, not disk used here — HardPlay stores
 * metadata and streams the rest, and conflating the two would make a 3 TB library look
 * like a 3 TB app.
 */
@Composable
private fun StatStrip(totals: LibraryTotals) {
    val colors = HardPlayTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.xl),
        ) {
            Stat(label = "Items", value = Format.count(totals.itemCount))
            Stat(label = "Video", value = Format.count(totals.videoCount))
            Stat(label = "Size", value = Format.bytes(totals.totalBytes))
        }
        Box(Modifier.height(Space.sm))
        Text(
            text = "Size is what the media weighs on Telegram. HardPlay keeps the " +
                "index and streams the rest on demand.",
            style = HardPlayTheme.type.bodySmall,
            color = colors.muted,
        )
    }
}

@Composable
private fun Stat(label: String, value: String) {
    val colors = HardPlayTheme.colors
    Column {
        Text(
            text = value,
            style = HardPlayTheme.type.timecode,
            color = colors.type,
        )
        Box(Modifier.height(2.dp))
        Text(
            text = label.uppercase(),
            style = HardPlayTheme.type.overline,
            color = colors.muted,
        )
    }
}

/**
 * The indexing state, and the only control that advances it.
 *
 * Present only while there is something to say. A permanently visible "Keep indexing"
 * button on a fully indexed library is a permanent suggestion that something is wrong.
 */
@Composable
private fun IndexingBlock(
    progress: SyncProgress,
    onKeepIndexing: () -> Unit,
) {
    Box(Modifier.padding(horizontal = Space.gutter, vertical = Space.sm)) {
        when (progress) {
            is SyncProgress.Failed -> Notice(
                text = progress.message,
                emphasis = true,
                action = {
                    GhostButton(text = "Retry", onClick = onKeepIndexing, small = true)
                },
            )

            is SyncProgress.Running -> Notice(
                text = when (progress.phase) {
                    SyncPhase.HEAD -> "Checking ${progress.channelTitle} for new posts…"
                    SyncPhase.BACKFILL ->
                        "Indexing ${progress.channelTitle} — " +
                            "${Format.count(progress.indexed)} so far"
                },
                action = { BufferingMark(markSize = 18.dp, strokeWidth = 1.5.dp) },
            )

            else -> Notice(
                text = "History isn't fully indexed yet. Telegram only pages backwards, " +
                    "so this runs in batches — each press reads a little further.",
                action = {
                    GhostButton(text = "Keep indexing", onClick = onKeepIndexing, small = true)
                },
            )
        }
    }
}

/**
 * One source.
 *
 * Removal confirms *in place* rather than in a modal. The action is per-row and
 * irreversible, so the thing that matters most is being certain which row you are
 * about to destroy — and a sheet that covers the list is the one presentation that
 * takes that away.
 */
@Composable
private fun SourceCard(
    source: ManagedSource,
    confirming: Boolean,
    onToggle: (Boolean) -> Unit,
    onAskRemove: () -> Unit,
    onCancelRemove: () -> Unit,
    onConfirmRemove: () -> Unit,
) {
    val colors = HardPlayTheme.colors

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter, vertical = Space.xs)
            .clip(HardPlayTheme.shapes.card)
            .background(colors.surface)
            .border(
                Space.hairline,
                if (confirming) colors.danger.copy(alpha = 0.55f) else colors.hairline,
                HardPlayTheme.shapes.card,
            )
            .padding(Space.md),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            // A 2dp ember bar marks an active source, matching the picker's rows and the
            // sort sheet's selection. A checkbox would undo the design system.
            Box(
                Modifier
                    .width(2.dp)
                    .height(32.dp)
                    .background(
                        if (source.enabled) {
                            colors.emberGradientVertical
                        } else {
                            SolidColor(colors.hairline)
                        },
                    ),
            )

            Column(Modifier.weight(1f)) {
                Text(
                    text = source.title,
                    style = HardPlayTheme.type.title,
                    color = if (source.enabled) colors.type else colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = sourceStatus(source),
                    style = HardPlayTheme.type.labelSmall,
                    color = colors.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (!confirming) {
                HardPlaySwitch(checked = source.enabled, onCheckedChange = onToggle)
                GhostIconButton(
                    icon = Icons.Rounded.RemoveCircleOutline,
                    contentDescription = "Remove ${source.title}",
                    onClick = onAskRemove,
                    size = 18.dp,
                    tint = colors.muted,
                )
            }
        }

        if (source.lastError != null && !confirming) {
            Box(Modifier.padding(top = Space.sm)) {
                Notice(text = source.lastError, emphasis = true)
            }
        }

        AnimatedVisibility(
            visible = confirming,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(Modifier.padding(top = Space.md)) {
                Text(
                    text = "Removing this source deletes its " +
                        "${Format.count(source.indexedCount)} indexed items, any tags you " +
                        "typed on them and where you had got to. Telegram keeps the files; " +
                        "HardPlay cannot get the rest back.",
                    style = HardPlayTheme.type.bodySmall,
                    color = colors.typeDim,
                )
                Box(Modifier.height(Space.md))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    QuietButton(text = "Keep it", onClick = onCancelRemove)
                    Box(Modifier.weight(1f))
                    GhostButton(
                        text = "Remove",
                        onClick = onConfirmRemove,
                        destructive = true,
                        small = true,
                    )
                }
            }
        }
    }
}

/**
 * `1,204 items · History complete · 2h ago`.
 *
 * `lastSyncAt` is epoch **millis** while `Format.relativeDate` takes Telegram's epoch
 * **seconds**; passing it straight through prints "56y ago", which is the kind of
 * detail that makes a status board look broken.
 */
private fun sourceStatus(source: ManagedSource): String = listOfNotNull(
    if (source.indexedCount > 0) {
        "${Format.count(source.indexedCount)} items"
    } else {
        "Not indexed yet"
    },
    if (source.indexedCount > 0) {
        if (source.backfillComplete) "History complete" else "Still indexing"
    } else {
        null
    },
    source.lastSyncAt.takeIf { it > 0L }?.let { Format.relativeDate(it / 1000) },
    if (source.enabled) null else "Hidden from library",
).joinToString("  ·  ")
