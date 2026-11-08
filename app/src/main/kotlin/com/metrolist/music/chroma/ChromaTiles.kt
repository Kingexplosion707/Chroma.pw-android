package com.metrolist.music.chroma

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.metrolist.music.R
import com.metrolist.music.chroma.spotify.SpotifyApi
import com.metrolist.music.chroma.spotify.SpotifyBridge
import com.metrolist.music.chroma.spotify.SpotifyRemote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun wake(ctx: Context) {
    ChromaPrefs.init(ctx)
    SpotifyBridge.init(ctx)
    SpotifyRemote.start(ctx.applicationContext)
}

/** Chroma's sleep timer for the tile and shortcut: pauses Spotify, fading out over the last minute. */
object QuickSleep {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    var endsAt = 0L
        private set
    val active: Boolean get() = job?.isActive == true

    fun toggle(ctx: Context, minutes: Int = 30) = if (active) cancel() else start(ctx, minutes)

    fun start(ctx: Context, minutes: Int) {
        wake(ctx)
        job?.cancel()
        val end = System.currentTimeMillis() + minutes * 60_000L
        endsAt = end
        job = scope.launch {
            while (true) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) break
                if (ChromaPrefs.sleepFade.value && left < 60_000L) SpotifyBridge.onSleepVolume((left / 60_000f).coerceIn(0f, 1f))
                delay(1000)
            }
            SpotifyBridge.pause()
            SpotifyBridge.onSleepVolume(1f)
            endsAt = 0L
        }
    }

    fun cancel() {
        job?.cancel(); job = null; endsAt = 0L
        SpotifyBridge.onSleepVolume(1f)
    }
}

class ChromaLikeTile : TileService() {
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        wake(this)
        update()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scope = it }
        if (SpotifyApi.loggedIn.value) s.launch { runCatching { SpotifyBridge.refresh() }; update() }
    }

    override fun onStopListening() { scope?.cancel(); scope = null }

    override fun onClick() {
        wake(this)
        SpotifyBridge.toggleLike(null)
        update()
    }

    private fun update() {
        val t = qsTile ?: return
        val now = SpotifyRemote.state.value
        t.label = if (SpotifyBridge.liked.value) "Liked" else "Like song"
        t.state = if (SpotifyBridge.liked.value) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = now.title.ifEmpty { null }
        t.updateTile()
    }
}

class ChromaNextTile : TileService() {
    override fun onStartListening() {
        wake(this)
        qsTile?.apply {
            label = "Next song"
            state = Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= 29) subtitle = SpotifyRemote.state.value.title.ifEmpty { null }
            updateTile()
        }
    }

    override fun onClick() {
        wake(this)
        SpotifyBridge.next()
    }
}

class ChromaSleepTile : TileService() {
    override fun onStartListening() = update()

    override fun onClick() {
        QuickSleep.toggle(applicationContext)
        update()
    }

    private fun update() {
        val t = qsTile ?: return
        t.label = "Sleep timer"
        t.state = if (QuickSleep.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) {
            val mins = ((QuickSleep.endsAt - System.currentTimeMillis()) / 60_000L + 1).coerceAtLeast(1)
            t.subtitle = if (QuickSleep.active) "Pauses in $mins min" else "30 minutes"
        }
        t.updateTile()
    }
}

/** Runs a launcher shortcut (Like, Next, Sleep timer) without opening the app. */
class ChromaActionActivity : Activity() {
    companion object {
        const val LIKE = "pw.chroma.music.action.LIKE"
        const val NEXT = "pw.chroma.music.action.NEXT"
        const val SLEEP = "pw.chroma.music.action.SLEEP"

        fun publishShortcuts(ctx: Context) {
            fun shortcut(id: String, label: String, icon: Int, action: String) =
                ShortcutInfoCompat.Builder(ctx, id)
                    .setShortLabel(label)
                    .setIcon(IconCompat.createWithResource(ctx, icon))
                    .setIntent(Intent(ctx, ChromaActionActivity::class.java).setAction(action))
                    .build()
            runCatching {
                ShortcutManagerCompat.setDynamicShortcuts(
                    ctx,
                    listOf(
                        shortcut("chroma_like", "Like song", R.drawable.favorite, LIKE),
                        shortcut("chroma_next", "Next song", R.drawable.skip_next, NEXT),
                        shortcut("chroma_sleep", "Sleep timer", R.drawable.bedtime, SLEEP),
                    ),
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wake(this)
        when (intent?.action) {
            LIKE -> SpotifyBridge.toggleLike(null)
            NEXT -> SpotifyBridge.next()
            SLEEP -> {
                QuickSleep.toggle(applicationContext)
                SpotifyBridge.toast(if (QuickSleep.active) "Spotify pauses in 30 minutes" else "Sleep timer off")
            }
        }
        finish()
    }
}
