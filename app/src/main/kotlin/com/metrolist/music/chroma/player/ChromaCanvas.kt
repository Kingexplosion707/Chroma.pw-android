package com.metrolist.music.chroma.player

import com.metrolist.music.chroma.ChromaPrefs
import com.metrolist.music.chroma.spotify.SpotifyApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Spotify Canvas lookup. Tries your Spotify login's token on the Canvas cache first, then the web
 * player's token made from your own sp_dc cookie (kept only on this phone) with the web player's
 * Canvas query. These are Spotify's unofficial web-player endpoints and can change at any time.
 */
object CanvasProvider {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
    private const val SECRETS = "https://raw.githubusercontent.com/xyloflake/spot-secrets-go/main/secrets/secretDict.json"
    private const val CANVAS_QUERY = "575138ab27cd5c1b3e54da54d0a7cc8d85485402de26340c2145f0f6bb5e7a9f"
    private val fallbackSecret = 61 to intArrayOf(44, 55, 47, 42, 70, 40, 34, 114, 76, 74, 50, 111, 120, 97, 75, 76, 94, 102, 43, 69, 49, 120, 118, 80, 64, 78)

    private var token: String? = null
    private var tokenExp = 0L
    private var tokenFor = ""
    private var secret: Pair<Int, IntArray>? = null
    private var secretAt = 0L
    private val cache = HashMap<String, String?>()
    var lastError: String? = null
        private set

    suspend fun find(trackUri: String): String? = withContext(Dispatchers.IO) {
        if (!trackUri.startsWith("spotify:track:")) return@withContext null
        synchronized(cache) { if (cache.containsKey(trackUri)) return@withContext cache[trackUri] }
        val oauth = if (SpotifyApi.loggedIn.value) runCatching { SpotifyApi.bearer() }.getOrNull() else null
        val url = oauth?.let { runCatching { canvaz(trackUri, it) }.getOrNull() }
            ?: runCatching {
                val t = webToken() ?: return@runCatching null
                canvasQuery(trackUri, t) ?: canvaz(trackUri, t)
            }.onFailure { lastError = it.message }.getOrNull()
        if (url != null) lastError = null
        synchronized(cache) { cache[trackUri] = url }
        url
    }

    // ---------------- web player token ----------------
    private fun get(url: String, headers: Map<String, String>): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", UA)
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        return try {
            val code = c.responseCode
            code to ((if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty())
        } finally { c.disconnect() }
    }

    /** The web player's current TOTP secret, published by spot-secrets-go; a known one if offline. */
    private fun totpSecret(): Pair<Int, IntArray> {
        secret?.takeIf { System.currentTimeMillis() - secretAt < 6 * 3_600_000L }?.let { return it }
        runCatching {
            val (code, body) = get(SECRETS, emptyMap())
            check(code == 200)
            val o = JSONObject(body)
            val ver = o.keys().asSequence().mapNotNull { it.toIntOrNull() }.max()
            val a = o.getJSONArray(ver.toString())
            ver to IntArray(a.length()) { a.getInt(it) }
        }.onSuccess { secret = it; secretAt = System.currentTimeMillis() }
        return secret ?: fallbackSecret
    }

    private fun totp(cipher: IntArray, timeMs: Long): String {
        val key = cipher.mapIndexed { i, e -> (e xor ((i % 33) + 9)).toString() }.joinToString("").toByteArray()
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
        val h = mac.doFinal(ByteBuffer.allocate(8).putLong(timeMs / 1000 / 30).array())
        val o = h[h.size - 1].toInt() and 15
        val v = ((h[o].toInt() and 0x7f) shl 24) or ((h[o + 1].toInt() and 0xff) shl 16) or
            ((h[o + 2].toInt() and 0xff) shl 8) or (h[o + 3].toInt() and 0xff)
        return (v % 1_000_000).toString().padStart(6, '0')
    }

    private fun webToken(): String? {
        val dc = ChromaPrefs.spDc.value.trim()
        if (dc.isEmpty()) { lastError = "No sp_dc cookie set"; return null }
        if (token != null && tokenFor == dc && System.currentTimeMillis() < tokenExp) return token
        val (ver, cipher) = totpSecret()
        val code = totp(cipher, System.currentTimeMillis())
        val (status, body) = get(
            "https://open.spotify.com/api/token?reason=init&productType=web-player&totp=$code&totpServer=$code&totpVer=$ver",
            mapOf("Cookie" to "sp_dc=$dc", "Accept" to "application/json"),
        )
        if (status != 200) { lastError = "Spotify's web player refused the token request (HTTP $status)"; return null }
        val o = JSONObject(body)
        if (o.optBoolean("isAnonymous", false)) { lastError = "Spotify didn't accept the sp_dc cookie. Copy a fresh one while signed in to open.spotify.com."; return null }
        token = o.optString("accessToken").ifBlank { null }
        tokenFor = dc
        tokenExp = o.optLong("accessTokenExpirationTimestampMs", System.currentTimeMillis() + 30 * 60_000L) - 60_000L
        if (token == null) lastError = "Spotify returned no access token"
        return token
    }

    // ---------------- the web player's Canvas query ----------------
    private fun canvasQuery(uri: String, token: String): String? {
        val c = URL("https://api-partner.spotify.com/pathfinder/v2/query").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Content-Type", "application/json;charset=UTF-8")
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("App-Platform", "WebPlayer")
        c.setRequestProperty("User-Agent", UA)
        c.connectTimeout = 8000; c.readTimeout = 8000
        val body = JSONObject()
            .put("variables", JSONObject().put("trackUri", uri))
            .put("operationName", "canvas")
            .put("extensions", JSONObject().put("persistedQuery", JSONObject().put("version", 1).put("sha256Hash", CANVAS_QUERY)))
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode != 200) { lastError = "Canvas query failed (HTTP ${c.responseCode})"; return null }
            val canvas = JSONObject(c.inputStream.bufferedReader().readText())
                .optJSONObject("data")?.optJSONObject("trackUnion")?.optJSONObject("canvas")
            val url = canvas?.optString("url").orEmpty()
            val video = url.isNotBlank() && (url.contains(".mp4") || canvas?.optString("type").orEmpty().contains("VIDEO", true))
            if (!video) lastError = "This song has no Canvas"
            return url.takeIf { video }
        } finally { c.disconnect() }
    }

    // ---- the Canvas cache: EntityCanvazRequest{ entities=1: Entity{ entity_uri=1 } } ----
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

    private fun canvaz(uri: String, token: String): String? {
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
            val url = parseUrl(c.inputStream.readBytes())?.takeIf { it.startsWith("http") }
            if (url == null) lastError = "This song has no Canvas"
            return url
        } finally { c.disconnect() }
    }
}
