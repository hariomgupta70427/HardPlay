package com.hardplay.playback

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.hardplay.telegram.TelegramGateway
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handing one file to another app.
 *
 * Wanted because HardPlay's player is deliberately narrow — it streams from Telegram
 * and draws its own chrome — while VLC and mpv can do things it never will: obscure
 * codecs, external subtitle files, audio filters, casting.
 *
 * The honest constraint is that HardPlay has no file to hand over until TDLib has
 * finished downloading one. A `content://` URI backed by a *sparse* file would open
 * in the other player and then fail somewhere in the middle, which is a far worse
 * experience than the action being unavailable — so [state] is a real query and the
 * UI is expected to show what it returns rather than always offering the button.
 */
@Singleton
class ExternalOpen @Inject constructor(
    private val gateway: TelegramGateway,
) {

    /** Whether this file can be handed to another app right now, and why not. */
    sealed interface State {
        /** Fully downloaded and readable. [path] is a real, complete file. */
        data class Ready(val path: String) : State

        /** TDLib has some of it. Streaming works; handing it over does not. */
        data class Partial(val fraction: Float) : State

        data object Absent : State
    }

    suspend fun state(fileId: Int): State {
        val file = gateway.observeFile(fileId).first()
        val path = file.localPath
        if (file.isDownloadingCompleted && path != null && File(path).length() > 0L) {
            return State.Ready(path)
        }
        if (file.expectedSize > 0L && file.readableUntil > 0L) {
            return State.Partial(
                (file.readableUntil.toFloat() / file.expectedSize).coerceIn(0f, 1f),
            )
        }
        return State.Absent
    }

    /**
     * Fire an `ACTION_VIEW` chooser for a downloaded file.
     *
     * A chooser rather than a direct launch: there is no sensible default among the
     * video players someone might have installed, and silently picking one is how an
     * app ends up "broken" for the person whose preferred player it didn't choose.
     *
     * @return false when nothing on the device claims the type, so the caller can say
     *   so instead of appearing to do nothing.
     */
    fun launch(context: Context, path: String, mimeType: String?, title: String): Boolean {
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.media", File(path))
        }.getOrNull() ?: return false

        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType ?: "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // The receiving app opens as its own task; without this it would appear
            // inside HardPlay's back stack and returning would land here again.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return runCatching {
            context.startActivity(Intent.createChooser(view, title).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            true
        }.getOrElse { failure ->
            if (failure is ActivityNotFoundException) false else throw failure
        }
    }
}
