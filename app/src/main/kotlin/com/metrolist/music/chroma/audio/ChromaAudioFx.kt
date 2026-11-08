package com.metrolist.music.chroma.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.metrolist.music.chroma.ChromaPrefs
import com.metrolist.music.chroma.spotify.SPOTIFY_PKG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Chroma's audio effects for the Spotify app. Spotify hands its audio session to equalizers when it
 * starts playing; the effects attach to that session, or to the phone's whole output when Spotify
 * hasn't handed one over. Equalizer, Graphic EQ (AutoEq), bass boost, compander, gain and limiter
 * run in one DynamicsProcessing chain; rooms and widening use Android's reverb and virtualizer.
 */
object ChromaAudioFx {
    private const val TAG = "ChromaFx"
    const val BANDS = 32
    val EQ_FREQS = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    val ROOMS = listOf("Small room", "Medium room", "Large room", "Small hall", "Large hall")
    private val ROOM_PRESETS = shortArrayOf(
        PresetReverb.PRESET_SMALLROOM, PresetReverb.PRESET_MEDIUMROOM, PresetReverb.PRESET_LARGEROOM,
        PresetReverb.PRESET_MEDIUMHALL, PresetReverb.PRESET_LARGEHALL,
    )

    /** Band centres, a third of an octave apart from 20 Hz to 20 kHz. */
    private val centres = FloatArray(BANDS) { i -> (20.0 * 10.0.pow(3.0 * i / (BANDS - 1))).toFloat() }
    private val edges = FloatArray(BANDS) { i -> if (i == BANDS - 1) 20000f else sqrt(centres[i] * centres[i + 1]) }

    /** What the effects are attached to, shown under the switch. */
    val status = mutableStateOf("Off")

    private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val sessions = LinkedHashSet<Int>()
    private val chains = HashMap<Int, Chain>()

    private class Chain(val session: Int) {
        var dp: DynamicsProcessing? = null
        var virtualizer: Virtualizer? = null
        var reverb: PresetReverb? = null
        var failed = false

        fun release() {
            runCatching { dp?.release() }
            runCatching { virtualizer?.release() }
            runCatching { reverb?.release() }
            dp = null; virtualizer = null; reverb = null
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onSessionIntent(context, intent)
    }

    fun init(ctx: Context) {
        if (app != null) return
        val a = ctx.applicationContext
        app = a
        ChromaPrefs.init(a)
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        runCatching { ContextCompat.registerReceiver(a, receiver, filter, ContextCompat.RECEIVER_EXPORTED) }
        apply()
    }

    /** Spotify opening or closing its audio session for equalizers. */
    fun onSessionIntent(ctx: Context, intent: Intent) {
        if (app == null) init(ctx)
        val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)
        val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        if (session <= 0 || (pkg != null && pkg != SPOTIFY_PKG)) return
        main.post {
            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> sessions += session
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                    sessions -= session
                    chains.remove(session)?.release()
                }
            }
            apply()
        }
    }

    /** Applies the current settings. Cheap enough to call on every slider move. */
    fun apply() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { apply() }; return }
        val on = ChromaPrefs.fxEnabled.value
        val targets: Set<Int> = when {
            !on -> emptySet()
            sessions.isNotEmpty() -> sessions.toSet()
            ChromaPrefs.fxGlobal.value -> setOf(0)
            else -> emptySet()
        }
        chains.keys.filter { it !in targets }.forEach { chains.remove(it)?.release() }
        targets.forEach { s -> configure(chains.getOrPut(s) { Chain(s) }) }
        status.value = when {
            !on -> "Off"
            chains.values.any { it.failed } -> "This phone refused the effects. Try pausing and playing in Spotify."
            sessions.isNotEmpty() -> "On Spotify's audio"
            targets.contains(0) -> "On all audio, until Spotify hands over its own. Pause and play in Spotify to hand it over."
            else -> "Waiting for Spotify to play"
        }
    }

    private fun configure(ch: Chain) {
        ch.failed = false
        if (ch.dp == null) {
            ch.dp = runCatching {
                val cfg = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                    true, BANDS, true, 3, false, 0, true,
                ).setPreferredFrameDuration(10f).build()
                DynamicsProcessing(0, ch.session, cfg)
            }.onFailure { Log.w(TAG, "DynamicsProcessing on ${ch.session}", it) }.getOrNull()
        }
        val dp = ch.dp
        if (dp == null) ch.failed = true else runCatching {
            val gains = curve()
            val eq = DynamicsProcessing.Eq(true, true, BANDS)
            for (i in 0 until BANDS) eq.setBand(i, DynamicsProcessing.EqBand(true, edges[i], gains[i]))
            dp.setPreEqAllChannelsTo(eq)
            dp.setMbcAllChannelsTo(compander())
            dp.setLimiterAllChannelsTo(
                DynamicsProcessing.Limiter(
                    true, true, 0, 1f,
                    ChromaPrefs.fxLimiterRelease.value.coerceIn(10f, 1000f), 10f,
                    ChromaPrefs.fxLimiterThreshold.value.coerceIn(-30f, 0f),
                    ChromaPrefs.fxPostGain.value.coerceIn(-15f, 15f),
                ),
            )
            dp.setInputGainAllChannelsTo(0f)
            dp.setEnabled(true)
        }.onFailure { Log.w(TAG, "configure", it); ch.failed = true }

        val widen = ChromaPrefs.fxWideningOn.value && ChromaPrefs.fxWidening.value > 0
        if (widen) {
            val v = ch.virtualizer ?: runCatching { Virtualizer(0, ch.session) }.getOrNull()?.also { ch.virtualizer = it }
            v?.let { runCatching { it.setStrength((ChromaPrefs.fxWidening.value * 10).coerceIn(0, 1000).toShort()); it.setEnabled(true) } }
        } else {
            runCatching { ch.virtualizer?.release() }; ch.virtualizer = null
        }

        val room = ChromaPrefs.fxReverb.value
        if (room in ROOM_PRESETS.indices) {
            val r = ch.reverb ?: runCatching { PresetReverb(0, ch.session) }.getOrNull()?.also { ch.reverb = it }
            r?.let { runCatching { it.setPreset(ROOM_PRESETS[room]); it.setEnabled(true) } }
        } else {
            runCatching { ch.reverb?.release() }; ch.reverb = null
        }
    }

    // ---------------- the curve ----------------
    fun eqGains(): FloatArray {
        val parts = ChromaPrefs.fxEq.value.split(',')
        return FloatArray(EQ_FREQS.size) { i -> (parts.getOrNull(i)?.trim()?.toIntOrNull() ?: 0) / 10f }
    }

    fun setEqGains(g: FloatArray, name: String) {
        ChromaPrefs.fxEq.value = g.joinToString(",") { Math.round(it * 10).toString() }
        ChromaPrefs.fxEqName.value = name
    }

    private fun logInterp(points: List<Pair<Float, Float>>, f: Float): Float {
        if (points.isEmpty()) return 0f
        if (f <= points.first().first) return points.first().second
        if (f >= points.last().first) return points.last().second
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (points[m].first <= f) lo = m else hi = m }
        val (f0, g0) = points[lo]
        val (f1, g1) = points[hi]
        val t = ((ln(f) - ln(f0)) / (ln(f1) - ln(f0))).coerceIn(0f, 1f)
        return g0 + (g1 - g0) * t
    }

    private var graphicFor = ""
    private var graphicPoints: List<Pair<Float, Float>> = emptyList()

    /** The combined gain of the Equalizer, Graphic EQ and bass boost at each band. */
    fun curve(): FloatArray {
        val eqOn = ChromaPrefs.fxEqOn.value
        val eq = eqGains().mapIndexed { i, g -> EQ_FREQS[i].toFloat() to g }
        val graphicOn = ChromaPrefs.fxGraphicEqOn.value
        if (graphicOn && graphicFor != ChromaPrefs.fxAutoEqCurve.value) {
            graphicFor = ChromaPrefs.fxAutoEqCurve.value
            graphicPoints = parseGraphicEq(graphicFor).orEmpty()
        }
        val bassOn = ChromaPrefs.fxBassOn.value
        val bassMax = ChromaPrefs.fxBassBoost.value.toFloat()
        return FloatArray(BANDS) { i ->
            val f = centres[i]
            var g = 0f
            if (eqOn) g += logInterp(eq, f)
            if (graphicOn) g += logInterp(graphicPoints, f)
            if (bassOn) {
                val t = ((ln(f) - ln(60f)) / (ln(250f) - ln(60f))).coerceIn(0f, 1f)
                g += bassMax * (1f - t * t * (3f - 2f * t))
            }
            g.coerceIn(-24f, 24f)
        }
    }

    private fun compander(): DynamicsProcessing.Mbc {
        val mode = ChromaPrefs.fxCompander.value
        val mbc = DynamicsProcessing.Mbc(true, mode in 1..3, 3)
        val cut = floatArrayOf(250f, 4000f, 20000f)
        for (i in 0 until 3) {
            mbc.setBand(i, when (mode) {
                // Evens out: gentle compression on each band, made up with gain.
                1 -> DynamicsProcessing.MbcBand(true, cut[i], 15f, 150f, 2.5f, -28f, 6f, -90f, 1f, 0f, 4f)
                // Livens up: quiet passages drop away, so the loud ones stand out.
                2 -> DynamicsProcessing.MbcBand(true, cut[i], 3f, 80f, 1f, 0f, 6f, -38f, 1.6f, 0f, 2f)
                3 -> DynamicsProcessing.MbcBand(true, cut[i], 8f, 120f, 2f, -26f, 6f, -50f, 1.4f, 0f, 4f)
                else -> DynamicsProcessing.MbcBand(false, cut[i], 1f, 60f, 1f, 0f, 0f, -90f, 1f, 0f, 0f)
            })
        }
        return mbc
    }

    /** Parses an Equalizer APO GraphicEQ line into (Hz, dB) points, or null when it isn't one. */
    fun parseGraphicEq(text: String): List<Pair<Float, Float>>? {
        val start = text.indexOf("GraphicEQ:", ignoreCase = true)
        if (start < 0) return null
        val body = text.substring(start + "GraphicEQ:".length).lineSequence().first()
        val pts = body.split(';').mapNotNull { part ->
            val nums = part.trim().split(Regex("\\s+"))
            if (nums.size < 2) null else {
                val f = nums[0].replace(',', '.').toFloatOrNull()
                val g = nums[1].replace(',', '.').toFloatOrNull()
                if (f == null || g == null || f <= 0f) null else f to g
            }
        }.sortedBy { it.first }
        return pts.takeIf { it.size >= 2 }
    }

    // ---------------- Equalizer presets (Apple Music's) ----------------
    val EQ_PRESETS: List<Pair<String, FloatArray>> = listOf(
        "Flat" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Acoustic" to floatArrayOf(5f, 4.9f, 3.95f, 1.05f, 2.15f, 1.75f, 3.5f, 4.1f, 3.55f, 2.15f),
        "Bass Booster" to floatArrayOf(5.5f, 4.25f, 3.5f, 2.5f, 1.25f, 0f, 0f, 0f, 0f, 0f),
        "Bass Reducer" to floatArrayOf(-5.5f, -4.25f, -3.5f, -2.5f, -1.25f, 0f, 0f, 0f, 0f, 0f),
        "Classical" to floatArrayOf(4.75f, 3.75f, 3f, 2.5f, -1.5f, -1.5f, 0f, 2.25f, 3.25f, 3.75f),
        "Dance" to floatArrayOf(3.57f, 6.55f, 4.99f, 0f, 1.92f, 3.65f, 5.15f, 4.54f, 3.59f, 0f),
        "Deep" to floatArrayOf(4.95f, 3.55f, 1.75f, 1f, 2.85f, 2.5f, 1.45f, -2.15f, -3.55f, -4.6f),
        "Electronic" to floatArrayOf(4.25f, 3.8f, 1.2f, 0f, -2.15f, 2.25f, 0.85f, 1.25f, 3.95f, 4.8f),
        "Hip-Hop" to floatArrayOf(5f, 4.25f, 1.5f, 3f, -1f, -1f, 1.5f, -0.5f, 2f, 3f),
        "Jazz" to floatArrayOf(4f, 3f, 1.5f, 2.25f, -1.5f, -1.5f, 0f, 1.5f, 3f, 3.75f),
        "Late Night" to floatArrayOf(4.9f, 3.2f, 0.8f, -0.5f, -2.0f, 1.3f, 2.4f, 3.2f, 2.4f, 0.8f),
        "Latin" to floatArrayOf(4.5f, 3f, 0f, 0f, -1.5f, -1.5f, -1.5f, 0f, 3f, 4.5f),
        "Loudness" to floatArrayOf(6f, 4f, 0f, 0f, -2f, 0f, -1f, -5f, 5f, 1f),
        "Lounge" to floatArrayOf(-3f, -1.5f, -0.5f, 1.5f, 4f, 2.5f, 0f, -1.5f, 2f, 1f),
        "Piano" to floatArrayOf(3f, 2f, 0f, 2.5f, 3f, 1.5f, 3.5f, 4.5f, 3f, 3.5f),
        "Pop" to floatArrayOf(-1.5f, -1f, 0f, 2f, 4f, 4f, 2f, 0f, -1f, -1.5f),
        "R&B" to floatArrayOf(2.62f, 6.92f, 5.65f, 1.33f, -2.19f, -1.5f, 2.32f, 2.65f, 3f, 3.75f),
        "Rock" to floatArrayOf(5f, 4f, 3f, 1.5f, -0.5f, -1f, 0.5f, 2.5f, 3.5f, 4.5f),
        "Small Speakers" to floatArrayOf(5.5f, 4.25f, 3.5f, 2.5f, 1.25f, 0f, -1.25f, -2.5f, -3.5f, -4.25f),
        "Spoken Word" to floatArrayOf(-3.46f, -0.47f, 0f, 0.69f, 3.46f, 4.61f, 4.84f, 4.28f, 2.54f, 0f),
        "Treble Booster" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 1.25f, 2.5f, 3.5f, 4.25f, 5.5f),
        "Treble Reducer" to floatArrayOf(0f, 0f, 0f, 0f, 0f, -1.25f, -2.5f, -3.5f, -4.25f, -5.5f),
        "Vocal Booster" to floatArrayOf(-1.5f, -3f, -3f, 1.5f, 3.75f, 3.75f, 3f, 1.5f, 0f, -1.5f),
    )

    // ---------------- presets of your own ----------------
    private fun fxKeys() = ChromaPrefs.prefs().filter {
        it.key.startsWith("fx") && it.key !in setOf("fxEnabled", "fxPresets", "fxGlobal", "fxAutoEqName", "fxAutoEqCurve", "fxGraphicEqOn")
    }

    fun presets(): List<Pair<String, JSONObject>> = runCatching {
        val a = JSONArray(ChromaPrefs.fxPresets.value)
        (0 until a.length()).map { i -> a.getJSONObject(i).let { it.getString("name") to it.getJSONObject("settings") } }
    }.getOrDefault(emptyList())

    fun savePreset(name: String) {
        val settings = JSONObject().also { o -> fxKeys().forEach { it.export(o) } }
        val list = presets().filter { it.first != name } + (name to settings)
        ChromaPrefs.fxPresets.value = JSONArray().also { a ->
            list.forEach { (n, s) -> a.put(JSONObject().put("name", n).put("settings", s)) }
        }.toString()
    }

    fun applyPreset(name: String) {
        val s = presets().firstOrNull { it.first == name }?.second ?: return
        fxKeys().forEach { it.import(s) }
        ChromaPrefs.fxEnabled.value = true
        apply()
    }

    fun deletePreset(name: String) {
        ChromaPrefs.fxPresets.value = JSONArray().also { a ->
            presets().filter { it.first != name }.forEach { (n, s) -> a.put(JSONObject().put("name", n).put("settings", s)) }
        }.toString()
    }

    /** Every effect goes off and back to its defaults. The headphone correction stays. */
    fun resetEffects() {
        fxKeys().forEach { p ->
            @Suppress("UNCHECKED_CAST")
            val q = p as com.metrolist.music.chroma.ChromaPref<Any?>
            q.value = q.def
        }
        apply()
    }

    // ---------------- AutoEq ----------------
    data class Headphone(val name: String, val path: String, val source: String)

    private const val AUTOEQ = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results"
    private var index: List<Headphone>? = null
    private val line = Regex("""^- \[(.+?)]\(\./(.+?)\)(?: by (.+))?$""")

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "Chroma")
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    /** AutoEq's list of headphones, cached for a month. */
    suspend fun headphones(ctx: Context): List<Headphone> = withContext(Dispatchers.IO) {
        index?.let { return@withContext it }
        val f = File(ctx.cacheDir, "autoeq_index.md")
        val text = if (f.exists() && System.currentTimeMillis() - f.lastModified() < 30L * 86_400_000L) f.readText()
        else get("$AUTOEQ/INDEX.md").also { runCatching { f.writeText(it) } }
        text.lineSequence().mapNotNull { l ->
            line.find(l.trim())?.let { m -> Headphone(m.groupValues[1], m.groupValues[2], m.groupValues[3]) }
        }.toList().also { index = it }
    }

    /** AutoEq's GraphicEQ correction for a headphone. */
    suspend fun correction(h: Headphone): String = withContext(Dispatchers.IO) {
        val folder = h.path.trimEnd('/')
        val text = get("$AUTOEQ/$folder/${folder.substringAfterLast('/')}%20GraphicEQ.txt")
        if (parseGraphicEq(text) == null) error("Not a GraphicEQ line")
        text.lineSequence().first { it.contains("GraphicEQ:", true) }.trim()
    }
}

/** Spotify's audio-session broadcasts, for when Chroma isn't running yet. */
class ChromaAudioFxReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = ChromaAudioFx.onSessionIntent(context, intent)
}
