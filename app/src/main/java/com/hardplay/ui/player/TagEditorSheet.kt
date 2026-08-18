package com.hardplay.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.hardplay.data.db.entity.TagEntity
import com.hardplay.ui.components.GhostIconButton
import com.hardplay.ui.components.HardPlayTextField
import com.hardplay.ui.components.SheetHandle
import com.hardplay.ui.components.TagChip
import com.hardplay.ui.components.rememberHaptics
import com.hardplay.ui.theme.HardPlayTheme
import com.hardplay.ui.theme.Space

/**
 * The tag editor (PRD §6.2 §5).
 *
 * The autocomplete is the point of the screen, not a convenience. Free-text tagging
 * with no suggestions produces "behind the scenes", "Behind The Scenes" and "bts" as
 * three separate categories within a week, and once that has happened the tag filter
 * is worthless. Suggestions are ordered by existing usage so the tag you already use
 * is the easiest one to pick.
 */
@Composable
fun TagEditorSheet(
    title: String,
    caption: String,
    tags: List<TagEntity>,
    onAdd: (String) -> Unit,
    onRemove: (Long) -> Unit,
    suggest: suspend (String) -> List<String>,
    onDismiss: () -> Unit,
) {
    val colors = HardPlayTheme.colors
    val haptics = rememberHaptics()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var draft by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf(emptyList<String>()) }

    // Re-queried on every keystroke. It is one indexed lookup against a table with
    // as many rows as the user has tags, so debouncing it would add latency to hide
    // a cost that isn't there.
    LaunchedEffect(draft) {
        suggestions = suggest(draft)
    }

    val existing = remember(tags) { tags.map { it.name.lowercase() }.toSet() }

    fun commit(name: String) {
        val cleaned = name.trim()
        if (cleaned.isEmpty()) return
        haptics.confirm()
        onAdd(cleaned)
        draft = ""
    }

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
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = Space.gutter),
        ) {
            Text(text = "TAGS", style = HardPlayTheme.type.overline, color = colors.accent)
            Box(Modifier.height(4.dp))
            Text(
                text = title,
                style = HardPlayTheme.type.headline,
                color = colors.type,
                maxLines = 2,
            )

            Box(Modifier.height(Space.lg))

            // ---- current tags
            if (tags.isEmpty()) {
                Text(
                    text = "No tags on this item yet.",
                    style = HardPlayTheme.type.bodySmall,
                    color = colors.muted,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                    verticalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    tags.forEach { tag ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TagChip(
                                label = tag.name,
                                selected = true,
                                // Tapping a tag that is already on the item removes
                                // it. There is nothing else tapping it could mean,
                                // and it saves a row of delete buttons.
                                onClick = {
                                    haptics.tick()
                                    onRemove(tag.id)
                                },
                            )
                        }
                    }
                }
            }

            Box(Modifier.height(Space.lg))

            // ---- entry
            HardPlayTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = "Add a tag",
                imeAction = ImeAction.Done,
                onImeAction = { commit(draft) },
                trailing = {
                    if (draft.isNotBlank()) {
                        GhostIconButton(
                            icon = Icons.Rounded.Add,
                            contentDescription = "Add ${draft.trim()}",
                            onClick = { commit(draft) },
                            size = 18.dp,
                            tint = colors.accent,
                        )
                    }
                },
            )

            // ---- suggestions
            val offered = suggestions.filterNot { it.lowercase() in existing }
            if (offered.isNotEmpty()) {
                Box(Modifier.height(Space.md))
                Text(
                    text = if (draft.isBlank()) "MOST USED" else "MATCHING",
                    style = HardPlayTheme.type.overline,
                    color = colors.muted,
                )
                Box(Modifier.height(Space.sm))
                Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Space.xs),
                        verticalArrangement = Arrangement.spacedBy(Space.xs),
                    ) {
                        offered.forEach { name ->
                            TagChip(label = name, onClick = { commit(name) })
                        }
                    }
                }
            }

            // ---- caption, for context while tagging
            if (caption.isNotBlank()) {
                Box(Modifier.height(Space.lg))
                Text(text = "CAPTION", style = HardPlayTheme.type.overline, color = colors.muted)
                Box(Modifier.height(Space.xs))
                Text(
                    text = caption,
                    style = HardPlayTheme.type.bodySmall,
                    color = colors.typeDim,
                    modifier = Modifier
                        .heightIn(max = 120.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }

            Box(Modifier.height(Space.xxl))
        }
    }
}
