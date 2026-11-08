package com.metrolist.music.chroma

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject

/** A persisted, Compose-observable Chroma preference. */
class ChromaPref<T>(
    val key: String,
    val def: T,
    private val read: (SharedPreferences, String, T) -> T,
    private val write: (SharedPreferences.Editor, String, T) -> Unit,
) {
    private val state = mutableStateOf(def)
    var value: T
        get() = state.value
        set(v) {
            state.value = v
            ChromaPrefs.sp?.edit()?.also { write(it, key, v) }?.apply()
        }

    internal fun load(sp: SharedPreferences) { state.value = read(sp, key, def) }
    internal fun reset() { state.value = def }
    internal fun export(o: JSONObject) { o.put(key, value) }

    @Suppress("UNCHECKED_CAST")
    internal fun import(o: JSONObject) {
        if (!o.has(key)) return
        value = when (def) {
            is Boolean -> o.getBoolean(key) as T
            is Int -> o.getInt(key) as T
            is Float -> o.getDouble(key).toFloat() as T
            is String -> o.getString(key) as T
            else -> return
        }
    }
}

/**
 * Chroma's settings, named and grouped after the iOS Chroma Settings pages. Kept apart from
 * Metrolist's own DataStore so Metrolist updates never touch them.
 */
object ChromaPrefs {
    internal var sp: SharedPreferences? = null
    private val all = mutableListOf<ChromaPref<*>>()

    private fun bool(k: String, d: Boolean) = ChromaPref(k, d, { s, key, def -> s.getBoolean(key, def) }, { e, key, v -> e.putBoolean(key, v) }).also { all += it }
    private fun int(k: String, d: Int) = ChromaPref(k, d, { s, key, def -> s.getInt(key, def) }, { e, key, v -> e.putInt(key, v) }).also { all += it }
    private fun float(k: String, d: Float) = ChromaPref(k, d, { s, key, def -> s.getFloat(key, def) }, { e, key, v -> e.putFloat(key, v) }).also { all += it }
    private fun str(k: String, d: String) = ChromaPref(k, d, { s, key, def -> s.getString(key, def) ?: def }, { e, key, v -> e.putString(key, v) }).also { all += it }

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.applicationContext.getSharedPreferences("chroma", Context.MODE_PRIVATE)
        sp = p
        all.forEach { it.load(p) }
    }

    internal fun prefs(): List<ChromaPref<*>> = all

    /** Back to defaults. The sp_dc cookie and the Spotify login (kept elsewhere) stay. */
    fun resetAll() {
        val dc = spDc.value
        sp?.edit()?.clear()?.apply()
        all.forEach { it.reset() }
        if (dc.isNotEmpty()) spDc.value = dc
    }

    fun exportJson(): String = JSONObject().also { o -> all.filter { it.key != spDc.key }.forEach { it.export(o) } }.put("chroma", 1).toString(2)

    fun importJson(json: String): Boolean = runCatching {
        val o = JSONObject(json)
        if (!o.has("chroma")) return false
        all.forEach { it.import(o) }
        true
    }.getOrDefault(false)

    // ---- Spotify ----
    /** Metrolist's player, home, search and library drive the Spotify app instead of YouTube Music. */
    val spotifyMode = bool("spotifyMode", true)
    val spDc = str("spDc", "")

    // ---- Appearance ----
    /** 0 = Spotify green, 1 = Apple Music red, 2 = picked colour, 3 = from the cover */
    val accentMode = int("accentMode", 1)
    val accentColor = int("accentColor", 0xFFFA2D48.toInt())
    val amoled = bool("amoled", true)
    val glassBar = bool("glassBar", true)
    val glassMiniPlayer = bool("glassMini", true)
    val glassHeaderButtons = bool("glassHeaderButtons", true)
    val blackFadeUnderTabBar = bool("blackFade", true)
    val icon = str("icon", "")

    // ---- Home & Library ----
    val homeFilterPills = bool("homePills", true)
    val homeShortcuts = bool("homeShortcuts", true)
    val homeRecentlyPlayed = bool("homeRecent", true)
    val homeTopArtists = bool("homeTopArtists", true)
    val homeOnRepeat = bool("homeOnRepeat", true)
    val homePodcasts = bool("homePodcasts", true)
    val libraryGrid = bool("libGrid", false)
    val denserRows = bool("denserRows", false)
    /** 0 = recents, 1 = recently added, 2 = alphabetical, 3 = creator */
    val librarySort = int("libSort", 0)
    val hideFilterChips = bool("hideFilterChips", false)

    // ---- Player page ----
    val canvas = bool("canvas", true)
    val fluidArtwork = bool("fluidArtwork", true)
    val lowPowerPausesAnimations = bool("lowPowerPause", true)
    /** 0 = off, 1 = seek, 2 = skip track */
    val doubleTapCover = int("doubleTap", 1)
    val seekStepSeconds = int("seekStep", 10)

    // ---- Lyrics ----
    val spicyKey = str("spicyKey", "")
    val spicyShowSource = bool("spicyShowSource", true)
    /** Ask sources top to bottom until one has word timing (Chroma's behaviour). */
    val preferWordTiming = bool("preferWordTiming", true)
    val simulateWordTiming = bool("simulateWordTiming", true)
    val enableSpicy = bool("srcSpicy", true)
    val enableBini = bool("srcBini", true)
    val enableMusixmatch = bool("srcMusixmatch", true)
    val enableUnison = bool("srcUnison", true)
    val enableNetease = bool("srcNetease", true)
    val enableKugouKrc = bool("srcKugouKrc", true)
    val translationLanguage = str("translationLang", "")

    // ---- Live Activity & lock screen ----
    val liveActivity = bool("liveActivity", true)
    val liveActivityTranslation = bool("liveActivityTranslation", false)
    /** 0 = left, 1 = center, 2 = right */
    val wallpaperAlign = int("wpAlign", 0)

    // ---- Music Haptics ----
    /** 0 = kick and snare with bass rumble, 1 = kick and snare, no rumble, 2 = kick with bass rumble */
    val hapticMode = int("hMode", 0)
    val hapticIntensity = float("hInt", 0.8f)

    // ---- Sleep timer ----
    val sleepFade = bool("sleepFade", true)
    val sleepOneMinute = bool("sleepOneMinute", true)

    // ---- Audio effects (applied to Spotify's audio session) ----
    val fxEnabled = bool("fxEnabled", false)
    /** Falls back to the phone's whole output when Spotify hasn't handed over its audio session. */
    val fxGlobal = bool("fxGlobal", true)
    val fxEqOn = bool("fxEqOn", false)
    /** 10-band gains in dB*10, comma separated */
    val fxEq = str("fxEq", "0,0,0,0,0,0,0,0,0,0")
    val fxEqName = str("fxEqName", "Flat")
    val fxGraphicEqOn = bool("fxGraphicEqOn", false)
    val fxAutoEqName = str("fxAutoEqName", "")
    /** A GraphicEQ line: "GraphicEQ: 20 -0.3; 21 -0.3; ..." */
    val fxAutoEqCurve = str("fxAutoEqCurve", "")
    val fxBassOn = bool("fxBassOn", false)
    /** Bass boost's maximum gain in dB */
    val fxBassBoost = int("fxBass", 6)
    val fxWideningOn = bool("fxWideningOn", false)
    /** 0..100 */
    val fxWidening = int("fxWidening", 50)
    /** -1 = off, else index of the room */
    val fxReverb = int("fxReverb", -1)
    val fxPostGain = float("fxPostGain", 0f)
    val fxLimiterThreshold = float("fxLimiterThreshold", -1f)
    val fxLimiterRelease = float("fxLimiterRelease", 60f)
    /** 0 = off, 1 = evens out, 2 = livens up, 3 = both */
    val fxCompander = int("fxCompander", 0)
    /** Saved presets, a JSON array of {name, settings} */
    val fxPresets = str("fxPresets", "[]")

    // ---- Privacy & clutter ----
    val keepHistory = bool("keepHistory", true)
    /** Fingerprints of Spotify export files already imported, so a second import doesn't double count. */
    val importedHistory = str("importedHistory", "")
}
