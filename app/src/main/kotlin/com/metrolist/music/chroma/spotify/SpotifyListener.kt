package com.metrolist.music.chroma.spotify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import com.metrolist.music.MainActivity
import com.metrolist.music.R
import com.metrolist.music.chroma.ChromaPrefs
import com.metrolist.music.constants.HistoryDuration
import com.metrolist.music.constants.PauseListenHistoryKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Event
import com.metrolist.music.db.entities.LyricsEntity
import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.lyrics.LyricsHelper
import com.metrolist.music.lyrics.LyricsUtils
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import javax.inject.Inject

/**
 * Bound by Android once Chroma has notification access. Hands Chroma Spotify's media session, and
 * keeps running in the background to count plays into Metrolist's history (Chroma's on-device
 * listening stats) and to drive the lyrics Live Activity.
 */
@AndroidEntryPoint
class SpotifyListener : NotificationListenerService() {
    @Inject lateinit var database: MusicDatabase
    @Inject lateinit var lyricsHelper: LyricsHelper

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null
    private var lyricsJob: Job? = null

    private var currentId: String? = null
    private var currentMeta: MediaMetadata? = null
    private var playedMs = 0L
    private var lastTick = 0L
    private var lines: List<LyricsEntry> = emptyList()
    private var lyricsFor: String? = null
    private var lastLine = -2
    private var lastPlaying: Boolean? = null

    override fun onListenerConnected() {
        ChromaPrefs.init(this)
        SpotifyBridge.init(this)
        SpotifyRemote.start(applicationContext)
        com.metrolist.music.chroma.audio.ChromaAudioFx.init(applicationContext)
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                runCatching { tick() }
                delay(500)
            }
        }
    }

    override fun onListenerDisconnected() {
        flush()
        ticker?.cancel()
        LiveActivity.cancel(this)
    }

    override fun onDestroy() {
        flush()
        scope.cancel()
        super.onDestroy()
    }

    private fun tick() {
        if (!ChromaPrefs.spotifyMode.value) { LiveActivity.cancel(this); return }
        val now = SpotifyRemote.state.value
        val t = System.currentTimeMillis()
        if (!now.connected || now.title.isEmpty()) {
            flush(); currentId = null; LiveActivity.cancel(this); lastPlaying = null
            return
        }
        val id = SpotifyBridge.songId(now)
        if (id != currentId) {
            flush()
            currentId = id
            playedMs = 0L
            lastTick = t
            lines = emptyList(); lyricsFor = null; lastLine = -2
            val base = SpotifyBridge.metadataFor(now)
            currentMeta = base
            scope.launch { prepareTrack(id, base, SpotifyBridge.trackId(now)) }
        } else if (now.playing) {
            playedMs += (t - lastTick).coerceIn(0L, 2_000L)
        }
        lastTick = t

        if (ChromaPrefs.liveActivity.value) {
            val pos = now.livePosition()
            val idx = lines.indexOfLast { !it.isBackground && it.time <= pos + 150 }
            if (idx != lastLine || now.playing != lastPlaying) {
                lastLine = idx; lastPlaying = now.playing
                val line = lines.getOrNull(idx)
                LiveActivity.show(this, now, line?.text?.takeIf { it.isNotBlank() }, line?.translatedTextFlow?.value)
            }
        } else LiveActivity.cancel(this)
    }

    /** Saves the song (with Spotify's ids and artwork when signed in) and loads its lyrics. */
    private suspend fun prepareTrack(id: String, base: MediaMetadata, trackId: String?) {
        var meta = base
        if (trackId != null && SpotifyApi.loggedIn.value) {
            withTimeoutOrNull(4000) { runCatching { SpotifyApi.track(trackId) }.getOrNull() }?.let { api ->
                meta = SpotifyBridge.metadataFor(SpotifyRemote.state.value, api).copy(id = id)
            }
        }
        if (currentId != id) return
        currentMeta = meta
        val m = meta
        database.query { insert(m) }

        val cached = runCatching { withTimeoutOrNull(1500) { database.lyrics(id).first() } }.getOrNull()
        val text = cached?.lyrics?.takeIf { it.isNotBlank() && it != LyricsEntity.LYRICS_NOT_FOUND }
            ?: runCatching {
                withTimeoutOrNull(25_000) { lyricsHelper.getLyrics(m) }?.also { r ->
                    if (r.lyrics != LyricsEntity.LYRICS_NOT_FOUND) database.query { upsert(LyricsEntity(id = id, lyrics = r.lyrics, provider = r.provider)) }
                }?.lyrics
            }.getOrNull()
        if (currentId != id) return
        lines = if (text == null || text == LyricsEntity.LYRICS_NOT_FOUND) emptyList()
        else runCatching { LyricsUtils.parseLyrics(text).filter { it.text.isNotBlank() } }.getOrDefault(emptyList())
        lyricsFor = id
        lastLine = -2
        com.metrolist.music.chroma.ChromaNowLyrics.publish(id, lines)
    }

    /** Counts the finished play the way Metrolist does, once it passed the history length. */
    private fun flush() {
        val id = currentId ?: return
        val played = playedMs
        playedMs = 0L
        if (!ChromaPrefs.keepHistory.value) return
        val minMs = (dataStore[HistoryDuration] ?: 30f) * 1000f
        if (played < minMs || dataStore.get(PauseListenHistoryKey, false)) return
        val meta = currentMeta
        database.query {
            if (meta != null) insert(meta)
            incrementTotalPlayTime(id, played)
            runCatching { insert(Event(songId = id, timestamp = LocalDateTime.now(), playTime = played)) }
            runCatching { incrementPlayCount(id) }
        }
    }
}

/** Android's take on Chroma's Live Activity: a live-update notification with the current lyric line. */
object LiveActivity {
    private const val CHANNEL = "chroma_live_activity"
    private const val ID = 7418
    private var posted = false

    fun show(ctx: Context, now: SpotifyNow, line: String?, translation: String?) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Live Activity", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows the song and its current lyric line"
                setShowBadge(false)
            })
        }
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        fun action(a: String, icon: Int, title: String) = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(ctx, icon), title,
            PendingIntent.getBroadcast(ctx, a.hashCode(), Intent(ctx, LiveActivityReceiver::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE),
        ).build()

        val title = line ?: now.title
        val text = if (line != null) "${now.title} · ${now.artist}" else now.artist
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(if (now.playing) "Now Playing" else "Paused")
            .setOngoing(now.playing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_STATUS)
            .addAction(action(LiveActivityReceiver.PREV, R.drawable.skip_previous, "Previous"))
            .addAction(if (now.playing) action(LiveActivityReceiver.PAUSE, R.drawable.pause, "Pause") else action(LiveActivityReceiver.PLAY, R.drawable.play, "Play"))
            .addAction(action(LiveActivityReceiver.NEXT, R.drawable.skip_next, "Next"))
        now.art?.let { b.setLargeIcon(it) }
        val big = buildString {
            append(text)
            if (ChromaPrefs.liveActivityTranslation.value && !translation.isNullOrBlank()) append("\n").append(translation)
        }
        b.setStyle(Notification.BigTextStyle().bigText(big).setBigContentTitle(title))
        if (Build.VERSION.SDK_INT >= 36) {
            runCatching {
                b.setRequestPromotedOngoing(true)
                b.setShortCriticalText((line ?: now.title).take(24))
            }
        }
        runCatching { nm.notify(ID, b.build()); posted = true }
    }

    fun cancel(ctx: Context) {
        if (!posted) return
        ctx.getSystemService(NotificationManager::class.java)?.cancel(ID)
        posted = false
    }
}

class LiveActivityReceiver : BroadcastReceiver() {
    companion object {
        const val PREV = "pw.chroma.music.live.PREV"
        const val NEXT = "pw.chroma.music.live.NEXT"
        const val PLAY = "pw.chroma.music.live.PLAY"
        const val PAUSE = "pw.chroma.music.live.PAUSE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        SpotifyRemote.start(context.applicationContext)
        when (intent.action) {
            PREV -> SpotifyRemote.previous()
            NEXT -> SpotifyRemote.next()
            PLAY -> SpotifyRemote.play()
            PAUSE -> SpotifyRemote.pause()
        }
    }
}
