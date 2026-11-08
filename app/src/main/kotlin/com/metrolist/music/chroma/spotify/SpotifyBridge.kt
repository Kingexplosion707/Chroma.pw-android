package com.metrolist.music.chroma.spotify

import android.content.Context
import android.media.AudioManager
import android.media.session.PlaybackState
import android.widget.Toast
import com.metrolist.music.chroma.ChromaPrefs
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import kotlin.math.abs

/**
 * Spotify state that the Media Session can't give: shuffle, repeat, queue, liked, Connect device
 * and context, from the Web API when signed in. Also the one place that sends commands to Spotify,
 * Media Session first (instant, works on free accounts) with the Web API as fallback.
 */
object SpotifyBridge {
    val snap = MutableStateFlow<PlayerSnap?>(null)
    val queue = MutableStateFlow<List<SpTrack>>(emptyList())
    val liked = MutableStateFlow(false)
    val contextName = MutableStateFlow("")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var poller: Job? = null
    private var pollers = 0
    private var likedFor: String? = null
    private var queueFor: String? = null
    private var ctxFor: String? = null
    private var app: Context? = null

    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        SpotifyApi.init(ctx)
        ChromaPrefs.init(ctx)
    }

    /** Polls the Web API while some screen needs it. Calls must be balanced with [stopPolling]. */
    fun startPolling(ctx: Context) {
        init(ctx)
        pollers++
        if (poller?.isActive == true) return
        poller = scope.launch {
            while (isActive) {
                if (SpotifyApi.loggedIn.value) refresh()
                delay(3500)
            }
        }
    }

    fun stopPolling() {
        pollers = (pollers - 1).coerceAtLeast(0)
        if (pollers == 0) { poller?.cancel(); poller = null }
    }

    suspend fun refresh() {
        val s = runCatching { SpotifyApi.player() }.getOrNull()
        snap.value = s
        val id = s?.track?.id
        if (id != null && id != likedFor) {
            likedFor = id
            liked.value = runCatching { SpotifyApi.isSaved(id) }.getOrDefault(false)
        }
        if (id != null && id != queueFor) {
            queueFor = id
            queue.value = runCatching { SpotifyApi.queue() }.getOrDefault(emptyList())
        }
        val c = s?.contextUri
        if (c != ctxFor) {
            ctxFor = c
            contextName.value = if (c == null) "" else runCatching { resolveContextName(c) }.getOrDefault("")
        }
    }

    fun refreshSoon() { scope.launch { delay(450); if (SpotifyApi.loggedIn.value) refresh() } }

    private suspend fun resolveContextName(uri: String): String {
        val id = uri.substringAfterLast(':')
        return when {
            uri.contains(":playlist:") -> SpotifyApi.call("GET", "/playlists/$id?fields=name")?.optString("name")
            uri.contains(":album:") -> SpotifyApi.call("GET", "/albums/$id")?.optString("name")
            uri.contains(":artist:") -> SpotifyApi.call("GET", "/artists/$id")?.optString("name")
            uri.contains(":collection") -> "Liked Songs"
            else -> null
        }.orEmpty()
    }

    /** Spotify track id of what is playing, from the Media Session or the Web API. */
    fun trackId(now: SpotifyNow = SpotifyRemote.state.value): String? =
        now.mediaId.takeIf { it.startsWith("spotify:track:") }?.substringAfterLast(':')
            ?: snap.value?.track?.takeIf { now.title.isEmpty() || it.name.equals(now.title, true) }?.id

    /** Stable id for a track, used as the song id in Metrolist's database. */
    fun songId(now: SpotifyNow): String =
        trackId(now) ?: ("chroma" + abs((now.title + "|" + now.artist).hashCode()).toString())

    /** Builds Metrolist's track model from the Media Session, enriched by the Web API when it matches. */
    fun metadataFor(now: SpotifyNow, api: SpTrack? = snap.value?.track): MediaMetadata {
        val match = api?.takeIf { a -> a.id == trackId(now) || a.name.equals(now.title, true) }
        val artists = match?.artists?.map { MediaMetadata.Artist(it.id, it.name) }
            ?: now.artist.split(", ").filter { it.isNotBlank() }.map { MediaMetadata.Artist(null, it) }
        return MediaMetadata(
            id = songId(now),
            title = now.title.ifEmpty { match?.name.orEmpty() },
            artists = artists.ifEmpty { listOf(MediaMetadata.Artist(null, now.artist)) },
            duration = ((if (now.durationMs > 0) now.durationMs else match?.durationMs ?: 0L) / 1000).toInt(),
            thumbnailUrl = match?.imageLarge ?: match?.image ?: now.artUri,
            album = match?.takeIf { it.albumId.isNotEmpty() }?.let { MediaMetadata.Album(it.albumId, it.albumName) },
            explicit = match?.explicit ?: false,
        )
    }

    // ---------- commands ----------
    private fun session() = SpotifyRemote.state.value.connected

    fun play() { if (session()) SpotifyRemote.play() else api { SpotifyApi.resume() } }
    fun pause() { if (session()) SpotifyRemote.pause() else api { SpotifyApi.pause() } }
    fun next() { if (session()) SpotifyRemote.next() else api { SpotifyApi.next() } }
    fun previous() { if (session()) SpotifyRemote.previous() else api { SpotifyApi.previous() } }
    fun seekTo(ms: Long) { if (session()) SpotifyRemote.seekTo(ms) else api { SpotifyApi.seek(ms) } }

    fun skipForward(times: Int) {
        if (times <= 0) return
        scope.launch { repeat(times) { next(); delay(320) } }
    }

    fun setShuffle(on: Boolean) {
        snap.value = snap.value?.copy(shuffle = on)
        if (SpotifyApi.loggedIn.value) api { SpotifyApi.shuffle(on) } else SpotifyRemote.custom("shuffle")
    }

    fun setRepeat(mode: String) {
        snap.value = snap.value?.copy(repeat = mode)
        if (SpotifyApi.loggedIn.value) api { SpotifyApi.repeat(mode) } else SpotifyRemote.custom("repeat")
    }

    private fun api(block: suspend () -> Unit) {
        scope.launch {
            try { block(); delay(350); refresh() } catch (e: Exception) { toast(e.message) }
        }
    }

    /** Likes or unlikes what's playing on Spotify and mirrors it into Metrolist's database. */
    fun toggleLike(database: MusicDatabase?) {
        val now = SpotifyRemote.state.value
        val id = trackId(now)
        val target = !liked.value
        liked.value = target
        database?.let { db -> mirrorLike(db, songId(now), target) }
        if (id == null || !SpotifyApi.loggedIn.value) {
            if (!SpotifyRemote.custom("like", "heart", "save", "favorite", "add_to")) toast("Log in to Spotify in Chroma Settings to like songs")
            return
        }
        scope.launch {
            try { SpotifyApi.setSaved(id, target) } catch (e: Exception) {
                liked.value = !target
                database?.let { db -> mirrorLike(db, songId(now), !target) }
                toast(e.message)
            }
        }
    }

    fun mirrorLike(db: MusicDatabase, songId: String, isLiked: Boolean) {
        db.query {
            getSongByIdBlocking(songId)?.song?.let { s ->
                if (s.liked != isLiked) update(s.copy(liked = isLiked, likedDate = if (isLiked) LocalDateTime.now() else null))
            }
        }
    }

    /**
     * Plays something on Spotify: Web API with offsets when signed in (Premium), else the Media
     * Session's play-from-URI, else opens it in the Spotify app.
     */
    fun playUri(ctx: Context, context: String? = null, uris: List<String>? = null, startUri: String? = null) {
        init(ctx)
        scope.launch {
            val viaApi = SpotifyApi.loggedIn.value && runCatching { SpotifyApi.play(context, uris, startUri) }.isSuccess
            if (viaApi) { delay(500); refresh(); return@launch }
            val target = startUri ?: context ?: uris?.firstOrNull() ?: return@launch
            if (!SpotifyRemote.playFromUri(target)) SpotifyRemote.openSpotify(ctx, target)
        }
    }

    fun launch(block: suspend () -> Unit) {
        scope.launch { try { block() } catch (e: Exception) { toast(e.message) } }
    }

    fun toast(msg: String?) {
        val c = app ?: return
        Toast.makeText(c, msg ?: "Something went wrong", Toast.LENGTH_LONG).show()
    }

    // ---------- sleep timer fade on the phone's music volume ----------
    private var fadeBase = -1
    private var restoreJob: Job? = null

    /**
     * Fades the phone's music volume for the sleep timer. The volume comes back a moment after the
     * fade ends, so Spotify has paused by then instead of blasting at full volume for a second.
     */
    fun onSleepVolume(multiplier: Float) {
        if (!ChromaPrefs.spotifyMode.value) return
        val am = app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (multiplier >= 0.999f) {
            if (fadeBase >= 0 && restoreJob?.isActive != true) {
                val base = fadeBase
                restoreJob = scope.launch {
                    delay(1500)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, base, 0)
                    fadeBase = -1
                }
            }
            return
        }
        restoreJob?.cancel(); restoreJob = null
        if (fadeBase < 0) fadeBase = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(fadeBase * multiplier).coerceAtLeast(0), 0)
    }

    fun isPlayingNow(): Boolean = SpotifyRemote.state.value.playing || (snap.value?.playing == true && !session())

    @Suppress("unused") private val keep = PlaybackState.STATE_PLAYING
}
