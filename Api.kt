package pw.chroma.android

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

data class Item(
    val type: String,        // track | album | artist | playlist | liked
    val id: String,
    val uri: String,
    val name: String,
    val subtitle: String = "",
    val image: String? = null,
)

class ApiException(msg: String) : Exception(msg)

/** Spotify Web API client: OAuth (PKCE, your own Client ID), library, search and playback. */
object Api {
    const val REDIRECT = "chroma://callback"
    private const val SCOPES = "playlist-read-private playlist-read-collaborative user-library-read user-library-modify " +
        "user-read-playback-state user-modify-playback-state user-read-currently-playing user-read-private"

    private lateinit var sp: SharedPreferences
    val loggedIn = mutableStateOf(false)
    val userName = mutableStateOf("")
    private var access = ""
    private var expiresAt = 0L

    var clientId: String
        get() = sp.getString("clientId", null) ?: "3113fc6a4bcb489788cb913973b0ff90"
        set(v) { sp.edit().putString("clientId", v.trim()).apply() }

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("chroma_api", Context.MODE_PRIVATE)
        loggedIn.value = sp.getString("refresh", null) != null
        userName.value = sp.getString("user", "") ?: ""
    }

    fun logout() { sp.edit().remove("refresh").remove("user").apply(); access = ""; loggedIn.value = false; userName.value = "" }

    // ---------- OAuth PKCE ----------
    fun beginLogin(ctx: Context) {
        val verifier = ByteArray(48).also { SecureRandom().nextBytes(it) }.let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }
        val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        sp.edit().putString("verifier", verifier).apply()
        val u = "https://accounts.spotify.com/authorize?client_id=${enc(clientId)}&response_type=code" +
            "&redirect_uri=${enc(REDIRECT)}&code_challenge_method=S256&code_challenge=$challenge&scope=${enc(SCOPES)}"
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    suspend fun finishLogin(data: Uri) {
        val code = data.getQueryParameter("code") ?: throw ApiException(data.getQueryParameter("error") ?: "Login cancelled")
        val verifier = sp.getString("verifier", null) ?: throw ApiException("Login expired, try again")
        val r = tokenRequest("grant_type=authorization_code&code=${enc(code)}&redirect_uri=${enc(REDIRECT)}&client_id=${enc(clientId)}&code_verifier=${enc(verifier)}")
        saveToken(r)
        val me = call("GET", "/me")
        val name = me?.optString("display_name").orEmpty().ifBlank { me?.optString("id").orEmpty() }
        sp.edit().putString("user", name).apply()
        userName.value = name; loggedIn.value = true
    }

    private suspend fun tokenRequest(form: String): JSONObject = withContext(Dispatchers.IO) {
        val c = URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(form.toByteArray()) }
        try {
            val body = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (c.responseCode !in 200..299) throw ApiException("Auth failed: " + runCatching { JSONObject(body).optString("error_description") }.getOrDefault(body))
            JSONObject(body)
        } finally { c.disconnect() }
    }

    private fun saveToken(r: JSONObject) {
        access = r.getString("access_token")
        expiresAt = System.currentTimeMillis() + r.optLong("expires_in", 3600) * 1000 - 60_000
        r.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { sp.edit().putString("refresh", it).apply() }
    }

    private suspend fun token(): String {
        if (access.isNotEmpty() && System.currentTimeMillis() < expiresAt) return access
        val rt = sp.getString("refresh", null) ?: throw ApiException("Not connected")
        saveToken(tokenRequest("grant_type=refresh_token&refresh_token=${enc(rt)}&client_id=${enc(clientId)}"))
        return access
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------- requests ----------
    suspend fun call(method: String, path: String, body: String? = null): JSONObject? {
        val tk = token()
        return withContext(Dispatchers.IO) {
            val c = URL("https://api.spotify.com/v1$path").openConnection() as HttpURLConnection
            c.requestMethod = method
            c.setRequestProperty("Authorization", "Bearer $tk")
            c.connectTimeout = 10000; c.readTimeout = 10000
            if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
            else if (method == "PUT" || method == "POST") { c.doOutput = true; c.outputStream.use { } }
            try {
                val code = c.responseCode
                val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
                when {
                    code == 204 || (code in 200..299 && txt.isBlank()) -> null
                    code in 200..299 -> JSONObject(txt)
                    code == 403 -> throw ApiException("Spotify refused this (403). Playback control needs Premium, and your account must be added to the app's allowlist in the developer dashboard.")
                    code == 404 && path.contains("/player") -> throw ApiException("No active Spotify device. Open the Spotify app first.")
                    code == 429 -> throw ApiException("Rate limited by Spotify, try again in a moment.")
                    else -> throw ApiException("Spotify error $code: " + runCatching { JSONObject(txt).getJSONObject("error").optString("message") }.getOrDefault(txt.take(120)))
                }
            } finally { c.disconnect() }
        }
    }

    // ---------- parsing ----------
    private fun img(o: JSONObject?): String? {
        val a = o?.optJSONArray("images") ?: return null
        if (a.length() == 0) return null
        return a.getJSONObject(if (a.length() > 1) 1 else 0).optString("url").ifBlank { null }
    }

    private fun artists(o: JSONObject): String =
        o.optJSONArray("artists")?.let { a -> (0 until a.length()).joinToString(", ") { a.getJSONObject(it).optString("name") } }.orEmpty()

    private fun track(o: JSONObject): Item? {
        val uri = o.optString("uri"); if (uri.isEmpty()) return null
        return Item("track", o.optString("id"), uri, o.optString("name"), artists(o), img(o.optJSONObject("album")))
    }

    private fun parse(type: String, o: JSONObject): Item? {
        if (type == "track") return track(o)
        val uri = o.optString("uri"); if (uri.isEmpty()) return null
        val sub = when (type) {
            "album" -> artists(o)
            "playlist" -> o.optJSONObject("owner")?.optString("display_name").orEmpty()
            else -> "Artist"
        }
        return Item(type, o.optString("id"), uri, o.optString("name"), sub, img(o))
    }

    private fun list(a: JSONArray?, f: (JSONObject) -> Item?) = a?.let { x -> (0 until x.length()).mapNotNull { i -> x.optJSONObject(i)?.let(f) } }.orEmpty()

    // ---------- library ----------
    suspend fun playlists(): List<Item> =
        list(call("GET", "/me/playlists?limit=50")?.optJSONArray("items")) { parse("playlist", it) }

    suspend fun liked(): List<Item> =
        list(call("GET", "/me/tracks?limit=50")?.optJSONArray("items")) { it.optJSONObject("track")?.let(::track) }

    suspend fun playlistTracks(id: String): List<Item> {
        fun entries(r: JSONObject?) = list(r?.optJSONArray("items")) { e -> (e.optJSONObject("item") ?: e.optJSONObject("track"))?.let(::track) }
        return try { entries(call("GET", "/playlists/$id/items?limit=100")) }
        catch (e: ApiException) { entries(call("GET", "/playlists/$id/tracks?limit=100")) }
    }

    suspend fun albumTracks(id: String): List<Item> {
        val r = call("GET", "/albums/$id")
        val cover = img(r)
        return list(r?.optJSONObject("tracks")?.optJSONArray("items")) { o -> track(o)?.copy(image = cover) }
    }

    suspend fun search(q: String): List<Item> {
        val r = call("GET", "/search?q=${enc(q)}&type=track,album,artist,playlist&limit=10") ?: return emptyList()
        return listOf("track" to "tracks", "album" to "albums", "artist" to "artists", "playlist" to "playlists").flatMap { (t, k) ->
            list(r.optJSONObject(k)?.optJSONArray("items")) { parse(t, it) }
        }
    }

    // ---------- playback ----------
    private suspend fun deviceId(): String {
        val devs = call("GET", "/me/player/devices")?.optJSONArray("devices")
        if (devs == null || devs.length() == 0) throw ApiException("No Spotify device found. Open the Spotify app on this phone first.")
        val all = (0 until devs.length()).map { devs.getJSONObject(it) }
        val pick = all.firstOrNull { it.optBoolean("is_active") }
            ?: all.firstOrNull { it.optString("name").equals(Build.MODEL, true) }
            ?: all.first()
        return pick.getString("id")
    }

    /** Play a context (playlist/album/artist) from an optional track, or a plain list of track URIs. */
    suspend fun play(context: String? = null, uris: List<String>? = null, startUri: String? = null) {
        val body = JSONObject()
        if (context != null) {
            body.put("context_uri", context)
            if (startUri != null) body.put("offset", JSONObject().put("uri", startUri))
        } else if (uris != null) {
            val i = startUri?.let { uris.indexOf(it) }?.takeIf { it >= 0 } ?: 0
            body.put("uris", JSONArray(uris.drop(i).take(50)))
        }
        call("PUT", "/me/player/play?device_id=${deviceId()}", body.toString())
    }

    suspend fun like(trackId: String) { call("PUT", "/me/tracks?ids=$trackId") }
}
