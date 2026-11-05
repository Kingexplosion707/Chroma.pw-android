package com.metrolist.music.chroma

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Word(val text: String, val startMs: Long, val endMs: Long) {
    fun progress(pos: Long): Float = when {
        pos <= startMs -> 0f
        pos >= endMs -> 1f
        else -> (pos - startMs).toFloat() / (endMs - startMs).coerceAtLeast(1)
    }
}

data class LyricLine(val startMs: Long, val endMs: Long, val words: List<Word>, val estimated: Boolean) {
    val text: String get() = words.joinToString("") { it.text }.trim()
    val isBreak get() = words.all { it.text.isBlank() }
}

data class Lyrics(
    val lines: List<LyricLine> = emptyList(),
    val plain: String? = null,
    val instrumental: Boolean = false,
    /** true when every line came with real per-word timing */
    val wordTimed: Boolean = false,
) {
    val synced get() = lines.isNotEmpty()
    val isEmpty get() = lines.isEmpty() && plain.isNullOrBlank() && !instrumental

    /** Index of the line playing at [pos], or -1 before the first line. */
    fun indexAt(pos: Long): Int {
        var lo = 0; var hi = lines.size - 1; var ans = -1
        while (lo <= hi) { val m = (lo + hi) / 2; if (lines[m].startMs <= pos) { ans = m; lo = m + 1 } else hi = m - 1 }
        return ans
    }
}

/**
 * Time-synced lyrics from LRCLIB (lrclib.net). Uses real per-word timing when the LRC has
 * enhanced word tags (<mm:ss.xx>); otherwise estimates each word's timing inside its line.
 */
object LyricsRepo {
    private const val BASE = "https://lrclib.net/api"
    private val cache = LinkedHashMap<String, Lyrics?>()

    suspend fun load(title: String, artist: String, album: String, durationMs: Long): Lyrics? = withContext(Dispatchers.IO) {
        val key = "$title|$artist|$durationMs"
        synchronized(cache) { if (cache.containsKey(key)) return@withContext cache[key] }
        val cleanTitle = title.replace(Regex("""\s*[(\[]\s*(feat|with|ft)\.?[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+-\s+(Remaster|.*Version|Live).*$""", RegexOption.IGNORE_CASE), "").trim()
        val firstArtist = artist.split(",").first().trim()
        val result = try {
            val q = "artist_name=${enc(firstArtist)}&track_name=${enc(cleanTitle)}&album_name=${enc(album)}" +
                (if (durationMs > 0) "&duration=${durationMs / 1000}" else "")
            parse(get("$BASE/get?$q"), durationMs) ?: search(cleanTitle, firstArtist, durationMs)
        } catch (e: Exception) { null }
        synchronized(cache) { cache[key] = result; if (cache.size > 40) cache.remove(cache.keys.first()) }
        result
    }

    private fun search(title: String, artist: String, durationMs: Long): Lyrics? {
        val arr = JSONArray(get("$BASE/search?track_name=${enc(title)}&artist_name=${enc(artist)}") ?: return null)
        var best: Lyrics? = null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val d = (o.optDouble("duration", 0.0) * 1000).toLong()
            if (durationMs > 0 && d > 0 && kotlin.math.abs(d - durationMs) > 8000) continue
            val l = parse(o.toString(), durationMs) ?: continue
            if (l.synced) return l
            if (best == null && !l.isEmpty) best = l
        }
        return best
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "Chroma-Metrolist (https://github.com/Kingexplosion707/Chroma.pw-android)")
        return try { if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
    }

    private val lineRe = Regex("""^\[(\d+):(\d+(?:\.\d+)?)](.*)$""")
    private val wordRe = Regex("""<(\d+):(\d+(?:\.\d+)?)>""")

    private fun ms(m: String, s: String) = ((m.toLong() * 60 + s.toDouble()) * 1000).toLong()

    private fun parse(body: String?, durationMs: Long): Lyrics? {
        if (body == null) return null
        val o = JSONObject(body)
        if (o.optBoolean("instrumental", false)) return Lyrics(instrumental = true)
        val synced = o.optString("syncedLyrics", "")
        val plain = o.optString("plainLyrics", "").ifBlank { null }
        val raw = synced.lines().mapNotNull { lineRe.find(it.trim())?.destructured }
            .map { (m, s, t) -> ms(m, s) to t }
            .sortedBy { it.first }
        if (raw.isEmpty()) return if (plain != null) Lyrics(plain = plain) else null
        var allWordTimed = true
        val lines = raw.mapIndexed { i, (start, text) ->
            val nextStart = raw.getOrNull(i + 1)?.first ?: (if (durationMs > start) durationMs else start + 5000)
            val tagged = parseWordTags(start, text, nextStart)
            if (tagged == null) allWordTimed = false
            tagged ?: estimate(start, nextStart, text.trim())
        }
        return Lyrics(lines, plain, wordTimed = allWordTimed)
    }

    /** Enhanced LRC: "<00:12.30>word <00:12.80>next". */
    private fun parseWordTags(start: Long, text: String, nextStart: Long): LyricLine? {
        val tags = wordRe.findAll(text).toList()
        if (tags.isEmpty()) return null
        val words = mutableListOf<Word>()
        tags.forEachIndexed { i, m ->
            val (mm, ss) = m.destructured
            val s = ms(mm, ss)
            val chunk = text.substring(m.range.last + 1, tags.getOrNull(i + 1)?.range?.first ?: text.length)
            val e = tags.getOrNull(i + 1)?.let { n -> ms(n.groupValues[1], n.groupValues[2]) } ?: minOf(nextStart, s + 1200)
            if (chunk.isNotEmpty()) words += Word(chunk, s, e)
        }
        return LyricLine(start, nextStart, words, estimated = false)
    }

    /**
     * Estimated word timing: words share the sung part of the line in proportion to their length.
     * The sung part is capped so long instrumental gaps don't stretch the last words.
     */
    private fun estimate(start: Long, nextStart: Long, text: String): LyricLine {
        if (text.isBlank()) return LyricLine(start, nextStart, listOf(Word("", start, nextStart)), true)
        val parts = text.split(Regex("""(?<=\s)""")).filter { it.isNotEmpty() }
        val weights = parts.map { it.trim().length + 1.5 }
        val total = weights.sum()
        val window = nextStart - start
        val sung = minOf(window * 0.92, maxOf(900.0, total * 85.0)).toLong()
        var t = start.toDouble()
        val words = parts.mapIndexed { i, p ->
            val d = sung * weights[i] / total
            val w = Word(p, t.toLong(), (t + d).toLong())
            t += d; w
        }
        return LyricLine(start, nextStart, words, estimated = true)
    }
}
