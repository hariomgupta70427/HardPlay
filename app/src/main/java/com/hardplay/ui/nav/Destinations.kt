package com.hardplay.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Where the app can be.
 *
 * Split into [HomeTab] — the five destinations that keep the bottom bar — and
 * [Routes] for everything that takes the whole screen. That split is the bar's own
 * definition: rather than maintaining a list of routes to hide it on, the bar is
 * drawn if and only if the current route belongs to a tab, so a new full-screen
 * destination cannot accidentally inherit a nav bar, and no list of exceptions has to
 * be kept in step.
 *
 * **No tab route takes an argument, deliberately.** Cross-tab requests — Discover
 * asking Library to show one tag — go through [LibraryFocus] instead. A route with an
 * optional argument is registered under its whole pattern, which makes its id different
 * from the bare route's, and every `startDestination` or `popUpTo` naming the wrong one
 * of the two fails at launch. See [LibraryFocus] for the rest of the reasoning.
 */
enum class HomeTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    /** The grid. The app's home, and what the back button returns to. */
    LIBRARY("tab/library", "Library", Icons.Rounded.GridView),

    /**
     * Search and the recommendation shelves.
     *
     * Search lives here rather than in the library's chrome because an empty query is
     * the interesting state: with nothing typed this is the "what should I watch"
     * screen, and that deserves a destination rather than a text field that appears.
     */
    DISCOVER("tab/discover", "Discover", Icons.Rounded.Search),

    SAVED("tab/saved", "Saved", Icons.Rounded.FavoriteBorder),

    HISTORY("tab/history", "History", Icons.Rounded.History),

    /** Sources and settings — everything about the library rather than in it. */
    MANAGE("tab/manage", "Manage", Icons.Rounded.Tune),
    ;

    companion object {
        val Start = LIBRARY

        /** Which tab a live destination belongs to, or null for a full-screen route. */
        fun forRoute(route: String?): HomeTab? = entries.firstOrNull { it.route == route }
    }
}

/** Full-screen destinations. None of these draw the bottom bar. */
object Routes {
    const val PLAYER = "player"
    const val SETTINGS = "settings"
    const val SOURCES = "sources"

    /**
     * The component specimen screen. Registered only in debug builds — it is a
     * design-review tool, and shipping a route to it in release would put every
     * component in the app one deep link away from a user.
     */
    const val DESIGN = "design"

    fun player(localId: Long) = "$PLAYER/$localId"
}
