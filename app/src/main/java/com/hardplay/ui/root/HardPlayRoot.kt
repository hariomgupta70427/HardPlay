package com.hardplay.ui.root

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hardplay.BuildConfig
import com.hardplay.ui.channels.ChannelPickerScreen
import com.hardplay.ui.components.HardPlayTopBar
import com.hardplay.ui.discover.DiscoverScreen
import com.hardplay.ui.gallery.DesignGallery
import com.hardplay.ui.gate.UnlockScreen
import com.hardplay.ui.history.HistoryScreen
import com.hardplay.ui.library.LibraryScreen
import com.hardplay.ui.login.LoginScreen
import com.hardplay.ui.manage.ManageScreen
import com.hardplay.ui.nav.HardPlayBottomBar
import com.hardplay.ui.nav.HomeShellViewModel
import com.hardplay.ui.nav.HomeTab
import com.hardplay.ui.nav.Routes
import com.hardplay.ui.player.PlayerScreen
import com.hardplay.ui.player.PlayerViewModel
import com.hardplay.ui.saved.SavedScreen
import com.hardplay.ui.settings.SettingsScreen
import com.hardplay.ui.theme.HardPlaySurface
import com.hardplay.ui.theme.Motion

/**
 * The app's root.
 *
 * Gate states are chosen here; only the signed-in, has-sources case gets a
 * `NavHost`. That split is what keeps the lock screen and the login screen off the
 * back stack — see [RootViewModel].
 */
@Composable
fun HardPlayRoot(
    modifier: Modifier = Modifier,
    viewModel: RootViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when (state) {
        RootState.Starting -> HardPlaySurface(modifier.fillMaxSize(), bloom = true) { Box(Modifier) }

        RootState.Locked -> UnlockScreen(onUnlocked = viewModel::onUnlocked, modifier = modifier)

        RootState.NeedsAuth -> LoginScreen(modifier = modifier)

        RootState.NeedsSources -> ChannelPickerScreen(
            // No callback work needed: adding a channel moves the channel count above
            // zero, which moves the root state to Ready on its own.
            onDone = {},
            firstRun = true,
            modifier = modifier,
        )

        RootState.Ready -> HardPlayNavHost(modifier = modifier)
    }
}

/**
 * The signed-in navigation graph.
 *
 * Two structural decisions worth not undoing:
 *
 *  * **[SharedTransitionLayout] sits outside the `NavHost`.** A shared element needs
 *    both the outgoing and the incoming screen inside the same layout to measure
 *    against, so a per-destination wrapper would have neither able to see the other.
 *    The poster grows into the player from *every* grid, which is why each tab passes
 *    the scopes down rather than only Library.
 *  * **The bottom bar is derived from the route, not tracked.** It is drawn if and only
 *    if the live destination belongs to a [HomeTab], so adding a full-screen
 *    destination cannot accidentally inherit a nav bar, and no list of exceptions has
 *    to be maintained.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun HardPlayNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    shellViewModel: HomeShellViewModel = hiltViewModel(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentTab = HomeTab.forRoute(backStackEntry?.destination?.route)
    val counts by shellViewModel.counts.collectAsStateWithLifecycle()

    fun openTab(tab: HomeTab) {
        if (tab == currentTab) return
        navController.navigate(tab.route) {
            // By id rather than by route: `popUpTo` matches a destination's id, and every
            // tab route here is argument-free precisely so that the two cannot disagree.
            popUpTo(navController.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            // Each tab keeps its scroll position and its paging state across
            // switches. Without this, coming back to Library re-fetches page one and
            // drops the user at the top.
            restoreState = true
        }
    }

    fun openItem(localId: Long) = navController.navigate(Routes.player(localId))

    SharedTransitionLayout(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = HomeTab.Start.route,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                // The poster carries the motion, so the screens themselves only need to
                // get out of its way. A slide here would fight the shared element.
                enterTransition = { fadeIn(tween(Motion.Standard, easing = Motion.Ember)) },
                exitTransition = { fadeOut(tween(Motion.Quick, easing = Motion.Smooth)) },
                popEnterTransition = { fadeIn(tween(Motion.Standard, easing = Motion.Ember)) },
                popExitTransition = { fadeOut(tween(Motion.Quick, easing = Motion.Smooth)) },
            ) {
                composable(HomeTab.LIBRARY.route) {
                    LibraryScreen(
                        onOpenItem = ::openItem,
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                        onOpenSources = { navController.navigate(Routes.SOURCES) },
                        onOpenSearch = { openTab(HomeTab.DISCOVER) },
                        sharedScope = this@SharedTransitionLayout,
                        visibilityScope = this@composable,
                    )
                }

                composable(HomeTab.DISCOVER.route) {
                    DiscoverScreen(
                        onOpenItem = ::openItem,
                        onOpenTag = { tagId ->
                            // The tag is handed over rather than carried in a route, so
                            // Library keeps its scroll position and its loaded pages
                            // instead of being popped and re-pushed to receive it.
                            shellViewModel.focusTag(tagId)
                            openTab(HomeTab.LIBRARY)
                        },
                        sharedScope = this@SharedTransitionLayout,
                        visibilityScope = this@composable,
                    )
                }

                composable(HomeTab.SAVED.route) {
                    SavedScreen(
                        onOpenItem = ::openItem,
                        onBrowseLibrary = { openTab(HomeTab.LIBRARY) },
                        sharedScope = this@SharedTransitionLayout,
                        visibilityScope = this@composable,
                    )
                }

                composable(HomeTab.HISTORY.route) {
                    HistoryScreen(
                        onOpenItem = ::openItem,
                        onBrowseLibrary = { openTab(HomeTab.LIBRARY) },
                        sharedScope = this@SharedTransitionLayout,
                        visibilityScope = this@composable,
                    )
                }

                composable(HomeTab.MANAGE.route) {
                    ManageScreen(
                        onAddSources = { navController.navigate(Routes.SOURCES) },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    )
                }

                composable(
                    route = "${Routes.PLAYER}/{${PlayerViewModel.ARG_LOCAL_ID}}",
                    arguments = listOf(
                        navArgument(PlayerViewModel.ARG_LOCAL_ID) { type = NavType.StringType },
                    ),
                ) { entry ->
                    // Read the id from the route rather than waiting for the ViewModel to
                    // load it: the shared-element key has to exist on the very first frame
                    // of the transition, and by the time a database read returns, the
                    // animation has already started without it.
                    val localId = entry.arguments
                        ?.getString(PlayerViewModel.ARG_LOCAL_ID)
                        ?.toLongOrNull()
                        ?: -1L

                    PlayerScreen(
                        onBack = { navController.popBackStack() },
                        localId = localId,
                        sharedScope = this@SharedTransitionLayout,
                        visibilityScope = this@composable,
                    )
                }

                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenSources = { navController.navigate(Routes.SOURCES) },
                        // Signing out drops the root state back to NeedsAuth, which
                        // replaces this whole graph — so there is nothing to pop.
                        onSignedOut = {},
                        onOpenDesignGallery = if (BuildConfig.DEBUG) {
                            { navController.navigate(Routes.DESIGN) }
                        } else {
                            null
                        },
                    )
                }

                composable(Routes.SOURCES) {
                    ChannelPickerScreen(
                        onDone = { navController.popBackStack() },
                        onBack = { navController.popBackStack() },
                    )
                }

                if (BuildConfig.DEBUG) {
                    composable(Routes.DESIGN) {
                        DesignGalleryScreen(onBack = { navController.popBackStack() })
                    }
                }
            }

            AnimatedVisibility(
                visible = currentTab != null,
                // Slides out of the way rather than fading: the player takes the whole
                // screen, and a bar dissolving in place over video reads as a glitch.
                enter = slideInVertically(Motion.standard()) { it } + fadeIn(Motion.fade()),
                exit = slideOutVertically(Motion.quick()) { it } + fadeOut(Motion.fade()),
            ) {
                HardPlayBottomBar(
                    current = currentTab ?: HomeTab.Start,
                    onSelect = ::openTab,
                    counts = counts,
                )
            }
        }
    }
}

/**
 * The specimen screen with a way out.
 *
 * `DesignGallery` predates navigation — it was the app's root while the design system
 * was being built — so it has no back affordance of its own. Rather than edit a
 * reviewed component sheet, it gets a bar here.
 */
@Composable
private fun DesignGalleryScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        HardPlayTopBar(title = "Design system", overline = "Debug", scrolled = true, onBack = onBack)
        Box(Modifier.weight(1f)) { DesignGallery() }
    }
}
