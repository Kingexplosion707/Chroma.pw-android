package pw.chroma.android

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

const val SPOTIFY_PKG = "com.spotify.music"

data class NowPlaying(
    val connected: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val mediaId: String = "",
    val art: Bitmap? = null,
    val artColor: Int? = null,
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val positionTs: Long = 0,
    val speed: Float = 1f,
    val playing: Boolean = false,
    val actions: List<String> = emptyList(),
) {
    fun livePosition(): Long {
        val p = if (playing) positionMs + ((SystemClock.elapsedRealtime() - positionTs) * speed).toLong() else positionMs
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
    }
}

/** Client side of Chroma: talks to the official Spotify app through its media session. */
object Remote {
    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state

    private var controller: MediaController? = null
    private var started = false

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onSessionDestroyed() { controller = null; _state.value = NowPlaying() }
    }

    fun hasAccess(ctx: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun spotifyInstalled(ctx: Context): Boolean =
        ctx.packageManager.getLaunchIntentForPackage(SPOTIFY_PKG) != null

    fun start(ctx: Context) {
        if (!hasAccess(ctx)) return
        val mgr = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val cn = ComponentName(ctx, SpotifyListener::class.java)
        try {
            if (!started) {
                mgr.addOnActiveSessionsChangedListener({ list -> attach(list.orEmpty()) }, cn)
                started = true
            }
            attach(mgr.getActiveSessions(cn))
        } catch (_: SecurityException) { /* access revoked */ }
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
        val c = controller ?: run { _state.value = NowPlaying(); return }
        val m = c.metadata
        val s = c.playbackState
        val art = m?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: m?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val old = _state.value
        val color = if (art == null) null else if (art === old.art) old.artColor else averageColor(art)
        _state.value = NowPlaying(
            connected = true,
            title = m?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = (m?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty(),
            album = m?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            mediaId = m?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty(),
            art = art,
            artColor = color,
            durationMs = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
            positionMs = s?.position ?: 0,
            positionTs = s?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
            speed = s?.playbackSpeed ?: 1f,
            playing = s?.state == PlaybackState.STATE_PLAYING,
            actions = s?.customActions.orEmpty().map { it.action },
        )
    }

    /** Average of the more saturated pixels, darkened so white text stays readable. */
    private fun averageColor(b: Bitmap): Int {
        val sm = Bitmap.createScaledBitmap(b, 16, 16, true)
        var r = 0L; var g = 0L; var bl = 0L; var n = 0L
        for (x in 0 until 16) for (y in 0 until 16) {
            val p = sm.getPixel(x, y)
            val rr = (p shr 16) and 255; val gg = (p shr 8) and 255; val bb = p and 255
            val sat = maxOf(rr, gg, bb) - minOf(rr, gg, bb)
            val w = 1 + sat / 16
            r += rr * w; g += gg * w; bl += bb * w; n += w
        }
        val rr = (r / n * 0.55).toInt(); val gg = (g / n * 0.55).toInt(); val bb = (bl / n * 0.55).toInt()
        return (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
    }

    // ---- controls ----
    fun play() = controller?.transportControls?.play()
    fun pause() = controller?.transportControls?.pause()
    fun playPause() { if (_state.value.playing) pause() else play() }
    fun next() = controller?.transportControls?.skipToNext()
    fun previous() = controller?.transportControls?.skipToPrevious()
    fun seekTo(ms: Long) {
        controller?.transportControls?.seekTo(ms.coerceAtLeast(0))
    }
    fun seekBy(deltaMs: Long) = seekTo(_state.value.livePosition() + deltaMs)

    /** Spotify exposes some actions as custom actions; match them by keyword. Returns false if absent. */
    fun custom(vararg keywords: String): Boolean {
        val c = controller ?: return false
        val a = c.playbackState?.customActions.orEmpty().firstOrNull { ca ->
            keywords.any { k -> ca.action.contains(k, true) || ca.name.toString().contains(k, true) }
        } ?: return false
        c.transportControls.sendCustomAction(a, null)
        return true
    }

    fun openSpotify(ctx: Context) {
        ctx.packageManager.getLaunchIntentForPackage(SPOTIFY_PKG)?.let {
            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(it)
        }
    }
}
