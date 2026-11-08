package com.metrolist.music.chroma.spotify

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream

const val SPOTIFY_PKG = "com.spotify.music"

data class SpotifyNow(
    val connected: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    /** spotify:track:… when Spotify provides it */
    val mediaId: String = "",
    val art: Bitmap? = null,
    /** file:// path of the cached artwork, for image loaders */
    val artUri: String? = null,
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val positionTs: Long = 0,
    val speed: Float = 1f,
    val playing: Boolean = false,
) {
    fun livePosition(): Long {
        val p = if (playing) positionMs + ((SystemClock.elapsedRealtime() - positionTs) * speed).toLong() else positionMs
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
    }
}

/** Reads and controls the official Spotify app through its media session. */
object SpotifyRemote {
    private val _state = MutableStateFlow(SpotifyNow())
    val state: StateFlow<SpotifyNow> = _state
    private var controller: MediaController? = null
    private var started = false
    private var app: Context? = null

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onSessionDestroyed() { controller = null; _state.value = SpotifyNow() }
    }

    /** The last track Spotify announced with "Device broadcast status" on: title, artist, spotify:track id. */
    @Volatile private var announced: Triple<String, String, String>? = null
    private var broadcastsOn = false

    private val announcements = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getStringExtra("id")?.takeIf { it.startsWith("spotify:track:") } ?: return
            announced = Triple(intent.getStringExtra("track").orEmpty(), intent.getStringExtra("artist").orEmpty(), id.substringAfterLast(':'))
        }
    }

    /** The Spotify track id of [now] from Spotify's own broadcast, when it matches. */
    fun announcedTrackId(now: SpotifyNow): String? =
        announced?.takeIf { (t, _, _) -> t.isNotEmpty() && t.equals(now.title, ignoreCase = true) }?.third

    private fun listenForAnnouncements(ctx: Context) {
        if (broadcastsOn) return
        broadcastsOn = true
        runCatching {
            ContextCompat.registerReceiver(
                ctx.applicationContext, announcements, IntentFilter("com.spotify.music.metadatachanged"), ContextCompat.RECEIVER_EXPORTED,
            )
        }
    }

    fun hasAccess(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    fun spotifyInstalled(ctx: Context) = ctx.packageManager.getLaunchIntentForPackage(SPOTIFY_PKG) != null

    fun start(ctx: Context) {
        app = ctx.applicationContext
        listenForAnnouncements(ctx)
        if (!hasAccess(ctx)) return
        val mgr = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val cn = ComponentName(ctx, SpotifyListener::class.java)
        try {
            if (!started) { mgr.addOnActiveSessionsChangedListener({ attach(it.orEmpty()) }, cn); started = true }
            attach(mgr.getActiveSessions(cn))
        } catch (_: SecurityException) {}
    }

    private fun attach(list: List<MediaController>) {
        val c = list.firstOrNull { it.packageName == SPOTIFY_PKG }
        if (c?.sessionToken == controller?.sessionToken) { refresh(); return }
        controller?.unregisterCallback(callback)
        controller = c
        c?.registerCallback(callback)
        refresh()
    }

    private fun refresh() {
        val c = controller ?: run { _state.value = SpotifyNow(); return }
        val m = c.metadata
        val s = c.playbackState
        val art = m?.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: m?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val old = _state.value
        val title = m?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artUri = when {
            art == null -> null
            art === old.art -> old.artUri
            else -> cacheArt(art, title)
        }
        _state.value = SpotifyNow(
            connected = true,
            title = title,
            artist = (m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty(),
            album = m?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            mediaId = m?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty(),
            art = art, artUri = artUri,
            durationMs = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
            positionMs = s?.position ?: 0,
            positionTs = s?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
            speed = s?.playbackSpeed ?: 1f,
            playing = s?.state == PlaybackState.STATE_PLAYING,
        )
    }

    private fun cacheArt(b: Bitmap, key: String): String? = runCatching {
        val dir = File(app!!.cacheDir, "spotify_art").apply { mkdirs() }
        dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(10)?.forEach { it.delete() }
        val f = File(dir, "art_${(key + b.generationId).hashCode().toUInt()}.jpg")
        FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        "file://${f.absolutePath}"
    }.getOrNull()

    fun play() { controller?.transportControls?.play() }
    fun pause() { controller?.transportControls?.pause() }
    fun next() { controller?.transportControls?.skipToNext() }
    fun previous() { controller?.transportControls?.skipToPrevious() }
    fun seekTo(ms: Long) { controller?.transportControls?.seekTo(ms.coerceAtLeast(0)) }

    /** Starts a spotify: URI through the Media Session when Spotify allows it (works without Premium). */
    fun playFromUri(uri: String): Boolean {
        val c = controller ?: return false
        val actions = c.playbackState?.actions ?: 0L
        if (actions and PlaybackState.ACTION_PLAY_FROM_URI == 0L) return false
        c.transportControls.playFromUri(android.net.Uri.parse(uri), null)
        return true
    }

    fun skipToQueueItem(id: Long) { controller?.transportControls?.skipToQueueItem(id) }

    /** Spotify exposes some actions as custom actions; match by keyword. */
    fun custom(vararg keywords: String): Boolean {
        val c = controller ?: return false
        val a = c.playbackState?.customActions.orEmpty().firstOrNull { ca ->
            keywords.any { k -> ca.action.contains(k, true) || ca.name.toString().contains(k, true) }
        } ?: return false
        c.transportControls.sendCustomAction(a, null)
        return true
    }

    fun openSpotify(ctx: Context, uri: String? = null) {
        val i = if (uri != null) Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).setPackage(SPOTIFY_PKG)
        else ctx.packageManager.getLaunchIntentForPackage(SPOTIFY_PKG) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
    }
}
