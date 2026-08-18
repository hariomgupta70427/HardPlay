package com.hardplay.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.hardplay.data.db.entity.ChannelEntity
import com.hardplay.data.db.projection.TagFacet
import com.hardplay.data.model.LibraryQuery
import com.hardplay.data.model.LibrarySort
import com.hardplay.data.model.TypeFilter
import com.hardplay.ui.components.GhostButton
import com.hardplay.ui.components.Hairline
import com.hardplay.ui.components.TagChip
import com.hardplay.ui.components.SheetHandle
import com.hardplay.ui.components.rememberHaptics
import com.hardplay.ui.theme.HardPlayTheme
import com.hardplay.ui.theme.Space


@Composable
fun FilterSheet(
    query: LibraryQuery,
    facets: List<TagFacet>,
    sources: List<ChannelEntity>,
    onToggleTag: (Long) -> Unit,
    onToggleSource: (Long) -> Unit,
    onSetType: (TypeFilter) -> Unit,
    onToggleUnseen: () -> Unit,
    onToggleSaved: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = HardPlayTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.bgRaised,
        contentColor = colors.type,
        scrimColor = colors.scrim,
        shape = HardPlayTheme.shapes.sheet,
        dragHandle = { SheetHandle() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            SheetTitle(
                title = "Filter",
                subtitle = if (query.activeFacetCount > 0) {
                    "${query.activeFacetCount} active"
                } else {
                    "Nothing applied"
                },
                trailing = {
                    if (query.activeFacetCount > 0) {
                        GhostButton(text = "Clear", onClick = onClear, small = true)
                    }
                },
            )

            Column(
                Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                SheetGroup("Type") {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                        TypeFilter.entries.forEach { filter ->
                            TagChip(
                                label = filter.label,
                                selected = query.typeFilter == filter,
                                onClick = { onSetType(filter) },
                            )
                        }
                    }
                }

                SheetGroup("State") {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                        TagChip(
                            label = "Unseen only",
                            selected = query.unseenOnly,
                            onClick = onToggleUnseen,
                        )
                        TagChip(
                            label = "Saved",
                            selected = query.favouritesOnly,
                            onClick = onToggleSaved,
                        )
                    }
                }

                if (sources.size > 1) {
                    SheetGroup("Source") {
                        FlowChips {
                            sources.forEach { source ->
                                TagChip(
                                    label = source.title,
                                    selected = source.chatId in query.sourceIds,
                                    onClick = { onToggleSource(source.chatId) },
                                )
                            }
                        }
                    }
                }

                SheetGroup(
                    title = "Tags",
                    // The AND semantics are stated rather than left to be inferred
                    // from an empty grid.
                    note = if (query.tagIds.size > 1) "Items must carry all selected tags" else null,
                ) {
                    val visible = facets.filter { it.itemCount > 0 || it.id in query.tagIds }
                    if (visible.isEmpty()) {
                        Text(
                            text = "No tags yet. Open an item and add some, or let " +
                                "caption parsing fill them in.",
                            style = HardPlayTheme.type.bodySmall,
                            color = colors.muted,
                        )
                    } else {
                        FlowChips {
                            visible.forEach { facet ->
                                TagChip(
                                    label = facet.name,
                                    selected = facet.id in query.tagIds,
                                    count = facet.itemCount,
                                    onClick = { onToggleTag(facet.id) },
                                )
                            }
                        }
                    }
                }

                Box(Modifier.height(Space.xl))
            }
        }
    }
}

@Composable
fun SortSheet(
    current: LibrarySort,
    onSelect: (LibrarySort) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = HardPlayTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptics = rememberHaptics()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.bgRaised,
        contentColor = colors.type,
        scrimColor = colors.scrim,
        shape = HardPlayTheme.shapes.sheet,
        dragHandle = { SheetHandle() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            SheetTitle(title = "Sort", subtitle = current.label)

            LibrarySort.entries.forEach { sort ->
                val selected = sort == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            haptics.tick()
                            onSelect(sort)
                        }
                        .padding(horizontal = Space.gutter, vertical = Space.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Ember bar marks the selection; a radio button would be a
                    // Material tell on an otherwise custom sheet.
                    Box(
                        Modifier
                            .width(2.dp)
                            .height(18.dp)
                            .background(
                                if (selected) {
                                    colors.emberGradientVertical
                                } else {
                                    SolidColor(colors.hairline)
                                },
                            ),
                    )
                    Box(Modifier.width(Space.md))
                    Text(
                        text = sort.label,
                        style = HardPlayTheme.type.title,
                        color = if (selected) colors.type else colors.typeDim,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                if (sort != LibrarySort.entries.last()) Hairline(inset = true)
            }

            Box(Modifier.height(Space.lg))
        }
    }
}

@Composable
private fun SheetTitle(
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = HardPlayTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = Space.gutter, end = Space.gutter, bottom = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = HardPlayTheme.type.headline, color = colors.type)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = HardPlayTheme.type.labelSmall,
                    color = colors.muted,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun SheetGroup(
    title: String,
    note: String? = null,
    content: @Composable () -> Unit,
) {
    val colors = HardPlayTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter, vertical = Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(
            text = title.uppercase(),
            style = HardPlayTheme.type.overline,
            color = colors.muted,
        )
        content()
        if (note != null) {
            Text(
                text = note,
                style = HardPlayTheme.type.bodySmall,
                color = colors.muted,
            )
        }
    }
}

/**
 * Wrapping chip rows.
 *
 * `FlowRow` is used rather than a `LazyRow` because a tag sheet needs every chip
 * visible at once to be scanned — horizontal scrolling hides options behind a
 * gesture nobody discovers.
 */
@Composable
private fun FlowChips(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
        content = content,
    )
}
