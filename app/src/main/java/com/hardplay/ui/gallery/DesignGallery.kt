package com.hardplay.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hardplay.data.db.projection.LibraryRow
import com.hardplay.ui.components.BufferingMark
import com.hardplay.ui.components.CountBadge
import com.hardplay.ui.components.EmberButton
import com.hardplay.ui.components.EmptyState
import com.hardplay.ui.components.GhostButton
import com.hardplay.ui.components.Hairline
import com.hardplay.ui.components.HardPlaySwitch
import com.hardplay.ui.components.HardPlayTextField
import com.hardplay.ui.components.MetaChip
import com.hardplay.ui.components.Notice
import com.hardplay.ui.components.PosterCard
import com.hardplay.ui.components.PosterSkeleton
import com.hardplay.ui.components.QuietButton
import com.hardplay.ui.components.ScreenHeader
import com.hardplay.ui.components.SearchGlyph
import com.hardplay.ui.components.SettingRow
import com.hardplay.ui.components.Shelf
import com.hardplay.ui.components.SpeedChip
import com.hardplay.ui.components.TagChip
import com.hardplay.ui.nav.HardPlayBottomBar
import com.hardplay.ui.nav.HomeTab
import com.hardplay.ui.theme.HardPlaySurface
import com.hardplay.ui.theme.HardPlayTheme
import com.hardplay.ui.theme.Space

/**
 * A live specimen of the design system on a real panel.
 *
 * This is not a debug screen to be deleted — grain, ember gradients and OLED
 * blacks all look different in an emulator, in Android Studio's preview and in
 * the hand, and this is the only way to judge them.
 *
 * The rule that keeps it worth having: **it shows components at the configuration
 * they actually ship in.** For a long stretch it showed the poster card only at 2:3
 * with no overflow control, while every screen in the app drew it at 16:9 with one —
 * so the sheet was reassuring about a card nobody was looking at.
 */
private data class Specimen(
    val title: String,
    val duration: String,
    val source: String,
    val resume: Float,
    val unseen: Boolean,
    val saved: Boolean = false,
)

private val specimens = listOf(
    Specimen("Nightshift", "1:42:08", "Vault", 0.34f, false),
    Specimen("Low Tide in Reverse", "58:12", "Vault", 0f, true),
    Specimen("Ash Wednesday", "2:11:47", "Second Reel", 0.82f, false, saved = true),
    Specimen("The Long Quiet", "44:03", "Second Reel", 0f, false),
)

/**
 * Sample rows for the shelf specimen.
 *
 * Built through `LibraryRow`'s own constructor rather than a stand-in, so the shelf
 * here is the shelf the app draws. They carry no artwork on purpose: the fallback
 * initial is one of the things this screen exists to judge, and it is the state a
 * freshly synced library spends its first minutes in.
 */
private fun sampleRow(
    id: Long,
    title: String,
    channel: String,
    seconds: Int,
    bytes: Long,
    position: Long? = null,
    saved: Boolean = false,
) = LibraryRow(
    localId = id,
    chatId = -1001,
    messageId = id,
    type = "VIDEO",
    title = title,
    caption = title,
    date = 1_755_000_000L,
    durationSeconds = seconds,
    fileSizeBytes = bytes,
    width = 1920,
    height = 1080,
    thumbnailFileId = null,
    previewFileId = null,
    posterPath = null,
    minithumbnail = null,
    posterFileId = null,
    posterForMessageId = null,
    fileId = id.toInt(),
    remoteFileId = "specimen:$id",
    channelTitle = channel,
    channelEnabled = true,
    positionMs = position,
    playbackDurationMs = seconds * 1_000L,
    completed = false,
    playCount = 1,
    lastPlayedAt = if (position != null) 1_755_000_000_000L else null,
    favouritedAt = if (saved) 1_755_000_000_000L else null,
)

private val shelfRows = listOf(
    sampleRow(1, "Harbour Lights", "Night Reel", 1_242, 1_840_000_000L, position = 420_000L),
    sampleRow(2, "Rooftop b-roll", "Night Reel", 512, 640_000_000L),
    sampleRow(3, "Cold Open v3", "Studio Dailies", 2_880, 3_100_000_000L, saved = true),
)

@Composable
fun DesignGallery() {
    val colors = HardPlayTheme.colors
    val type = HardPlayTheme.type

    var selectedTags by remember { mutableStateOf(setOf("4K")) }
    var speedIndex by remember { mutableIntStateOf(2) }
    var switched by remember { mutableStateOf(true) }
    var field by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(HomeTab.LIBRARY) }
    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    HardPlaySurface(animatedGrain = true) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = WindowInsets.systemBars.asPaddingValues(),
            horizontalArrangement = Arrangement.spacedBy(Space.gridGap),
            verticalArrangement = Arrangement.spacedBy(Space.lg),
            modifier = Modifier.padding(horizontal = Space.gutter),
        ) {
            fullWidth {
                Column(Modifier.padding(top = Space.xxl, bottom = Space.sm)) {
                    Text("OXBLOOD & EMBER", style = type.overline, color = colors.accent)
                    Text("Design\nsystem", style = type.display, color = colors.type)
                    Text(
                        "Archivo narrow at 900, Instrument Serif for the aside.",
                        style = type.editorialSmall,
                        color = colors.typeDim,
                        modifier = Modifier.padding(top = Space.sm),
                    )
                }
            }

            fullWidth { GallerySection("Screen header") }
            fullWidth {
                ScreenHeader(
                    title = "Library",
                    overline = "1,842 items",
                    subtitle = "1,204 videos · 3.1 TB",
                )
            }

            fullWidth { GallerySection("Buttons") }
            fullWidth {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    EmberButton("Resume playback", onClick = {}, fillWidth = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        GhostButton("Edit tags", onClick = {})
                        GhostButton("Remove", onClick = {}, destructive = true)
                        QuietButton("Not now", onClick = {})
                    }
                }
            }

            fullWidth { GallerySection("Chips") }
            fullWidth {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                        listOf("4K" to 12, "HDR" to 7, "Archive" to 148).forEach { (label, count) ->
                            TagChip(
                                label = label,
                                count = count,
                                selected = label in selectedTags,
                                onClick = {
                                    selectedTags = if (label in selectedTags) {
                                        selectedTags - label
                                    } else {
                                        selectedTags + label
                                    }
                                },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                        speeds.forEachIndexed { i, s ->
                            SpeedChip(speed = s, selected = i == speedIndex, onClick = { speedIndex = i })
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Space.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MetaChip("2160p", emphasised = true)
                        MetaChip("hevc")
                        MetaChip("1.84 gb")
                        CountBadge(count = 3)
                    }
                }
            }

            fullWidth { GallerySection("Controls") }
            fullWidth {
                Column {
                    SettingRow(
                        title = "Sharper video artwork",
                        subtitle = "Decodes a frame from videos Telegram gave no thumbnail",
                        trailing = {
                            HardPlaySwitch(checked = switched, onCheckedChange = { switched = it })
                        },
                    )
                    Hairline(inset = true)
                    Box(Modifier.padding(vertical = Space.md)) {
                        HardPlayTextField(
                            value = field,
                            onValueChange = { field = it },
                            placeholder = "Captions and tags",
                            leading = { focused -> SearchGlyph(active = focused || field.isNotEmpty()) },
                        )
                    }
                }
            }

            fullWidth { GallerySection("Buffering mark") }
            fullWidth {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Space.xl),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BufferingMark()
                    BufferingMark(progress = 0.35f)
                    BufferingMark(progress = 0.78f, markSize = 28.dp)
                    Text("12.4 / 318 MB", style = type.timecodeSmall, color = colors.muted)
                }
            }

            // The shipping configuration: 16:9, an overflow control on every cell, two
            // reserved title lines. This is what the grid actually looks like.
            fullWidth { GallerySection("Poster grid — 16:9, as shipped") }
            items(specimens) { item ->
                PosterCard(
                    title = item.title,
                    aspect = WIDE_ASPECT,
                    durationLabel = item.duration,
                    sourceLabel = item.source,
                    resumeFraction = item.resume,
                    unseen = item.unseen,
                    saved = item.saved,
                    titleLines = 2,
                    onClick = {},
                    onMenu = {},
                    onLongClick = {},
                )
            }

            fullWidth { GallerySection("Poster grid — 2:3") }
            items(specimens.take(2)) { item ->
                PosterCard(
                    title = item.title,
                    durationLabel = item.duration,
                    sourceLabel = item.source,
                    resumeFraction = item.resume,
                    unseen = item.unseen,
                    onClick = {},
                    onLongClick = {},
                )
            }

            fullWidth { GallerySection("Shelf") }
            fullWidth {
                Shelf(
                    key = "specimen",
                    title = "Pick up where you left off",
                    overline = "Continue",
                    rows = shelfRows,
                    onOpen = {},
                    onMenu = {},
                    // The grid already applies the screen gutter, so the shelf must not
                    // add a second one.
                    gutter = 0.dp,
                    cardWidth = 152.dp,
                )
            }

            fullWidth { GallerySection("Skeletons") }
            items(listOf(0, 1)) { PosterSkeleton(aspect = WIDE_ASPECT, titleLines = 2) }

            fullWidth { GallerySection("Notices and empties") }
            fullWidth {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Notice(text = "Indexing Night Reel — 1,204 so far", action = {
                        BufferingMark(markSize = 18.dp, strokeWidth = 1.5.dp)
                    })
                    Notice(
                        text = "Telegram is rate-limiting this account. Try again in 4 min.",
                        emphasis = true,
                    )
                    EmptyState(
                        overline = "No matches",
                        headline = "Nothing fits those filters.",
                        body = "Selected tags have to all be present on an item.",
                        action = { GhostButton(text = "Clear filters", onClick = {}) },
                    )
                }
            }

            fullWidth { GallerySection("Bottom bar") }
            fullWidth {
                HardPlayBottomBar(
                    current = tab,
                    onSelect = { tab = it },
                    counts = mapOf(HomeTab.SAVED to 24, HomeTab.HISTORY to 312),
                )
            }

            fullWidth { Column(Modifier.padding(bottom = Space.xxxl)) {} }
        }
    }
}

@Composable
private fun GallerySection(label: String) {
    val colors = HardPlayTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = Space.xl, bottom = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Text(label.uppercase(), style = HardPlayTheme.type.overline, color = colors.muted)
        Box(
            Modifier
                .weight(1f)
                .height(Space.hairline)
                .background(colors.hairline),
        )
    }
}

/** Spans a full grid row. Saves repeating the `span` lambda at every call. */
private fun LazyGridScope.fullWidth(
    content: @Composable () -> Unit,
) = item(span = { GridItemSpan(maxLineSpan) }) { content() }

/** `CardAspect.WIDE.ratio`, inlined so the gallery does not depend on the data layer. */
private const val WIDE_ASPECT = 16f / 9f
