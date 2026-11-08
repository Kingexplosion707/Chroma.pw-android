package com.metrolist.music.chroma.spotify

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.FlagSet
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import com.metrolist.music.extensions.toMediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet
import androidx.media3.common.MediaMetadata as Media3Metadata

/** A fixed list of media items as a media3 timeline, for Metrolist's queue views. */
private class ListTimeline(private val items: List<MediaItem>, private val durationsUs: List<Long>) : Timeline() {
    override fun getWindowCount() = items.size
    override fun getPeriodCount() = items.size

    override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window =
        window.set(
            "chroma$windowIndex", items[windowIndex], null,
            C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET,
            true, false, null,
            0L, durationsUs[windowIndex], windowIndex, windowIndex, 0L,
        )

    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period =
        period.set(periodIndex, periodIndex, periodIndex, durationsUs[periodIndex], 0L)

    override fun getIndexOfPeriod(uid: Any): Int = (uid as? Int)?.takeIf { it in items.indices } ?: C.INDEX_UNSET
    override fun getUidOfPeriod(periodIndex: Int): Any = periodIndex
}

/**
 * Metrolist's whole UI talks to an [ExoPlayer]. This one reports what the Spotify app is playing
 * and turns every command into a Spotify command, so the mini player, the player page, the queue,
 * the sleep timer and lyrics all work for Spotify. Anything Spotify has no equivalent for falls
 * through to Metrolist's idle player.
 */
class SpotifyPlayer private constructor(
    private val base: ExoPlayer,
    private val appContext: Context,
) : ExoPlayer by base {
    companion object {
        private var instance: SpotifyPlayer? = null

        fun wrap(base: ExoPlayer, ctx: Context): SpotifyPlayer {
            instance?.let { if (it.base === base) return it }
            val old = instance
            return SpotifyPlayer(base, ctx.applicationContext).also { p ->
                old?.let { o -> p.listeners.addAll(o.listeners); o.scope.cancel() }
                instance = p
            }
        }
    }

    private val listeners = CopyOnWriteArraySet<Player.Listener>()
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var now = SpotifyNow()
    private var snapNow: PlayerSnap? = null
    private var items: List<MediaItem> = emptyList()
    private var timeline: Timeline = Timeline.EMPTY
    private var currentKey = ""
    private var metaKey = ""
    private var queueKey = ""
    private var everHadItem = false

    init {
        SpotifyBridge.init(appContext)
        SpotifyRemote.start(appContext)
        runCatching { if (base.isPlaying) base.pause() }
        scope.launch {
            combine(SpotifyRemote.state, SpotifyBridge.snap, SpotifyBridge.queue) { n, s, q -> Triple(n, s, q) }
                .collect { (n, s, q) -> update(n, s, q) }
        }
    }

    private val hasItem get() = items.isNotEmpty()

    private fun update(n: SpotifyNow, s: PlayerSnap?, q: List<SpTrack>) {
        val wasPlaying = isPlaying()
        val oldShuffle = getShuffleModeEnabled()
        val oldRepeat = getRepeatMode()
        val oldState = getPlaybackState()
        now = n
        snapNow = s
        val events = FlagSet.Builder()

        val active = n.connected && n.title.isNotEmpty() || (!n.connected && s?.track != null)
        if (!active) {
            if (items.isNotEmpty()) {
                items = emptyList(); timeline = Timeline.EMPTY; currentKey = ""; metaKey = ""; queueKey = ""
                emit { it.onTimelineChanged(timeline, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) }
                emit { it.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) }
                events.add(Player.EVENT_TIMELINE_CHANGED).add(Player.EVENT_MEDIA_ITEM_TRANSITION)
            }
        } else {
            val source = if (n.connected) n else SpotifyNow(
                connected = false, title = s!!.track!!.name, artist = s.track.artistLine,
                mediaId = s.track.uri, durationMs = s.track.durationMs, positionMs = s.progressMs,
                positionTs = android.os.SystemClock.elapsedRealtime() - (System.currentTimeMillis() - s.at), playing = s.playing,
            )
            val meta = SpotifyBridge.metadataFor(source, s?.track)
            val key = meta.id + "|" + meta.title
            val mKey = key + "|" + meta.thumbnailUrl + "|" + meta.artists.joinToString { it.id.orEmpty() }
            val qKey = q.joinToString(",") { it.id }
            if (key != currentKey || mKey != metaKey || qKey != queueKey) {
                val trackChanged = key != currentKey
                val current = meta.toMediaItem()
                val upcoming = q.take(40).map { SpotifyBridge.metadataFor(SpotifyNow(connected = true, title = it.name, artist = it.artistLine, mediaId = it.uri, durationMs = it.durationMs), it).toMediaItem() }
                items = listOf(current) + upcoming
                val durations = listOf(meta.duration * 1_000_000L) + q.take(40).map { it.durationMs * 1000L }
                timeline = ListTimeline(items, durations.map { if (it > 0) it else C.TIME_UNSET })
                emit { it.onTimelineChanged(timeline, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) }
                events.add(Player.EVENT_TIMELINE_CHANGED)
                if (trackChanged || mKey != metaKey) {
                    val reason = if (!everHadItem) Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED else Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                    everHadItem = true
                    emit { it.onMediaItemTransition(current, reason) }
                    emit { it.onMediaMetadataChanged(current.mediaMetadata) }
                    events.add(Player.EVENT_MEDIA_ITEM_TRANSITION).add(Player.EVENT_MEDIA_METADATA_CHANGED)
                }
                currentKey = key; metaKey = mKey; queueKey = qKey
            }
        }

        if (getPlaybackState() != oldState) {
            val st = getPlaybackState()
            emit { it.onPlaybackStateChanged(st) }
            events.add(Player.EVENT_PLAYBACK_STATE_CHANGED)
        }
        if (isPlaying() != wasPlaying) {
            val p = isPlaying()
            emit { it.onPlayWhenReadyChanged(p, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
            emit { it.onIsPlayingChanged(p) }
            events.add(Player.EVENT_PLAY_WHEN_READY_CHANGED).add(Player.EVENT_IS_PLAYING_CHANGED)
        }
        if (getShuffleModeEnabled() != oldShuffle) {
            val sh = getShuffleModeEnabled()
            emit { it.onShuffleModeEnabledChanged(sh) }
            events.add(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)
        }
        if (getRepeatMode() != oldRepeat) {
            val r = getRepeatMode()
            emit { it.onRepeatModeChanged(r) }
            events.add(Player.EVENT_REPEAT_MODE_CHANGED)
        }
        val flags = events.build()
        if (flags.size() > 0) emit { it.onEvents(this, Player.Events(flags)) }
    }

    private inline fun emit(crossinline call: (Player.Listener) -> Unit) {
        val run = Runnable { listeners.forEach { l -> runCatching { call(l) } } }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
    }

    // ---------- listeners ----------
    override fun addListener(listener: Player.Listener) { listeners.add(listener) }
    override fun removeListener(listener: Player.Listener) { listeners.remove(listener) }

    // ---------- state ----------
    override fun getPlaybackState(): Int = if (hasItem) Player.STATE_READY else Player.STATE_IDLE
    override fun getPlayWhenReady(): Boolean = hasItem && playingNow()
    override fun isPlaying(): Boolean = hasItem && playingNow()
    override fun isLoading(): Boolean = false
    override fun getPlayerError(): ExoPlaybackException? = null

    private fun playingNow() = if (now.connected) now.playing else snapNow?.playing == true

    override fun getCurrentPosition(): Long = when {
        now.connected -> now.livePosition()
        snapNow != null -> {
            val s = snapNow!!
            val p = s.progressMs + if (s.playing) System.currentTimeMillis() - s.at else 0L
            p.coerceIn(0L, (s.track?.durationMs ?: p).coerceAtLeast(0L))
        }
        else -> 0L
    }

    override fun getContentPosition(): Long = getCurrentPosition()
    override fun getDuration(): Long {
        val d = if (now.connected) now.durationMs else snapNow?.track?.durationMs ?: 0L
        return if (d > 0) d else C.TIME_UNSET
    }
    override fun getContentDuration(): Long = getDuration()
    override fun getBufferedPosition(): Long = getDuration().takeIf { it != C.TIME_UNSET } ?: getCurrentPosition()
    override fun getContentBufferedPosition(): Long = getBufferedPosition()
    override fun getTotalBufferedDuration(): Long = (getBufferedPosition() - getCurrentPosition()).coerceAtLeast(0L)
    override fun getBufferedPercentage(): Int = if (hasItem) 100 else 0

    override fun getCurrentTimeline(): Timeline = timeline
    override fun getCurrentMediaItem(): MediaItem? = items.firstOrNull()
    override fun getMediaItemCount(): Int = items.size
    override fun getMediaItemAt(index: Int): MediaItem = items[index]
    override fun getCurrentMediaItemIndex(): Int = 0
    override fun getCurrentPeriodIndex(): Int = 0
    override fun getNextMediaItemIndex(): Int = if (items.size > 1) 1 else C.INDEX_UNSET
    override fun getPreviousMediaItemIndex(): Int = C.INDEX_UNSET
    override fun hasNextMediaItem(): Boolean = hasItem
    override fun hasPreviousMediaItem(): Boolean = hasItem
    override fun getMediaMetadata(): Media3Metadata = items.firstOrNull()?.mediaMetadata ?: Media3Metadata.EMPTY
    override fun isCurrentMediaItemSeekable(): Boolean = hasItem
    override fun isCurrentMediaItemDynamic(): Boolean = false
    override fun isCurrentMediaItemLive(): Boolean = false

    override fun getShuffleModeEnabled(): Boolean = snapNow?.shuffle == true
    override fun getRepeatMode(): Int = when (snapNow?.repeat) {
        "context" -> Player.REPEAT_MODE_ALL
        "track" -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
    }

    override fun isCommandAvailable(command: Int): Boolean = true
    override fun getAvailableCommands(): Player.Commands = Player.Commands.Builder().addAllCommands().build()

    // ---------- commands ----------
    override fun play() = SpotifyBridge.play()
    override fun pause() = SpotifyBridge.pause()
    override fun setPlayWhenReady(playWhenReady: Boolean) { if (playWhenReady) SpotifyBridge.play() else SpotifyBridge.pause() }
    override fun stop() = SpotifyBridge.pause()
    override fun clearMediaItems() {}
    override fun prepare() {}

    override fun seekTo(positionMs: Long) = SpotifyBridge.seekTo(positionMs)
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (mediaItemIndex <= 0) SpotifyBridge.seekTo(positionMs) else SpotifyBridge.skipForward(mediaItemIndex)
    }
    override fun seekToDefaultPosition() = SpotifyBridge.seekTo(0)
    override fun seekToDefaultPosition(mediaItemIndex: Int) = seekTo(mediaItemIndex, 0L)
    override fun seekBack() = SpotifyBridge.seekTo((getCurrentPosition() - 10_000).coerceAtLeast(0))
    override fun seekForward() = SpotifyBridge.seekTo(getCurrentPosition() + 10_000)
    override fun seekToNext() = SpotifyBridge.next()
    override fun seekToNextMediaItem() = SpotifyBridge.next()
    override fun seekToPrevious() = SpotifyBridge.previous()
    override fun seekToPreviousMediaItem() = SpotifyBridge.previous()

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) = SpotifyBridge.setShuffle(shuffleModeEnabled)
    override fun setRepeatMode(repeatMode: Int) = SpotifyBridge.setRepeat(
        when (repeatMode) { Player.REPEAT_MODE_ALL -> "context"; Player.REPEAT_MODE_ONE -> "track"; else -> "off" },
    )
}
