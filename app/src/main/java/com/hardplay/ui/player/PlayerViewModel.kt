package com.hardplay.ui.player

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.hardplay.data.db.dao.FavouriteDao
import com.hardplay.data.db.dao.MediaDao
import com.hardplay.data.db.entity.MediaEntity
import com.hardplay.data.db.entity.MediaType
import com.hardplay.data.db.entity.TagEntity
import com.hardplay.data.prefs.SettingsStore
import com.hardplay.data.repo.MediaFileRepair
import com.hardplay.data.repo.MediaFileRole
import com.hardplay.data.repo.PlaybackRepository
import com.hardplay.data.repo.TagRepository
import com.hardplay.di.AppScope
import com.hardplay.playback.ExternalOpen
import com.hardplay.playback.TelegramMediaUri
import com.hardplay.playback.TelegramPlayerFactory
import com.hardplay.telegram.TelegramGateway
import com.hardplay.ui.image.FrameHarvester
import com.hardplay.ui.image.PosterSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The player screen's state, and the owner of the [ExoPlayer] instance.
 *
 * A ViewModel holds the player rather than the composable, so a rotation or a brief
 * backgrounding doesn't tear down the codec and re-buffer from Telegram — which on
 * this transport costs seconds, not milliseconds.
 *
 * Three details here are easy to get wrong and expensive when you do:
 *
 *  * **The resume write is on an application-scoped coroutine, not [viewModelScope].**
 *    The last save happens as the screen is going away, and `viewModelScope` is
 *    already cancelled by then, so a `viewModelScope.launch` would silently drop
 *    exactly the position that matters most.
 *  * **Position is polled, not observed.** `Player` has no position callback; the
 *    only options are a ticker or reading it during composition. A ticker at 4 Hz
 *    keeps the scrubber smooth and keeps the read off the frame path.
 *  * **A photo has no player at all.** It gets a download-progress subscription
 *    instead, because its bytes arrive through TDLib rather than through Media3 and
 *    nothing else would ever report on them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val mediaDao: MediaDao,
    private val favourites: FavouriteDao,
    private val tags: TagRepository,
    private val playback: PlaybackRepository,
    private val settings: SettingsStore,
    private val playerFactory: TelegramPlayerFactory,
    private val gateway: TelegramGateway,
    private val repair: MediaFileRepair,
    private val externalOpen: ExternalOpen,
    private val frameHarvester: FrameHarvester,
    @AppScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val localId: Long = savedState.get<String>(ARG_LOCAL_ID)?.toLongOrNull() ?: -1L

    val isDemo: Boolean = gateway.isDemo

    /** Created eagerly: the surface needs a player the moment the screen composes. */
    val player: ExoPlayer = playerFactory.create()

    private val _ui = MutableStateFlow(PlayerUiState())
    val ui: StateFlow<PlayerUiState> = _ui.asStateFlow()

    private val _tracks = MutableStateFlow(PlayerTrackState())
    val tracks: StateFlow<PlayerTrackState> = _tracks.asStateFlow()

    private val _external = MutableStateFlow<ExternalOpen.State>(ExternalOpen.State.Absent)
    val external: StateFlow<ExternalOpen.State> = _external.asStateFlow()

    val itemTags: StateFlow<List<TagEntity>> = tags.observeForItem(localId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val isFavourite: StateFlow<Boolean> = favourites.observeIsFavourite(localId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Watched, as the library means it — not "playing right now". */
    val isViewed: StateFlow<Boolean> = playback.observe(localId)
        .map { it?.completed == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Saved state is flipped through the DAO's own transaction rather than read,
     * negated and written here — otherwise a double tap can read the same value
     * twice and write the same state twice.
     */
    fun toggleFavourite() {
        viewModelScope.launch { favourites.toggle(localId, System.currentTimeMillis()) }
    }

    /** Marked from the chrome. App-scoped, so navigating away mid-write keeps it. */
    fun setViewed(viewed: Boolean) {
        appScope.launch {
            if (viewed) playback.markViewed(localId) else playback.markUnviewed(localId)
        }
    }

    private var ticker: Job? = null
    private var lastSavedPositionMs = 0L

    /** One capture per item. See [captureFrameOnce]. */
    private var frameCaptured = false

    /**
     * Keep the frame that is already on screen as this item's artwork.
     *
     * The cheapest quality win available: Telegram gives a video exactly one
     * thumbnail, typically no more than 320px, and no larger rung to ask for — which
     * is why video looks soft in a full-width cell. A decoded frame fixes that
     * outright, and by the time the first frame has rendered the player is holding one
     * for free. Nothing extra is downloaded.
     *
     * Best-effort by design. `TextureView.getBitmap` allocates a full-size bitmap and
     * returns null before the surface has content, so this runs once per item and is
     * allowed to fail silently — artwork is never worth a playback stutter, let alone
     * a crash.
     */
    fun captureFrameOnce(bitmap: Bitmap?) {
        if (bitmap == null) return
        if (frameCaptured || _ui.value.isPhoto || localId <= 0L) {
            runCatching { bitmap.recycle() }
            return
        }
        frameCaptured = true
        // App-scoped: the write outlives the screen, and viewModelScope is already
        // cancelled by the time a back press has finished tearing the player down.
        appScope.launch {
            runCatching { frameHarvester.capture(localId, bitmap) }
            runCatching { bitmap.recycle() }
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _ui.update { it.copy(playing = isPlaying) }
            if (isPlaying) startTicker() else stopTicker()
        }

        override fun onPlaybackStateChanged(state: Int) {
            _ui.update {
                it.copy(
                    buffering = state == Player.STATE_BUFFERING,
                    ended = state == Player.STATE_ENDED,
                    durationMs = player.duration.coerceAtLeast(0L),
                )
            }
            if (state == Player.STATE_ENDED) {
                appScope.launch { playback.markCompleted(localId) }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            _ui.update { it.copy(error = explain(error), buffering = false) }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            _ui.update {
                it.copy(
                    videoAspect = if (videoSize.height > 0) {
                        videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    } else {
                        it.videoAspect
                    },
                )
            }
        }

        /**
         * Track lists only exist once the container has been read, which is well after
         * `prepare()`. Reading them eagerly at load time yields an empty list and a
         * menu that is permanently empty for files that do have alternates.
         */
        override fun onTracksChanged(tracks: Tracks) {
            _tracks.value = tracks.readPlayerTracks()
        }

        /**
         * The handover point for the grid → player transition.
         *
         * Until this fires the video surface is blank, so the screen shows the same
         * poster the grid did — that poster is the shared element. Cross-fading to the
         * surface any earlier means the transition lands on a black rectangle, which
         * looks like a failure rather than a flourish.
         */
        override fun onRenderedFirstFrame() {
            _ui.update { it.copy(firstFrameRendered = true) }
        }
    }

    init {
        player.addListener(listener)
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val entity = mediaDao.byId(localId)
        if (entity == null) {
            _ui.update { it.copy(error = "That item is no longer in the library.") }
            return
        }

        val appSettings = settings.settings.first()
        val resumeFrom = playback.position(localId)
            ?.takeIf { !it.completed && it.positionMs > PlaybackRepository.RESUME_FLOOR_MS }
            ?.positionMs
            ?: 0L
        val isPhoto = MediaType.fromStored(entity.type) == MediaType.PHOTO

        _ui.update {
            it.copy(
                title = entity.title,
                caption = entity.caption,
                sizeBytes = entity.fileSizeBytes,
                width = entity.width,
                height = entity.height,
                isPhoto = isPhoto,
                poster = PosterSource.of(entity).takeIf { poster -> !poster.isEmpty },
                remoteFileId = entity.remoteFileId,
                fileId = entity.fileId,
                localId = entity.localId,
                skipSeconds = appSettings.skipSeconds,
                speed = appSettings.playbackSpeed,
                resumedFromMs = resumeFrom,
                // A still's own dimensions are the honest aspect to fit it at, and
                // they are known before a single byte has been fetched.
                videoAspect = photoAspect(entity) ?: it.videoAspect,
            )
        }

        refreshExternalState()

        if (isPhoto) {
            observePhotoDownload()
            // Photos have no player. Counting the open still matters — it is what
            // History and "most watched" are about — and nothing else will do it,
            // since there is no playback to end.
            appScope.launch { playback.recordPlayStarted(localId) }
            return
        }

        player.setMediaItem(buildMediaItem(entity))
        player.setPlaybackParameters(PlaybackParameters(appSettings.playbackSpeed))
        if (resumeFrom > 0L) player.seekTo(resumeFrom)
        player.prepare()
        player.playWhenReady = true

        // Counted here rather than on the first frame: opening an item is the intent
        // that "most watched" is about, and a file that fails to buffer was still
        // something the user chose.
        appScope.launch { playback.recordPlayStarted(localId) }
    }

    private fun photoAspect(entity: MediaEntity): Float? {
        val width = entity.width ?: return null
        val height = entity.height ?: return null
        if (width <= 0 || height <= 0) return null
        return width.toFloat() / height
    }

    /**
     * Download progress for a photo.
     *
     * The full-resolution file can be several megabytes, and until this existed the
     * viewer had nothing to say during the wait — so opening a large still looked
     * like the app had simply decided to show the blurry version. TDLib reports a
     * contiguous prefix rather than a percentage, so the fraction is derived from it.
     *
     * Driven off the *row* rather than off the file id read once at load. The artwork
     * path repairs a refused id and writes the fresh one back, and when it does, this
     * follows it. Watching the id captured at load instead left the readout pinned at
     * zero for exactly the items that needed repairing — the ones that looked broken.
     */
    private fun observePhotoDownload() {
        viewModelScope.launch {
            mediaDao.observeRow(localId)
                .map { it?.fileId ?: NO_FILE }
                .distinctUntilChanged()
                .flatMapLatest { id ->
                    if (id == NO_FILE) emptyFlow() else gateway.observeFile(id)
                }
                .collect { state ->
                    val fraction = when {
                        state.isDownloadingCompleted -> 1f
                        state.expectedSize > 0L ->
                            (state.readableUntil.toFloat() / state.expectedSize).coerceIn(0f, 1f)
                        else -> 0f
                    }
                    _ui.update { it.copy(photoProgress = fraction) }
                }
        }
    }

    private fun buildMediaItem(entity: MediaEntity): MediaItem = MediaItem.Builder()
        .setUri(
            TelegramMediaUri.build(
                fileId = entity.fileId,
                sizeBytes = entity.fileSizeBytes,
                remoteFileId = entity.remoteFileId,
                // Travels with the item so the streaming source can repair a refused
                // file id on its own thread without reaching back into Room.
                localId = entity.localId,
            ),
        )
        .setMediaId(entity.localId.toString())
        .build()

    // ------------------------------------------------------------- transport

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun skipForward() {
        val step = _ui.value.skipSeconds * 1_000L
        player.seekTo((player.currentPosition + step).coerceAtMost(player.duration.coerceAtLeast(0L)))
        refreshPosition()
    }

    fun skipBackward() {
        val step = _ui.value.skipSeconds * 1_000L
        player.seekTo((player.currentPosition - step).coerceAtLeast(0L))
        refreshPosition()
    }

    /** Called continuously while the scrubber is dragged. */
    fun scrubTo(positionMs: Long) {
        _ui.update { it.copy(scrubbingToMs = positionMs) }
    }

    /** Commits a drag. Seeking on every drag frame would thrash TDLib's range requests. */
    fun commitScrub() {
        val target = _ui.value.scrubbingToMs ?: return
        player.seekTo(target)
        _ui.update { it.copy(scrubbingToMs = null, positionMs = target) }
        persist(target, force = true)
    }

    fun setSpeed(speed: Float) {
        player.setPlaybackParameters(PlaybackParameters(speed))
        _ui.update { it.copy(speed = speed) }
        viewModelScope.launch { settings.setPlaybackSpeed(speed) }
    }

    fun retry() {
        _ui.update { it.copy(error = null, buffering = true) }
        viewModelScope.launch {
            // Drop any remembered failure first. A manual retry is the user asking
            // us to go and check again, and honouring it while a cached "this item
            // cannot be repaired" still stands would make the button do nothing.
            repair.forget(localId)

            val entity = mediaDao.byId(localId)
            if (entity != null && !_ui.value.isPhoto) {
                // Rebuilt from the row rather than re-preparing the existing item, so
                // a repair that some other surface has already paid for is picked up.
                // Position is restored explicitly because setMediaItem resets it.
                val resumeAt = player.currentPosition.coerceAtLeast(0L)
                player.setMediaItem(buildMediaItem(entity))
                if (resumeAt > 0L) player.seekTo(resumeAt)
                _ui.update { it.copy(fileId = entity.fileId) }
            }
            player.prepare()
            player.play()
        }
    }

    // ----------------------------------------------------------------- tracks

    /**
     * Pick one audio or subtitle track.
     *
     * `setOverrideForType` replaces any existing override of the same type, so this is
     * a selection rather than an accumulation — picking a second audio track does not
     * leave both enabled.
     */
    fun selectTrack(track: PlayerTrack) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(track.trackType, false)
            .setOverrideForType(TrackSelectionOverride(track.group, track.indexInGroup))
            .build()
    }

    /**
     * Subtitles off.
     *
     * Both calls are needed: clearing the override alone lets the default selector
     * pick a track straight back again, and disabling the type alone leaves a stale
     * override to be re-applied the moment subtitles are turned on.
     */
    fun disableSubtitles() {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    // ------------------------------------------------------------- other apps

    /**
     * Re-ask whether the file is complete.
     *
     * Cheap, and worth doing every time the sheet opens rather than once at load: a
     * file that was half-downloaded when the screen opened is frequently finished by
     * the time someone goes looking for this action.
     */
    fun refreshExternalState() {
        val fileId = _ui.value.fileId
        if (fileId == NO_FILE) return
        viewModelScope.launch {
            val state = externalOpen.state(fileId)
            // "Absent" from a stored id is ambiguous: TDLib either has none of the
            // file, or does not recognise the handle at all. Repairing before
            // believing it is what stops the action reporting nothing-downloaded for
            // an item that is in fact sitting complete on disk under a fresh id.
            _external.value = if (state is ExternalOpen.State.Absent) {
                val healed = repair.repair(localId, MediaFileRole.ORIGINAL)
                if (healed != null && healed != fileId) {
                    _ui.update { it.copy(fileId = healed) }
                    externalOpen.state(healed)
                } else {
                    state
                }
            } else {
                state
            }
        }
    }

    /**
     * @param activityContext must be the activity. Launching a chooser from the
     *   application context puts it behind the current window on several OEM builds.
     * @return false when nothing on the device handles the type, so the caller can
     *   say so rather than appearing to do nothing.
     */
    fun openExternally(activityContext: Context): Boolean {
        val ready = _external.value as? ExternalOpen.State.Ready ?: return false
        return externalOpen.launch(
            context = activityContext,
            path = ready.path,
            mimeType = if (_ui.value.isPhoto) "image/*" else "video/*",
            title = _ui.value.title,
        )
    }

    // --------------------------------------------------------------- tagging

    fun addTag(name: String) {
        viewModelScope.launch { tags.addToItem(localId, name) }
    }

    fun removeTag(tagId: Long) {
        viewModelScope.launch { tags.removeFromItem(localId, tagId) }
    }

    suspend fun suggestTags(prefix: String): List<String> =
        tags.suggest(prefix).map { it.name }

    // ---------------------------------------------------------------- ticker

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = viewModelScope.launch {
            while (true) {
                refreshPosition()
                delay(TICK_MS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
        // Pausing is a natural save point, and the one the user expects to survive.
        persist(player.currentPosition, force = true)
    }

    private fun refreshPosition() {
        val position = player.currentPosition.coerceAtLeast(0L)
        val duration = player.duration.coerceAtLeast(0L)
        _ui.update {
            it.copy(
                positionMs = position,
                durationMs = duration,
                bufferedMs = player.bufferedPosition.coerceAtLeast(0L),
            )
        }
        persist(position, force = false)
    }

    /**
     * @param force bypasses the distance throttle. Used at pause, seek and teardown
     *   — the moments where losing the position would actually be noticed.
     */
    private fun persist(positionMs: Long, force: Boolean) {
        val duration = player.duration
        if (duration <= 0L) return
        if (!force && !PlaybackRepository.shouldPersist(lastSavedPositionMs, positionMs)) return
        lastSavedPositionMs = positionMs
        appScope.launch { playback.save(localId, positionMs, duration) }
    }

    private fun explain(error: PlaybackException): String = when {
        isDemo -> "Demo mode has artwork and metadata but no video bytes. " +
            "Build TDLib and sign in to stream."
        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "Telegram stopped delivering data. Check the connection and retry."
        error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
            "This device can't decode that file."
        // Everything the streaming source gives up on lands here, and ExoPlayer's own
        // message for it is the bare string "Source error" — which is what the user
        // saw, and which says nothing about what to do. The source has already
        // retried and tried to re-resolve the file by this point, so the honest
        // reading is that Telegram would not serve it right now.
        error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            "Telegram wouldn't serve this file just now. Retry usually fixes it; if it " +
                "keeps failing, the post may have been deleted from the channel."
        else -> error.localizedMessage ?: "Playback failed."
    }

    override fun onCleared() {
        // Order matters: capture the position while the player still has one, then
        // release. Reading currentPosition after release returns 0 and would
        // overwrite a good resume point with the start of the file.
        val finalPosition = player.currentPosition
        persist(finalPosition, force = true)

        ticker?.cancel()
        player.removeListener(listener)
        player.release()
        super.onCleared()
    }

    companion object {
        const val ARG_LOCAL_ID = "localId"
        private const val TICK_MS = 250L
        private const val NO_FILE = 0
    }
}

data class PlayerUiState(
    val localId: Long = -1,
    val title: String = "",
    val caption: String = "",
    val sizeBytes: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    val isPhoto: Boolean = false,
    /** Best available artwork, for the transition hand-off and photo items. */
    val poster: PosterSource? = null,
    val remoteFileId: String = "",
    /** TDLib's session id for the media itself, for external opening and progress. */
    val fileId: Int = 0,

    val playing: Boolean = false,
    val buffering: Boolean = true,
    /** False until the surface has a real frame on it. Gates the poster hand-off. */
    val firstFrameRendered: Boolean = false,
    val ended: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    /** Non-null only while a drag is in progress. */
    val scrubbingToMs: Long? = null,
    val speed: Float = 1f,
    val skipSeconds: Int = 10,
    /** Where playback resumed from, so the UI can say so once. */
    val resumedFromMs: Long = 0,
    /** Fit ratio for whichever stage is showing — a video's frame or a photo's own. */
    val videoAspect: Float = 16f / 9f,
    /** 0f..1f of the full-resolution still. Meaningless for video. */
    val photoProgress: Float = 0f,
    val error: String? = null,
) {
    /** What the scrubber should show: the drag target while dragging, else the clock. */
    val displayPositionMs: Long get() = scrubbingToMs ?: positionMs

    val progress: Float
        get() = if (durationMs <= 0L) 0f else (displayPositionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    val bufferedProgress: Float
        get() = if (durationMs <= 0L) 0f else (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /** `1920×1080`, when the index knows it. Stated as a fact; there is no ladder. */
    val renditionLabel: String?
        get() {
            val w = width ?: return null
            val h = height ?: return null
            if (w <= 0 || h <= 0) return null
            return "$w×$h"
        }
}
