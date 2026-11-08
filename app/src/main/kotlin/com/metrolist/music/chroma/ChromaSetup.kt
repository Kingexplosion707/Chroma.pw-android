package com.metrolist.music.chroma

import android.app.Application
import android.content.Context
import com.metrolist.music.chroma.audio.ChromaAudioFx
import com.metrolist.music.chroma.spotify.SpotifyBridge
import com.metrolist.music.constants.DarkModeKey
import com.metrolist.music.constants.DynamicThemeKey
import com.metrolist.music.constants.LyricsProviderOrderKey
import com.metrolist.music.constants.PlayerBackgroundStyleKey
import com.metrolist.music.constants.PureBlackKey
import com.metrolist.music.constants.SelectedThemeColorKey
import com.metrolist.music.lyrics.LyricsProviderRegistry
import com.metrolist.music.utils.safeDataStoreEdit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Starts Chroma's parts with the app, and gives a fresh install Chroma's look once. */
object ChromaSetup {
    private const val DEFAULTS = "chromaDefaultsV1"

    fun onAppCreate(app: Application, scope: CoroutineScope) {
        ChromaPrefs.init(app)
        SpotifyBridge.init(app)
        ChromaAudioFx.init(app)
        ChromaActionActivity.publishShortcuts(app)
        val sp = app.getSharedPreferences("chroma", Context.MODE_PRIVATE)
        if (sp.getBoolean(DEFAULTS, false)) return
        scope.launch(Dispatchers.IO) {
            val ok = app.safeDataStoreEdit { p ->
                p[SelectedThemeColorKey] = 0xFFFA2D48.toInt()
                p[DynamicThemeKey] = false
                p[PureBlackKey] = true
                p[DarkModeKey] = "ON"
                p[PlayerBackgroundStyleKey] = "BLUR"
                p[LyricsProviderOrderKey] = LyricsProviderRegistry.serializeProviderOrder(LyricsProviderRegistry.getDefaultProviderOrder())
            }
            if (ok) sp.edit().putBoolean(DEFAULTS, true).apply()
        }
    }
}
