package com.hardplay.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hardplay.core.Format
import com.hardplay.data.model.CardAspect
import com.hardplay.data.prefs.SettingsStore
import com.hardplay.ui.components.GhostButton
import com.hardplay.ui.components.Hairline
import com.hardplay.ui.components.HardPlaySwitch
import com.hardplay.ui.components.HardPlayTopBar
import com.hardplay.ui.components.Notice
import com.hardplay.ui.components.SettingGroup
import com.hardplay.ui.components.SettingRow
import com.hardplay.ui.components.TagChip
import com.hardplay.ui.theme.HardPlaySurface
import com.hardplay.ui.theme.HardPlayTheme
import com.hardplay.ui.theme.Space

/**
 * Settings.
 *
 * Ordered by how often a setting is touched, not by subsystem: presence and privacy
 * first because they are the reason this app is sideloaded, storage next because it is
 * the only setting with a running cost, and the diagnostics last where they belong.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenSources: () -> Unit,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
    /** Debug builds only; null in release, and the row is then not drawn. */
    onOpenDesignGallery: (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = HardPlayTheme.colors
    val current = settings

    HardPlaySurface(modifier = modifier.fillMaxSize(), bloom = false) {
        Column(Modifier.fillMaxSize()) {
            HardPlayTopBar(title = "Settings", scrolled = true, onBack = onBack)

            if (current == null) {
                Box(Modifier.fillMaxSize())
                return@Column
            }

            LazyColumn(
                Modifier
                    .weight(1f)
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(bottom = Space.xxxl),
            ) {
                if (ui.notice != null) {
                    item {
                        Box(Modifier.padding(horizontal = Space.gutter, vertical = Space.sm)) {
                            Notice(
                                text = ui.notice.orEmpty(),
                                onDismiss = viewModel::dismissNotice,
                            )
                        }
                    }
                }

                // ---------------------------------------------------- presence
                item { SettingGroup("Presence") }
                item {
                    SwitchRow(
                        title = "Discreet launcher",
                        subtitle = if (current.discreetLauncher) {
                            "Shows as “Archive” with a neutral icon"
                        } else {
                            "Shows as “HardPlay” with the ember mark"
                        },
                        checked = current.discreetLauncher,
                        onCheckedChange = viewModel::setDiscreetLauncher,
                    )
                }
                item { Hairline(inset = true) }
                item {
                    SwitchRow(
                        title = "Require unlock",
                        subtitle = "Biometric or device credential on every launch",
                        checked = current.requireUnlock,
                        onCheckedChange = viewModel::setRequireUnlock,
                    )
                }
                item { Hairline(inset = true) }
                item {
                    SwitchRow(
                        title = "Block screenshots",
                        subtitle = "Keeps the library out of screenshots and the recents " +
                            "thumbnail. Some devices draw a picture-in-picture window " +
                            "black while this is on.",
                        checked = current.blockScreenshots,
                        onCheckedChange = viewModel::setBlockScreenshots,
                    )
                }

                // ---------------------------------------------------- library
                item { SettingGroup("Library") }
                item {
                    SettingRow(
                        title = "Sources",
                        subtitle = "Add or remove Telegram channels",
                        onClick = onOpenSources,
                        trailing = { GhostButton(text = "Manage", onClick = onOpenSources, small = true) },
                    )
                }
                item { Hairline(inset = true) }
                item {
                    SwitchRow(
                        title = "Tag from captions",
                        subtitle = "Parses hashtags, bracketed labels and quality tokens",
                        checked = current.autoTagCaptions,
                        onCheckedChange = viewModel::setAutoTag,
                    )
                }
                item { Hairline(inset = true) }
                item {
                    SwitchRow(
                        title = "Fold paired screenshots",
                        subtitle = "Hide a still that only previews the video beside it",
                        checked = current.hidePairedStills,
                        onCheckedChange = viewModel::setHidePairedStills,
                    )
                }
                item { Hairline(inset = true) }
                item {
                    SwitchRow(
                        title = "Sharper video artwork",
                        subtitle = "Telegram gives a video one small thumbnail and no " +
                            "larger size, so HardPlay decodes a frame from videos that " +
                            "have no artwork at all. A couple of megabytes each, once.",
                        checked = current.sharpVideoArtwork,
                        onCheckedChange = viewModel::setSharpVideoArtwork,
                    )
                }
                item { Hairline(inset = true) }
                item {
                    SwitchRow(
                        title = "Background sync",
                        subtitle = "Every 6 hours, on Wi-Fi, when the battery isn't low",
                        checked = current.backgroundSync,
                        onCheckedChange = viewModel::setBackgroundSync,
                    )
                }
                item { Hairline(inset = true) }
                item {
                    ChoiceRow(
                        title = "Card shape",
                        subtitle = "16:9 suits landscape video; 2:3 suits artwork",
                        options = CardAspect.entries,
                        selected = current.cardAspect,
                        label = { it.label },
                        onSelect = viewModel::setCardAspect,
                    )

                }

                item { Hairline(inset = true) }

                item {
                    ChoiceRow(
                        title = "Grid columns",
                        subtitle = "Zero adapts to the screen width",
                        options = COLUMN_CHOICES,
                        selected = current.gridColumns,
                        label = { if (it == 0) "Auto" else it.toString() },
                        onSelect = viewModel::setGridColumns,
                    )
                }

                // ---------------------------------------------------- playback
                item { SettingGroup("Playback") }
                item {
                    ChoiceRow(
                        title = "Skip step",
                        subtitle = "Double-tap the left or right edge of the video",
                        options = SettingsStore.SKIP_CHOICES,
                        selected = current.skipSeconds,
                        label = { "${it}s" },
                        onSelect = viewModel::setSkipSeconds,
                    )
                }
                item { Hairline(inset = true) }
                item {
                    ChoiceRow(
                        title = "Default speed",
                        subtitle = "Applied when a new item opens",
                        options = SettingsStore.SPEED_CHOICES,
                        selected = current.playbackSpeed,
                        label = { Format.speed(it) },
                        onSelect = viewModel::setDefaultSpeed,
                    )
                }
                item { Hairline(inset = true) }
                item {
                    SettingRow(
                        title = "Clear resume positions",
                        subtitle = "Empties the continue-watching shelf",
                        trailing = {
                            GhostButton(
                                text = "Clear",
                                onClick = viewModel::clearWatchHistory,
                                small = true,
                            )
                        },
                    )
                }

                // ----------------------------------------------------- storage
                item { SettingGroup("Storage") }
                item {
                    ChoiceRow(
                        title = "Chunk cache cap",
                        subtitle = "Currently holding ${Format.bytes(ui.cacheBytes)}",
                        options = CACHE_CHOICES,
                        selected = current.cacheCapBytes,
                        label = ::cacheCapLabel,
                        onSelect = viewModel::setCacheCap,
                    )
                }
                item { Hairline(inset = true) }
                item {
                    SettingRow(
                        title = "Clear cached media",
                        subtitle = "Keeps the index, the tags and the session",
                        trailing = {
                            GhostButton(
                                text = "Clear",
                                onClick = viewModel::clearCache,
                                small = true,
                                enabled = !ui.busy,
                            )
                        },
                    )
                }
                item { Hairline(inset = true) }
                item {
                    SettingRow(
                        title = "Clear extracted artwork",
                        subtitle = "Currently holding ${Format.bytes(ui.artworkBytes)}. " +
                            "Items fall back to Telegram's own thumbnail.",
                        trailing = {
                            GhostButton(
                                text = "Clear",
                                onClick = viewModel::clearExtractedArtwork,
                                small = true,
                                enabled = !ui.busy && ui.artworkBytes > 0,
                            )
                        },
                    )
                }
                item { Hairline(inset = true) }
                item {
                    SettingRow(
                        title = "Rebuild search index",
                        subtitle = "Use if search misses something you can see",
                        trailing = {
                            GhostButton(
                                text = "Rebuild",
                                onClick = viewModel::rebuildSearchIndex,
                                small = true,
                                enabled = !ui.busy,
                            )
                        },
                    )
                }

                // ------------------------------------------------------ engine
                item { SettingGroup("Engine") }
                item {
                    SettingRow(
                        title = "Telegram engine",
                        subtitle = ui.engineSummary,
                    )
                }
                item { Hairline(inset = true) }
                item {
                    SettingRow(
                        title = "Sign out",
                        subtitle = "Ends the Telegram session and wipes its local database",
                        trailing = {
                            GhostButton(
                                text = "Sign out",
                                onClick = { viewModel.signOut(onSignedOut) },
                                small = true,
                                destructive = true,
                                enabled = !ui.busy && !ui.isDemo,
                            )
                        },
                    )
                }

                if (onOpenDesignGallery != null) {
                    item { SettingGroup("Debug") }
                    item {
                        SettingRow(
                            title = "Design system",
                            subtitle = "Every component on one sheet — grain and ember on a real panel",
                            onClick = onOpenDesignGallery,
                            trailing = {
                                GhostButton(text = "Open", onClick = onOpenDesignGallery, small = true)
                            },
                        )
                    }
                }

                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(Space.xxl),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "HardPlay ${ui.versionName}",
                            style = HardPlayTheme.type.timecodeSmall,
                            color = colors.muted,
                        )
                        Box(Modifier.height(Space.xs))
                        Text(
                            text = "No analytics. No crash reporting. " +
                                "Nothing leaves the device except traffic to Telegram.",
                            style = HardPlayTheme.type.editorialSmall,
                            color = colors.muted,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A settings row whose value is a switch.
 *
 * Exists so the *row* is the target rather than the 40×22dp control. [SettingRow] has
 * always documented that intent, but it only takes effect when a caller passes
 * `onClick` — and not one of the seven switch rows here did, so every toggle in
 * Settings was a small thing to hit one-handed. One wrapper makes that impossible to
 * forget again. The switch keeps its own gesture and the inner one wins, so tapping the
 * control does not fire both.
 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        onClick = { onCheckedChange(!checked) },
        trailing = { HardPlaySwitch(checked = checked, onCheckedChange = onCheckedChange) },
    )
}

/**
 * A row whose value is picked from a short set of chips.
 *
 * Chips rather than a dropdown: every option here has at most five values, and a
 * menu hides them behind a tap for no gain. It also keeps the settings screen using
 * the same chip the filter sheet does.
 */
@Composable
private fun <T> ChoiceRow(
    title: String,
    subtitle: String?,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
) {
    val colors = HardPlayTheme.colors
    Column(Modifier.fillMaxWidth().padding(vertical = Space.sm)) {
        Column(Modifier.padding(horizontal = Space.gutter)) {
            Text(text = title, style = HardPlayTheme.type.title, color = colors.type)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = HardPlayTheme.type.bodySmall,
                    color = colors.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Box(Modifier.height(Space.sm))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.gutter),
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            options.forEach { option ->
                TagChip(
                    label = label(option),
                    selected = option == selected,
                    enabled = enabled,
                    onClick = { if (enabled) onSelect(option) },
                )
            }
        }
    }
}

private val COLUMN_CHOICES = listOf(0, 1, 2, 3, 4)
