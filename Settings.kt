package pw.chroma.android

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

/** Persistent, Compose-observable settings. */
object Settings {
    private lateinit var sp: SharedPreferences
    private val _accent = mutableIntStateOf(0xFF1ED760.toInt())
    private val _amoled = mutableStateOf(true)
    private val _tint = mutableStateOf(true)
    private val _round = mutableStateOf(true)
    private val _align = mutableIntStateOf(0)
    private val _doubleTap = mutableIntStateOf(1)
    private val _haptics = mutableStateOf(true)
    private val _fade = mutableStateOf(true)
    private val _hideLyricsOnError = mutableStateOf(false)
    private val _icon = mutableStateOf("icon_chroma")
    private val _hMode = mutableIntStateOf(2)
    private val _hInt = mutableFloatStateOf(0.8f)
    private val _spDc = mutableStateOf("")
    private val _video = mutableStateOf(true)
    private val _canvasUrl = mutableStateOf("")

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("chroma", Context.MODE_PRIVATE)
        _accent.intValue = sp.getInt("accent", _accent.intValue)
        _amoled.value = sp.getBoolean("amoled", true)
        _tint.value = sp.getBoolean("tint", true)
        _round.value = sp.getBoolean("round", true)
        _align.intValue = sp.getInt("align", 0)
        _doubleTap.intValue = sp.getInt("doubleTap", 1)
        _haptics.value = sp.getBoolean("haptics", true)
        _fade.value = sp.getBoolean("fade", true)
        _hideLyricsOnError.value = sp.getBoolean("hideLyricsOnError", false)
        _icon.value = sp.getString("icon", "icon_chroma") ?: "icon_chroma"
        _hMode.intValue = sp.getInt("hMode", 2)
        _hInt.floatValue = sp.getFloat("hInt", 0.8f)
        _spDc.value = sp.getString("spDc", "") ?: ""
        _video.value = sp.getBoolean("wpVideo", true)
        _canvasUrl.value = sp.getString("canvasUrl", "") ?: ""
    }

    var accent: Int get() = _accent.intValue
        set(v) { _accent.intValue = v; sp.edit().putInt("accent", v).apply() }
    var amoled: Boolean get() = _amoled.value
        set(v) { _amoled.value = v; sp.edit().putBoolean("amoled", v).apply() }
    var tintAlbum: Boolean get() = _tint.value
        set(v) { _tint.value = v; sp.edit().putBoolean("tint", v).apply() }
    var roundArtwork: Boolean get() = _round.value
        set(v) { _round.value = v; sp.edit().putBoolean("round", v).apply() }
    /** 0 = left, 1 = center, 2 = right */
    var lyricsAlign: Int get() = _align.intValue
        set(v) { _align.intValue = v; sp.edit().putInt("align", v).apply() }
    /** 0 = off, 1 = seek 10s, 2 = skip track */
    var doubleTap: Int get() = _doubleTap.intValue
        set(v) { _doubleTap.intValue = v; sp.edit().putInt("doubleTap", v).apply() }
    var haptics: Boolean get() = _haptics.value
        set(v) { _haptics.value = v; sp.edit().putBoolean("haptics", v).apply() }
    var sleepFade: Boolean get() = _fade.value
        set(v) { _fade.value = v; sp.edit().putBoolean("fade", v).apply() }
    var hideLyricsOnError: Boolean get() = _hideLyricsOnError.value
        set(v) { _hideLyricsOnError.value = v; sp.edit().putBoolean("hideLyricsOnError", v).apply() }
    var icon: String get() = _icon.value
        set(v) { _icon.value = v; sp.edit().putString("icon", v).apply() }
    var wallpaperVideo: Boolean get() = _video.value
        set(v) { _video.value = v; sp.edit().putBoolean("wpVideo", v).apply() }
    /** Optional endpoint template; {uri} / {id} are replaced with the Spotify track URI / id. */
    var canvasUrl: String get() = _canvasUrl.value
        set(v) { _canvasUrl.value = v; sp.edit().putString("canvasUrl", v).apply() }
    /** 0 = bass pulse, 1 = beat, 2 = beat + bass */
    var hapticMode: Int get() = _hMode.intValue
        set(v) { _hMode.intValue = v; sp.edit().putInt("hMode", v).apply() }
    var hapticIntensity: Float get() = _hInt.floatValue
        set(v) { _hInt.floatValue = v; sp.edit().putFloat("hInt", v).apply() }
    /** Spotify web cookie, kept only in this app's private storage. */
    var spDc: String get() = _spDc.value
        set(v) { _spDc.value = v; sp.edit().putString("spDc", v).apply() }
}
