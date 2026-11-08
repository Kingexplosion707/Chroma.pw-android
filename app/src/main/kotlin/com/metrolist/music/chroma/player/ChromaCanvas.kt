package com.metrolist.music.chroma.player

import com.metrolist.music.chroma.ChromaPrefs
import com.metrolist.music.chroma.spotify.SpotifyApi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Best-effort Spotify Canvas lookup using the user's own sp_dc cookie (stored only on this device).
 * Uses Spotify's unofficial web-player endpoints, which can change or stop working at any time.
 */
object CanvasProvider {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"
    private var token: String? = null
    private var tokenExp = 0L
    private val cache = HashMap<String, String?>()
    var lastError: String? = null
        private set

    suspend fun find(trackUri: String): String? = withContext(Dispatchers.IO) {
        if (!trackUri.startsWith("spotify:track:")) return@withContext null
        if (cache.containsKey(trackUri)) return@withContext cache[trackUri]
        // Your Spotify login's token first; the web player token from sp_dc as a fallback.
        val oauth = if (SpotifyApi.loggedIn.value) runCatching { SpotifyApi.bearer() }.getOrNull() else null
        val url = oauth?.let { runCatching { fetchCanvas(trackUri, it) }.getOrNull() }
            ?: try {
                val t = accessToken() ?: return@withContext null
                fetchCanvas(trackUri, t)
            } catch (e: Exception) { lastError = e.message; null }
        cache[trackUri] = url
        url
    }

    private fun accessToken(): String? {
        if (token != null && System.currentTimeMillis() < tokenExp) return token
        val dc = ChromaPrefs.spDc.value.trim()
        if (dc.isEmpty()) { lastError = "No sp_dc set"; return null }
        val c = URL("https://open.spotify.com/api/token?reason=init&productType=web-player").openConnection() as HttpURLConnection
        c.setRequestProperty("Cookie", "sp_dc=$dc")
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "application/json")
        c.connectTimeout = 8000; c.readTimeout = 8000
        try {
            if (c.responseCode != 200) { lastError = "Token request failed (HTTP ${c.responseCode}). Spotify may have changed this endpoint or the cookie expired."; return null }
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            token = o.optString("accessToken").ifBlank { null }
            tokenExp = o.optLong("accessTokenExpirationTimestampMs", System.currentTimeMillis() + 30 * 60_000L) - 60_000L
            if (token == null) lastError = "Spotify returned no access token (anonymous or expired cookie)"
            return token
        } finally { c.disconnect() }
    }

    // ---- minimal protobuf: EntityCanvazRequest{ entities=1: Entity{ entity_uri=1 } } ----
    private fun varint(out: ByteArrayOutputStream, v0: Int) { var v = v0; while (v > 0x7F) { out.write((v and 0x7F) or 0x80); v = v ushr 7 }; out.write(v) }

    private fun encodeRequest(uri: String): ByteArray {
        val u = uri.toByteArray()
        val entity = ByteArrayOutputStream().apply { write(0x0A); varint(this, u.size); write(u) }.toByteArray()
        return ByteArrayOutputStream().apply { write(0x0A); varint(this, entity.size); write(entity) }.toByteArray()
    }

    private class Reader(val b: ByteArray, var p: Int = 0, val end: Int = b.size) {
        fun varint(): Int { var r = 0; var s = 0; while (true) { val x = b[p++].toInt(); r = r or ((x and 0x7F) shl s); if (x and 0x80 == 0) return r; s += 7 } }
    }

    /** Response: field 1 = repeated Canvaz { 2 = url }. */
    private fun parseUrl(b: ByteArray): String? {
        val r = Reader(b)
        while (r.p < r.end) {
            val tag = r.varint(); val field = tag ushr 3; val wire = tag and 7
            if (wire != 2) { if (wire == 0) r.varint() else return null; continue }
            val len = r.varint()
            if (field == 1) {
                val s = Reader(b, r.p, r.p + len)
                while (s.p < s.end) {
                    val t = s.varint(); val f = t ushr 3; val w = t and 7
                    if (w == 0) { s.varint(); continue }
                    if (w != 2) break
                    val l = s.varint()
                    if (f == 2) return String(b, s.p, l)
                    s.p += l
                }
            }
            r.p += len
        }
        return null
    }

    private fun fetchCanvas(uri: String, token: String): String? {
        val c = URL("https://spclient.wg.spotify.com/canvaz-cache/v0/canvases").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Content-Type", "application/protobuf")
        c.setRequestProperty("Accept", "application/protobuf")
        c.setRequestProperty("User-Agent", UA)
        c.connectTimeout = 8000; c.readTimeout = 8000
        try {
            c.outputStream.use { it.write(encodeRequest(uri)) }
            if (c.responseCode != 200) { lastError = "Canvas request failed (HTTP ${c.responseCode})"; return null }
            val url = parseUrl(c.inputStream.readBytes())
            if (url == null) lastError = "This track has no Canvas"
            return url
        } finally { c.disconnect() }
    }
}
