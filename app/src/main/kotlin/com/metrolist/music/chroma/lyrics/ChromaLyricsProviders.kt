package com.metrolist.music.chroma.lyrics

import android.content.Context
import android.util.Base64
import com.metrolist.music.betterlyrics.TTMLParser
import com.metrolist.music.chroma.ChromaPrefs
import com.metrolist.music.lyrics.LyricsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.Inflater
import kotlin.math.abs

/**
 * Chroma's lyrics sources, in the order the iOS mod asks them: Spicy Lyrics, BiniLyrics (Apple Music
 * timing), Musixmatch, Unison, NetEase, KuGou, then the open fallbacks. Each returns Metrolist's
 * lyrics text: an LRC line followed by a `<word:start:end|...>` line when it has word timing.
 * Sources only get the track, never the account.
 */
internal object LyricsOut {
    data class W(val text: String, val startS: Double, val endS: Double)
    data class L(val startMs: Long, val text: String, val words: List<W> = emptyList(), val agent: String? = null, val background: Boolean = false)

    fun time(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        return "[%02d:%02d.%02d]".format(t / 60000, (t % 60000) / 1000, (t % 1000) / 10)
    }

    fun build(lines: List<L>): String = buildString {
        lines.filter { it.text.isNotBlank() || it.words.isNotEmpty() }.forEach { l ->
            append(time(l.startMs))
            when {
                l.background -> append("{bg}")
                l.agent != null -> append("{agent:").append(l.agent).append('}')
            }
            append(l.text.replace('\n', ' ').trim()).append('\n')
            if (l.words.isNotEmpty()) {
                append('<')
                append(l.words.joinToString("|") { w -> "${w.text.replace('|', '/').trim()}:${"%.3f".format(java.util.Locale.US, w.startS)}:${"%.3f".format(java.util.Locale.US, w.endS)}" })
                append(">\n")
            }
        }
    }

    fun isWordTimed(s: String): Boolean =
        s.lineSequence().any { it.startsWith("<") && it.endsWith(">") && it.contains(':') } ||
            Regex("<\\d{1,2}:\\d{2}\\.\\d{2,3}>").containsMatchIn(s)

    private val lrcLine = Regex("^((?:\\[\\d{1,3}:\\d{2}(?:\\.\\d{1,3})?])+)(.*)$")
    private val lrcTime = Regex("\\[(\\d{1,3}):(\\d{2})(?:\\.(\\d{1,3}))?]")

    /**
     * Chroma's "Simulate word timing": spreads each line's time over its words by length, capped
     * so long instrumental gaps don't stretch the last words.
     */
    fun simulateWordTiming(lrc: String): String {
        if (isWordTimed(lrc)) return lrc
        val parsed = mutableListOf<Pair<Long, String>>()
        lrc.lineSequence().forEach { raw ->
            val m = lrcLine.matchEntire(raw.trim()) ?: return@forEach
            val text = m.groupValues[2]
            lrcTime.findAll(m.groupValues[1]).forEach { t ->
                val frac = t.groupValues[3].let { if (it.isEmpty()) 0L else (it + "00").take(3).toLong() }
                parsed += (t.groupValues[1].toLong() * 60_000 + t.groupValues[2].toLong() * 1000 + frac) to text
            }
        }
        if (parsed.isEmpty()) return lrc
        parsed.sortBy { it.first }
        val out = parsed.mapIndexed { i, (start, text) ->
            val next = parsed.getOrNull(i + 1)?.first ?: (start + 5000)
            val parts = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.isEmpty()) return@mapIndexed L(start, text)
            val weights = parts.map { it.length + 1.5 }
            val total = weights.sum()
            val sung = minOf((next - start) * 0.92, maxOf(900.0, total * 85.0))
            var t = start.toDouble()
            val words = parts.mapIndexed { k, p ->
                val d = sung * weights[k] / total
                W(p, t / 1000.0, (t + d) / 1000.0).also { t += d }
            }
            L(start, text, words)
        }
        return build(out)
    }
}

internal object Http {
    fun get(url: String, headers: Map<String, String> = emptyMap(), timeout: Int = 8000): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeout; c.readTimeout = timeout
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        return try {
            val code = c.responseCode
            code to ((if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty())
        } finally { c.disconnect() }
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

private fun isSpotifyTrackId(id: String) = id.length == 22 && id.all { it.isLetterOrDigit() }

private fun firstArtist(artist: String) = artist.split(",", "&", " feat", " ft.").first().trim()

// ---------------- Spicy Lyrics ----------------
object SpicyLyricsProvider : LyricsProvider {
    override val name = "SpicyLyrics"
    override fun isEnabled(context: Context) = ChromaPrefs.enableSpicy.value && ChromaPrefs.spicyKey.value.startsWith("sl_pk_")

    override suspend fun getLyrics(context: Context, id: String, title: String, artist: String, duration: Int, album: String?): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(isSpotifyTrackId(id)) { "Spicy Lyrics needs a Spotify track" }
                val key = ChromaPrefs.spicyKey.value
                var (code, body) = Http.get("https://api.spicylyrics.org/v1/lyrics/$id", mapOf("Authorization" to "Bearer $key"))
                if (code == 401 && body.contains("malformed")) { val r = Http.get("https://api.spicylyrics.org/v1/lyrics/$id", mapOf("Authorization" to key)); code = r.first; body = r.second }
                check(code == 200) { "Spicy Lyrics: HTTP $code" }
                val root = JSONObject(body)
                val o = root.optJSONObject("Body")?.takeIf { it.has("Type") || it.has("Content") } ?: root
                parse(o)
            }
        }

    private fun words(sylls: JSONArray?): List<LyricsOut.W> {
        sylls ?: return emptyList()
        val out = mutableListOf<LyricsOut.W>()
        var text = StringBuilder(); var start = 0.0; var end = 0.0; var open = false
        for (i in 0 until sylls.length()) {
            val s = sylls.optJSONObject(i) ?: continue
            val t = s.optString("Text")
            if (!open) { start = s.optDouble("StartTime"); open = true }
            text.append(t)
            end = s.optDouble("EndTime")
            if (!s.optBoolean("IsPartOfWord", false)) {
                if (text.isNotBlank()) out += LyricsOut.W(text.toString().trim(), start, end)
                text = StringBuilder(); open = false
            }
        }
        if (open && text.isNotBlank()) out += LyricsOut.W(text.toString().trim(), start, end)
        return out
    }

    private fun parse(o: JSONObject): String {
        val type = o.optString("Type")
        val content = o.optJSONArray("Content")
        if (type.equals("Static", true) || content == null) {
            val lines = o.optJSONArray("Lines") ?: error("No lyrics")
            return (0 until lines.length()).joinToString("\n") { lines.getJSONObject(it).optString("Text") }
        }
        val duet = (0 until content.length()).any { content.optJSONObject(it)?.optBoolean("OppositeAligned") == true }
        val out = mutableListOf<LyricsOut.L>()
        for (i in 0 until content.length()) {
            val c = content.optJSONObject(i) ?: continue
            val agent = if (duet) (if (c.optBoolean("OppositeAligned")) "v2" else "v1") else null
            if (type.equals("Syllable", true)) {
                val lead = c.optJSONObject("Lead") ?: continue
                val w = words(lead.optJSONArray("Syllables"))
                val start = ((lead.optDouble("StartTime", w.firstOrNull()?.startS ?: 0.0)) * 1000).toLong()
                out += LyricsOut.L(start, w.joinToString(" ") { it.text }, w, agent)
                c.optJSONArray("Background")?.let { bgs ->
                    for (b in 0 until bgs.length()) {
                        val bg = bgs.optJSONObject(b) ?: continue
                        val bw = words(bg.optJSONArray("Syllables"))
                        if (bw.isNotEmpty()) out += LyricsOut.L((bw.first().startS * 1000).toLong(), bw.joinToString(" ") { it.text }, bw, background = true)
                    }
                }
            } else {
                out += LyricsOut.L((c.optDouble("StartTime") * 1000).toLong(), c.optString("Text"), agent = agent)
            }
        }
        check(out.isNotEmpty()) { "No lyrics" }
        return LyricsOut.build(out.sortedBy { it.startMs })
    }
}

// ---------------- BiniLyrics (Apple Music word timing) ----------------
object BiniLyricsProvider : LyricsProvider {
    override val name = "BiniLyrics"
    override fun isEnabled(context: Context) = ChromaPrefs.enableBini.value

    override suspend fun getLyrics(context: Context, id: String, title: String, artist: String, duration: Int, album: String?): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://lyrics-api.binimum.org/?track=${Http.enc(title)}&artist=${Http.enc(firstArtist(artist))}" +
                    (album?.takeIf { it.isNotBlank() }?.let { "&album=${Http.enc(it)}" } ?: "") + (if (duration > 0) "&duration=$duration" else "")
                val (code, body) = Http.get(url)
                check(code == 200) { "BiniLyrics: HTTP $code" }
                val results = JSONObject(body).optJSONArray("results") ?: error("No results")
                val candidates = (0 until results.length()).mapNotNull { results.optJSONObject(it) }
                    .filter { duration <= 0 || it.optInt("duration", duration).let { d -> abs(d - duration) <= 5 } }
                val best = candidates.firstOrNull { it.optString("timing_type") == "word" } ?: candidates.firstOrNull() ?: error("No match")
                val (c2, ttml) = Http.get(best.getString("lyricsUrl"))
                check(c2 == 200 && ttml.contains("<tt")) { "BiniLyrics: no TTML" }
                val parsed = TTMLParser.parseTTML(ttml)
                check(parsed.isNotEmpty()) { "BiniLyrics: empty" }
                TTMLParser.toLRC(parsed)
            }
        }
}

// ---------------- Unison (hand-timed) ----------------
object UnisonProvider : LyricsProvider {
    override val name = "Unison"
    override fun isEnabled(context: Context) = ChromaPrefs.enableUnison.value

    override suspend fun getLyrics(context: Context, id: String, title: String, artist: String, duration: Int, album: String?): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://unison.boidu.dev/lyrics?song=${Http.enc(title)}&artist=${Http.enc(firstArtist(artist))}" +
                    (album?.takeIf { it.isNotBlank() }?.let { "&album=${Http.enc(it)}" } ?: "") + (if (duration > 0) "&duration=$duration" else "")
                val (code, body) = Http.get(url)
                check(code == 200) { "Unison: HTTP $code" }
                val data = JSONObject(body).optJSONObject("data") ?: error("Unison: no data")
                val lyrics = data.optString("lyrics")
                check(lyrics.isNotBlank()) { "Unison: empty" }
                if (data.optString("format").equals("ttml", true) || lyrics.trimStart().startsWith("<")) {
                    val parsed = TTMLParser.parseTTML(lyrics)
                    check(parsed.isNotEmpty()) { "Unison: unreadable" }
                    TTMLParser.toLRC(parsed)
                } else lyrics
            }
        }
}

// ---------------- Musixmatch (richsync) ----------------
object MusixmatchProvider : LyricsProvider {
    override val name = "Musixmatch"
    private const val BASE = "https://apic-appmobile.musixmatch.com/ws/1.1/"
    private const val APP = "mac-ios-v2.0"
    private val headers = mapOf("User-Agent" to "Musixmatch/2025120901 CFNetwork/3860.300.31 Darwin/25.2.0", "x-mxm-app-version" to "2025120901")
    @Volatile private var token: String? = null

    override fun isEnabled(context: Context) = ChromaPrefs.enableMusixmatch.value

    private fun token(context: Context, fresh: Boolean = false): String {
        val prefs = context.getSharedPreferences("chroma_mxm", Context.MODE_PRIVATE)
        if (!fresh) (token ?: prefs.getString("token", null))?.let { token = it; return it }
        val (code, body) = Http.get("${BASE}token.get?app_id=$APP&format=json", headers)
        check(code == 200) { "Musixmatch: HTTP $code" }
        val t = JSONObject(body).getJSONObject("message").optJSONObject("body")?.optString("user_token")
        check(!t.isNullOrBlank() && !t.contains("UpgradeOnly")) { "Musixmatch: no token" }
        token = t
        prefs.edit().putString("token", t).apply()
        return t
    }

    override suspend fun getLyrics(context: Context, id: String, title: String, artist: String, duration: Int, album: String?): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                var result = macro(context, id, title, artist, duration, token(context))
                if (result == null) result = macro(context, id, title, artist, duration, token(context, fresh = true))
                result ?: error("Musixmatch: nothing")
            }
        }

    private fun macro(context: Context, id: String, title: String, artist: String, duration: Int, tok: String): String? {
        val q = StringBuilder("${BASE}macro.subtitles.get?app_id=$APP&format=json&namespace=lyrics_richsynched&subtitle_format=mxm&optional_calls=track.richsync&usertoken=${Http.enc(tok)}")
        if (isSpotifyTrackId(id)) q.append("&track_spotify_id=").append(id)
        else q.append("&q_track=").append(Http.enc(title)).append("&q_artist=").append(Http.enc(firstArtist(artist))).append(if (duration > 0) "&q_duration=$duration" else "")
        val (code, body) = Http.get(q.toString(), headers)
        if (code != 200) return null
        val msg = JSONObject(body).getJSONObject("message")
        val status = msg.optJSONObject("header")?.optInt("status_code")
        if (status == 401) return null
        val calls = msg.optJSONObject("body")?.optJSONObject("macro_calls") ?: error("Musixmatch: no match")
        fun call(n: String): JSONObject? = calls.optJSONObject(n)?.optJSONObject("message")
        val track = call("matcher.track.get")?.optJSONObject("body")?.optJSONObject("track") ?: error("Musixmatch: no match")
        if (track.optInt("instrumental") == 1) error("Musixmatch: instrumental")

        call("track.richsync.get")?.optJSONObject("body")?.optJSONObject("richsync")?.optString("richsync_body")?.takeIf { it.isNotBlank() }?.let { rb ->
            val arr = JSONArray(rb)
            val lines = (0 until arr.length()).mapNotNull { i ->
                val l = arr.optJSONObject(i) ?: return@mapNotNull null
                val ts = l.optDouble("ts"); val te = l.optDouble("te")
                val chunks = l.optJSONArray("l") ?: JSONArray()
                val words = mutableListOf<LyricsOut.W>()
                for (k in 0 until chunks.length()) {
                    val c = chunks.optJSONObject(k) ?: continue
                    val text = c.optString("c")
                    if (text.isBlank()) continue
                    val start = ts + c.optDouble("o")
                    val nextStart = (k + 1 until chunks.length()).asSequence().mapNotNull { chunks.optJSONObject(it) }
                        .firstOrNull { it.optString("c").isNotBlank() }?.let { ts + it.optDouble("o") } ?: te
                    words += LyricsOut.W(text.trim(), start, maxOf(start + 0.05, nextStart))
                }
                LyricsOut.L((ts * 1000).toLong(), l.optString("x"), words)
            }
            if (lines.isNotEmpty()) return LyricsOut.build(lines)
        }
        call("track.subtitles.get")?.optJSONObject("body")?.optJSONArray("subtitle_list")?.optJSONObject(0)
            ?.optJSONObject("subtitle")?.optString("subtitle_body")?.takeIf { it.isNotBlank() }?.let { sb ->
                val arr = JSONArray(sb)
                val lines = (0 until arr.length()).mapNotNull { i ->
                    val l = arr.optJSONObject(i) ?: return@mapNotNull null
                    LyricsOut.L((l.optJSONObject("time")?.optDouble("total") ?: 0.0).times(1000).toLong(), l.optString("text"))
                }
                if (lines.isNotEmpty()) return LyricsOut.build(lines)
            }
        return call("track.lyrics.get")?.optJSONObject("body")?.optJSONObject("lyrics")?.optString("lyrics_body")?.takeIf { it.isNotBlank() }
            ?: error("Musixmatch: no lyrics")
    }
}

// ---------------- NetEase (yrc word timing) ----------------
object NetEaseProvider : LyricsProvider {
    override val name = "NetEase"
    private val headers = mapOf("Referer" to "https://music.163.com", "User-Agent" to "Mozilla/5.0")
    private val yrcLine = Regex("^\\[(\\d+),(\\d+)](.*)$")
    private val yrcWord = Regex("\\((\\d+),(\\d+),-?\\d+\\)([^(]*)")

    override fun isEnabled(context: Context) = ChromaPrefs.enableNetease.value

    override suspend fun getLyrics(context: Context, id: String, title: String, artist: String, duration: Int, album: String?): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val (code, body) = Http.get("https://music.163.com/api/search/get?s=${Http.enc("$title ${firstArtist(artist)}")}&type=1&limit=10", headers)
                check(code == 200) { "NetEase: HTTP $code" }
                val songs = JSONObject(body).optJSONObject("result")?.optJSONArray("songs") ?: error("NetEase: no results")
                val list = (0 until songs.length()).mapNotNull { songs.optJSONObject(it) }
                val song = list.firstOrNull { s -> s.optString("name").equals(title, true) && (duration <= 0 || abs(s.optLong("duration") / 1000 - duration) <= 4) }
                    ?: list.firstOrNull { s -> duration > 0 && abs(s.optLong("duration") / 1000 - duration) <= 3 }
                    ?: error("NetEase: no match")
                val (c2, lyr) = Http.get("https://music.163.com/api/song/lyric/v1?id=${song.optLong("id")}&lv=-1&kv=-1&tv=-1&rv=-1&yv=-1&ytv=-1&yrv=-1", headers)
                check(c2 == 200) { "NetEase: HTTP $c2" }
                val o = JSONObject(lyr)
                val yrc = o.optJSONObject("yrc")?.optString("lyric").orEmpty()
                if (yrc.isNotBlank()) {
                    val lines = yrc.lineSequence().mapNotNull { raw ->
                        val m = yrcLine.matchEntire(raw.trim()) ?: return@mapNotNull null
                        val words = yrcWord.findAll(m.groupValues[3]).mapNotNull { w ->
                            val text = w.groupValues[3]
                            if (text.isBlank()) null else LyricsOut.W(text.trim(), w.groupValues[1].toLong() / 1000.0, (w.groupValues[1].toLong() + w.groupValues[2].toLong()) / 1000.0)
                        }.toList()
                        if (words.isEmpty()) null else LyricsOut.L(m.groupValues[1].toLong(), words.joinToString(" ") { it.text }, words)
                    }.toList()
                    if (lines.isNotEmpty()) return@runCatching LyricsOut.build(lines)
                }
                o.optJSONObject("lrc")?.optString("lyric")?.takeIf { it.contains("[") } ?: error("NetEase: no lyrics")
            }
        }
}

// ---------------- KuGou (krc word timing) ----------------
object KuGouKrcProvider : LyricsProvider {
    override val name = "KuGouKRC"
    private val key = byteArrayOf(64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, -50, -46, 110, 105)
    private val krcLine = Regex("^\\[(\\d+),(\\d+)](.*)$")
    private val krcWord = Regex("<(\\d+),(\\d+),-?\\d+>([^<]*)")

    override fun isEnabled(context: Context) = ChromaPrefs.enableKugouKrc.value

    override suspend fun getLyrics(context: Context, id: String, title: String, artist: String, duration: Int, album: String?): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val kw = Http.enc("${firstArtist(artist)} - $title")
                val (code, body) = Http.get("https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=$kw&duration=${duration * 1000}&hash=")
                check(code == 200) { "KuGou: HTTP $code" }
                val cands = JSONObject(body).optJSONArray("candidates") ?: error("KuGou: no results")
                val list = (0 until cands.length()).mapNotNull { cands.optJSONObject(it) }
                val best = list.firstOrNull { duration <= 0 || abs(it.optLong("duration") / 1000 - duration) <= 4 } ?: error("KuGou: no match")
                val (c2, dl) = Http.get("https://lyrics.kugou.com/download?ver=1&client=pc&id=${best.optString("id")}&accesskey=${best.optString("accesskey")}&fmt=krc&charset=utf8")
                check(c2 == 200) { "KuGou: HTTP $c2" }
                val raw = Base64.decode(JSONObject(dl).optString("content"), Base64.DEFAULT)
                check(raw.size > 4 && String(raw, 0, 4) == "krc1") { "KuGou: not krc" }
                val enc = ByteArray(raw.size - 4) { i -> (raw[i + 4].toInt() xor key[i % 16].toInt()).toByte() }
                val inflater = Inflater().apply { setInput(enc) }
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (!inflater.finished()) { val n = inflater.inflate(buf); if (n == 0 && inflater.needsInput()) break; out.write(buf, 0, n) }
                inflater.end()
                val text = out.toString("UTF-8")
                val lines = text.lineSequence().mapNotNull { rawLine ->
                    val m = krcLine.matchEntire(rawLine.trim()) ?: return@mapNotNull null
                    val start = m.groupValues[1].toLong()
                    val words = krcWord.findAll(m.groupValues[3]).mapNotNull { w ->
                        val t = w.groupValues[3]
                        if (t.isBlank()) null else {
                            val ws = start + w.groupValues[1].toLong()
                            LyricsOut.W(t.trim(), ws / 1000.0, (ws + w.groupValues[2].toLong()) / 1000.0)
                        }
                    }.toList()
                    if (words.isEmpty()) null else LyricsOut.L(start, words.joinToString(" ") { it.text }, words)
                }.toList()
                check(lines.isNotEmpty()) { "KuGou: empty" }
                LyricsOut.build(lines)
            }
        }
}
