package pw.chroma.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class LyricLine(val timeMs: Long, val text: String)
data class Lyrics(val synced: List<LyricLine> = emptyList(), val plain: String? = null, val instrumental: Boolean = false) {
    val isEmpty get() = synced.isEmpty() && plain.isNullOrBlank() && !instrumental
}

/** Time-synced lyrics from LRCLIB (lrclib.net) - free and keyless. */
object LyricsRepo {
    private const val BASE = "https://lrclib.net/api"
    private var cacheKey = ""
    private var cache: Lyrics? = null

    suspend fun load(title: String, artist: String, album: String, durationMs: Long): Lyrics? = withContext(Dispatchers.IO) {
        val key = "$title|$artist|$album|$durationMs"
        if (key == cacheKey) return@withContext cache
        val result = try {
            val q = "artist_name=${enc(artist)}&track_name=${enc(title)}&album_name=${enc(album)}" +
                (if (durationMs > 0) "&duration=${durationMs / 1000}" else "")
            parse(get("$BASE/get?$q")) ?: searchFallback(title, artist)
        } catch (e: Exception) { null }
        cacheKey = key; cache = result
        result
    }

    private fun searchFallback(title: String, artist: String): Lyrics? {
        val arr = JSONArray(get("$BASE/search?q=${enc("$artist $title")}") ?: return null)
        for (i in 0 until arr.length()) parse(arr.getJSONObject(i).toString())?.let { if (!it.isEmpty) return it }
        return null
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "Chroma-Android/1.1.0")
        return try { if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
    }

    private fun parse(body: String?): Lyrics? {
        if (body == null) return null
        val o = JSONObject(body)
        val synced = o.optString("syncedLyrics", "")
        val lines = Regex("""\[(\d+):(\d+(?:\.\d+)?)](.*)""").findAll(synced).map {
            val (m, s, t) = it.destructured
            LyricLine(((m.toLong() * 60 + s.toDouble()) * 1000).toLong(), t.trim())
        }.toList()
        val plain = o.optString("plainLyrics", "").ifBlank { null }
        return Lyrics(lines, plain, o.optBoolean("instrumental", false))
    }
}
