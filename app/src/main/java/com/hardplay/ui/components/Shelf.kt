package com.hardplay.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hardplay.core.Format
import com.hardplay.data.db.projection.LibraryRow
import com.hardplay.ui.image.PosterSource
import com.hardplay.ui.theme.HardPlayTheme
import com.hardplay.ui.theme.Space

/**
 * A horizontal row of cards under an editorial heading.
 *
 * The unit Discover is built from, and the reason it is shared rather than written
 * per shelf: six shelves each with their own header spacing is six chances for the
 * screen to look assembled instead of designed.
 *
 * Draws nothing when [rows] is empty. That is the contract the recommendation screen
 * depends on — most shelves are empty on a fresh library, and a heading over a void
 * makes an app look broken rather than new.
 *
 * @param key namespace for the item keys. Two shelves can hold the same item, and a
 *   `LazyRow` key collision inside one scrolling parent drops one of them.
 * @param trailing an action at the end of the heading — "See all", usually.
 */
@Composable
fun Shelf(
    key: String,
    title: String,
    rows: List<LibraryRow>,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
    overline: String? = null,
    onMenu: ((LibraryRow) -> Unit)? = null,
    cardWidth: Dp = 172.dp,
    aspect: Float = 16f / 9f,
    gutter: Dp = Space.gutter,
    trailing: @Composable (() -> Unit)? = null,
) {
    if (rows.isEmpty()) return
    val colors = HardPlayTheme.colors

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = gutter, end = gutter, bottom = Space.sm),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                if (overline != null) {
                    Text(
                        text = overline.uppercase(),
                        style = HardPlayTheme.type.overline,
                        color = colors.accent,
                    )
                    Box(Modifier.height(4.dp))
                }
                Text(
                    text = title,
                    style = HardPlayTheme.type.displaySmall,
                    color = colors.type,
                )
            }
            trailing?.invoke()
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(Space.gridGap),
            contentPadding = PaddingValues(start = gutter, end = gutter),
        ) {
            items(rows, key = { "$key-${it.localId}" }) { row ->
                Box(Modifier.width(cardWidth)) {
                    PosterCard(
                        title = row.title,
                        onClick = { onOpen(row.localId) },
                        aspect = aspect,
                        thumbnail = PosterSource.of(row).takeIf { !it.isEmpty },
                        durationLabel = Format.duration(row.durationSeconds),
                        sourceLabel = row.channelTitle.takeIf { it.isNotBlank() },
                        resumeFraction = row.resumeFraction,
                        unseen = row.unseen,
                        saved = row.isFavourite,
                        titleLines = 2,
                        onMenu = onMenu?.let { menu -> { menu(row) } },
                    )
                }
            }
        }
    }
}
