package com.hardplay.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hardplay.data.prefs.SettingsStore
import com.hardplay.telegram.GatewayError
import com.hardplay.ui.image.FrameHarvester
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Background sync (PRD §8).
 *
 * Keeps the library current without the app being opened. Deliberately modest:
 * every wake costs battery and a little of the account's rate-limit headroom, and
 * a personal library that is six hours stale is not a problem worth spending
 * either on.
 */
@HiltWorker
class LibrarySyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val indexer: LibraryIndexer,
    private val settings: SettingsStore,
    private val frameHarvester: FrameHarvester,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Checked here rather than only at scheduling time: the setting can be
        // switched off while a job is already queued.
        if (!settings.settings.first().backgroundSync) return Result.success()

        val summary = indexer.sync(SyncMode.FULL)

        // Artwork after indexing, and only here rather than on launch, because this
        // job already carries the constraint the sweep needs: decoding a frame costs
        // a couple of megabytes per video, and UNMETERED is the difference between
        // that being free and it being someone's data allowance. The harvester
        // enforces its own caps and returns immediately when the feature is off.
        runCatching { frameHarvester.sweep() }

        val failure = summary.failure ?: return finish(summary)

        return when (failure.error) {
            // Retry with WorkManager's backoff. Its exponential curve is longer
            // than most flood-waits, so honouring the wait comes for free.
            GatewayError.FLOOD_WAIT,
            GatewayError.NETWORK,
            -> Result.retry()

            // Nothing a retry can fix: the user has to sign in, or add a channel.
            GatewayError.NOT_AUTHENTICATED,
            GatewayError.UNAVAILABLE,
            -> Result.success()

            else -> Result.retry()
        }
    }

    /**
     * A run that used its whole page budget still has history left, so it asks for
     * one more pass immediately instead of waiting for the next period. That is
     * what lets a large first-time backfill finish over an evening rather than
     * over days.
     */
    private fun finish(summary: SyncSummary): Result {
        if (summary.hasMoreWork) {
            WorkManager.getInstance(applicationContext).enqueue(
                OneTimeWorkRequestBuilder<LibrarySyncWorker>()
                    .setConstraints(constraints())
                    .setInitialDelay(CONTINUATION_DELAY_MINUTES, TimeUnit.MINUTES)
                    .build(),
            )
        }
        return Result.success()
    }

    companion object {
        private const val PERIODIC_NAME = "hardplay-library-sync"
        private const val PERIOD_HOURS = 6L
        private const val CONTINUATION_DELAY_MINUTES = 2L

        private fun constraints() = Constraints.Builder()
            // Unmetered only. Indexing is metadata, but it is also the same code
            // path a user might trigger on a train, and nobody thanks an app for
            // spending their data allowance in the background.
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                // KEEP: re-scheduling on every launch with UPDATE would reset the
                // period each time and, for an app opened often, mean the job
                // never actually runs.
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<LibrarySyncWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                    .setConstraints(constraints())
                    .build(),
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
        }
    }
}
