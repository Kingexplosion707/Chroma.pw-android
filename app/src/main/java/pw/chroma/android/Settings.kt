package pw.chroma.android

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** A persisted, Compose-observable preference. */
abstract class Pref<T>(val key: String, val def: T) : ReadWriteProperty<Any?, T> {
    val state = mutableStateOf(def)
    abstract fun load(sp: SharedPreferences): T
    abstract fun save(e: SharedPreferences.Editor, v: T)
    override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        state.value = value
        Settings.sp?.edit()?.also { save(it, value) }?.apply()
    }
}

/** Every Chroma setting, grouped like the iOS settings pages. */
object Settings {
    internal var sp: SharedPreferences? = null
    private val all = mutableListOf<Pref<*>>()

    private fun bool(k: String, d: Boolean) = object : Pref<Boolean>(k, d) {
        override fun load(sp: SharedPreferences) = sp.getBoolean(k, d)
        override fun save(e: SharedPreferences.Editor, v: Boolean) { e.putBoolean(k, v) }
    }.also { all += it }
    private fun int(k: String, d: Int) = object : Pref<Int>(k, d) {
        override fun load(sp: SharedPreferences) = sp.getInt(k, d)
        override fun save(e: SharedPreferences.Editor, v: Int) { e.putInt(k, v) }
    }.also { all += it }
    private fun float(k: String, d: Float) = object : Pref<Float>(k, d) {
        override fun load(sp: SharedPreferences) = sp.getFloat(k, d)
        override fun save(e: SharedPreferences.Editor, v: Float) { e.putFloat(k, v) }
    }.also { all += it }
    private fun str(k: String, d: String) = object : Pref<String>(k, d) {
        override fun load(sp: SharedPreferences) = sp.getString(k, d) ?: d
        override fun save(e: SharedPreferences.Editor, v: String) { e.putString(k, v) }
    }.also { all += it }

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.applicationContext.getSharedPreferences("chroma", Context.MODE_PRIVATE)
        sp = p
        @Suppress("UNCHECKED_CAST")
        all.forEach { (it as Pref<Any?>).state.value = it.load(p) }
    }

    /** Resets every preference in [keys] (or all) to its default. */
    fun reset(keys: Set<String>? = null) {
        val e = sp?.edit() ?: return
        @Suppress("UNCHECKED_CAST")
        all.filter { keys == null || it.key in keys }.forEach { (it as Pref<Any?>).also { p -> p.state.value = p.def; e.remove(p.key) } }
        e.apply()
    }

    // ---------- Looks: theme ----------
    var accent by int("accent", 0xFF1ED760.toInt())
    var amoled by bool("amoled", true)
    // ---------- Looks: glass ----------
    /** 0 = clear, 1 = frosted, 2 = tinted */
    var glassStyle by int("glassStyle", 0)
    var glassTabBar by bool("glassTabBar", true)
    var glassNowPlayingBar by bool("glassNpb", true)
    var glassSearch by bool("glassSearch", true)
    var glassHome by bool("glassHome", true)
    var glassPlaylist by bool("glassPlaylist", true)
    var glassLyrics by bool("glassLyrics", true)
    // ---------- Looks: home ----------
    /** 0 = gradient, 1 = solid, 2 = off */
    var homeGradientStyle by int("hgStyle", 0)
    /** 0 = subtle, 1 = medium, 2 = bold */
    var homeGradientStrength by int("hgStrength", 1)
    /** 0 = short, 1 = medium, 2 = tall, 3 = full screen */
    var homeGradientHeight by int("hgHeight", 1)
    var gradientHome by bool("hgHome", true)
    var gradientSearch by bool("hgSearch", false)
    var gradientLibrary by bool("hgLibrary", false)
    var homePills by bool("homePills", true)
    var homeShortcuts by bool("homeShortcuts", true)
    // ---------- Looks: tab bar ----------
    var tabHideLabels by bool("tabHideLabels", false)
    /** Comma-separated tab ids in order; ids not present are hidden. */
    var tabOrder by str("tabOrder", "home,search,library")
    var launchTab by str("launchTab", "home")
    /** Custom tabs as "Title|spotify:uri" joined by newlines. */
    var customTabs by str("customTabs", "")
    // ---------- Looks: now playing bar ----------
    var npbRoundArtwork by bool("npbRound", false)
    var npbAlbumTint by bool("npbTint", true)
    var npbTwoLines by bool("npbTwoLines", true)
    var npbHideConnect by bool("npbHideConnect", false)
    var npbSaveButton by bool("npbSave", true)
    // ---------- Looks: player ----------
    var playerBackdrop by bool("playerBackdrop", true)
    var canvas by bool("canvas", true)
    var roundArtwork by bool("round", true)
    /** 0 = off, 1 = seek 10s, 2 = skip track */
    var doubleTap by int("doubleTap", 1)
    /** 0 = left/right, 1 = left/center/right (center toggles play) */
    var playerZones by int("playerZones", 0)
    var haptics by bool("haptics", true)
    /** Comma-separated hidden player parts. */
    var playerHidden by str("playerHidden", "")
    // ---------- Looks: playlists & albums ----------
    var playlistFullCover by bool("plFullCover", false)
    var playlistDividers by bool("plDividers", false)
    var playlistHideFind by bool("plHideFind", false)
    var playlistCreator by bool("plCreator", true)
    var playlistDescription by bool("plDescription", true)
    var playlistMetadata by bool("plMetadata", true)
    // ---------- Library ----------
    var libraryGrid by bool("libGrid", false)
    var denserRows by bool("denserRows", false)
    /** 0 = recents, 1 = recently added, 2 = alphabetical, 3 = creator */
    var librarySort by int("libSort", 0)
    // ---------- Lyrics ----------
    var wordSync by bool("wordSync", true)
    /** 0 = left, 1 = center, 2 = right */
    var lyricsAlign by int("align", 0)
    var lyricsBlur by float("lyricsBlur", 0.6f)
    var lyricsOriginalColors by bool("lyricsOrigColors", true)
    var lyricsStaticColor by bool("lyricsStatic", false)
    var lyricsColor by int("lyricsColor", 0xFF3A2A5A.toInt())
    var hideLyricsOnError by bool("hideLyricsOnError", false)
    // ---------- Listening ----------
    var sleepFade by bool("fade", true)
    var sleepOneMinute by bool("sleepOneMinute", true)
    var shakeRandom by bool("shakeRandom", false)
    var cleanShareLinks by bool("cleanShare", true)
    // ---------- Canvas & wallpaper ----------
    var spDc by str("spDc", "")
    var canvasUrl by str("canvasUrl", "")
    var wallpaperVideo by bool("wpVideo", true)
    // ---------- Music haptics ----------
    /** 0 = bass pulse, 1 = beat, 2 = beat + bass */
    var hapticMode by int("hMode", 2)
    var hapticIntensity by float("hInt", 0.8f)
    // ---------- App icon ----------
    var icon by str("icon", "icon_chroma")

    fun isHidden(part: String) = part in playerHidden.split(',')
    fun setHidden(part: String, hidden: Boolean) {
        val s = playerHidden.split(',').filter { it.isNotBlank() }.toMutableSet()
        if (hidden) s += part else s -= part
        playerHidden = s.joinToString(",")
    }
}
