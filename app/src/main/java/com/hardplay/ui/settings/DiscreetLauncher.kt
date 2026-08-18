package com.hardplay.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.hardplay.data.prefs.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The discreet-disguise switch (CLAUDE.md: neutral launcher label and icon, real
 * branding only after the biometric gate, toggleable in Settings).
 *
 * Implemented with the two `activity-alias` entries in the manifest rather than by
 * swapping resources, because the launcher label and icon are read from the manifest
 * at install time and cannot be changed at runtime any other way.
 *
 * **The enable-before-disable order is not cosmetic.** If both aliases are disabled
 * at once — even for the instant between two calls — the launcher has no component to
 * show and the app's icon disappears, with no way back short of a reinstall. So the
 * incoming alias is switched on first, and only then is the outgoing one switched off.
 */
@Singleton
class DiscreetLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun apply(discreet: Boolean) {
        val packageManager = context.packageManager
        val incoming = if (discreet) DISCREET_ALIAS else BRANDED_ALIAS
        val outgoing = if (discreet) BRANDED_ALIAS else DISCREET_ALIAS

        setEnabled(packageManager, incoming, true)
        setEnabled(packageManager, outgoing, false)
    }

    /** True when the neutral alias is the one the launcher is showing. */
    fun isDiscreet(): Boolean {
        val state = context.packageManager.getComponentEnabledSetting(component(DISCREET_ALIAS))
        return state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }

    private fun setEnabled(packageManager: PackageManager, alias: String, enabled: Boolean) {
        runCatching {
            packageManager.setComponentEnabledSetting(
                component(alias),
                if (enabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                // DONT_KILL_APP: without it the platform stops the process to apply
                // the change, which from the user's side is the app closing itself
                // the moment they touch the switch.
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    private fun component(alias: String) = ComponentName(context.packageName, alias)

    private companion object {
        /**
         * Fully qualified against the *namespace*, not the applicationId. The
         * manifest declares these as `.Launcher` relative to `com.hardplay`, while
         * the applicationId is `com.northline.archive` — conflating the two yields a
         * ComponentName that silently matches nothing.
         */
        const val DISCREET_ALIAS = "com.hardplay.Launcher"
        const val BRANDED_ALIAS = "com.hardplay.LauncherBranded"
    }
}

/** Formats a cache cap for the settings row. */
internal fun cacheCapLabel(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "${bytes / (1024L * 1024 * 1024)} GB"
    else -> "${bytes / (1024L * 1024)} MB"
}

internal val CACHE_CHOICES = SettingsStore.CACHE_CAP_CHOICES
