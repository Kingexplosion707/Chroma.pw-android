package pw.chroma.android

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.*

/**
 * One place for playback: metadata and position come from Spotify's media session (instant, works
 * on free), while shuffle/repeat/like/device/context come from the Web API when connected.
 */
object Playback {
    val snap = mutableStateOf<PlayerSnap?>(null)
    val liked = mutableStateOf(false)
    val contextName = mutableStateOf("")
    private var likedFor = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var poller: Job? = null
    lateinit var app: Context

    fun init(ctx: Context) { app = ctx.applicationContext }

    fun startPolling() {
        if (poller?.isActive == true) return
        poller = scope.launch {
            while (isActive) {
                if (Api.loggedIn.value) refresh()
                delay(4000)
            }
        }
    }

    fun stopPolling() { poller?.cancel(); poller = null }

    suspend fun refresh() {
        val s = runCatching { Api.player() }.getOrNull()
        snap.value = s
        val id = s?.track?.id ?: return
        if (id != likedFor) {
            likedFor = id
            liked.value = runCatching { Api.isSaved(id) }.getOrDefault(false)
        }
        val ctx = s.contextUri
        if (ctx != null && ctx != lastCtx) { lastCtx = ctx; contextName.value = resolveContextName(ctx) }
        if (ctx == null) { lastCtx = null; contextName.value = "" }
    }

    private var lastCtx: String? = null

    private suspend fun resolveContextName(uri: String): String = runCatching {
        val id = uri.substringAfterLast(':')
        when {
            uri.contains(":playlist:") -> Api.call("GET", "/playlists/$id?fields=name")?.optString("name")
            uri.contains(":album:") -> Api.call("GET", "/albums/$id")?.optString("name")
            uri.contains(":artist:") -> Api.call("GET", "/artists/$id")?.optString("name")
            uri.contains(":collection") -> "Liked Songs"
            else -> null
        }
    }.getOrNull().orEmpty()

    fun contextLabel(): String {
        val u = snap.value?.contextUri ?: return "PLAYING FROM SPOTIFY"
        return when {
            u.contains(":playlist:") -> "PLAYING FROM PLAYLIST"
            u.contains(":album:") -> "PLAYING FROM ALBUM"
            u.contains(":artist:") -> "PLAYING FROM ARTIST"
            u.contains(":collection") -> "PLAYING FROM YOUR LIBRARY"
            else -> "PLAYING FROM SPOTIFY"
        }
    }

    /** The Spotify track id of what's playing, from the API or the media session. */
    fun trackId(): String? = snap.value?.track?.id
        ?: Remote.state.value.mediaId.takeIf { it.startsWith("spotify:track:") }?.substringAfterLast(':')

    // ---------- actions: Web API first, media session fallback ----------
    private fun act(api: suspend () -> Unit, fallback: () -> Unit) {
        if (!Api.loggedIn.value) { fallback(); return }
        scope.launch {
            try { api(); delay(350); refresh() } catch (e: Exception) { fallback() }
        }
    }

    fun playPause() {
        val playing = Remote.state.value.playing
        if (Remote.state.value.connected) { Remote.playPause(); return }
        act({ if (playing) Api.pause() else Api.resume() }, { Remote.playPause() })
    }

    fun next() { if (Remote.state.value.connected) Remote.next() else act({ Api.next() }, {}) }
    fun previous() { if (Remote.state.value.connected) Remote.previous() else act({ Api.previous() }, {}) }
    fun seekTo(ms: Long) { if (Remote.state.value.connected) Remote.seekTo(ms) else act({ Api.seek(ms) }, {}) }

    fun toggleShuffle() {
        val on = !(snap.value?.shuffle ?: false)
        snap.value = snap.value?.copy(shuffle = on)
        act({ Api.shuffle(on) }, { Remote.custom("shuffle") })
    }

    fun cycleRepeat() {
        val nextMode = when (snap.value?.repeat) { "off", null -> "context"; "context" -> "track"; else -> "off" }
        snap.value = snap.value?.copy(repeat = nextMode)
        act({ Api.repeat(nextMode) }, { Remote.custom("repeat") })
    }

    fun toggleLike() {
        val id = trackId()
        if (id == null || !Api.loggedIn.value) { Remote.custom("like", "heart", "save", "favorite"); return }
        val target = !liked.value
        liked.value = target
        scope.launch {
            try { Api.setSaved(id, target) } catch (e: Exception) { liked.value = !target; toast(e.message) }
        }
    }

    /** Plays something, surfacing errors as a toast. */
    fun play(context: String? = null, uris: List<String>? = null, startUri: String? = null) {
        scope.launch {
            try { Api.play(context, uris, startUri); delay(600); refresh() } catch (e: Exception) { toast(e.message) }
        }
    }

    fun launch(block: suspend () -> Unit) {
        scope.launch { try { block() } catch (e: Exception) { toast(e.message) } }
    }

    fun toast(msg: String?) { Toast.makeText(app, msg ?: "Something went wrong", Toast.LENGTH_LONG).show() }

    /** Share a clean open.spotify.com link (no tracking parameters when Clean Share Links is on). */
    fun share(ctx: Context, uri: String, title: String) {
        val parts = uri.split(':')
        if (parts.size < 3) return
        var url = "https://open.spotify.com/${parts[1]}/${parts[2]}"
        if (!Settings.cleanShareLinks) url += "?si=chroma"
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url).putExtra(Intent.EXTRA_SUBJECT, title)
        ctx.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
