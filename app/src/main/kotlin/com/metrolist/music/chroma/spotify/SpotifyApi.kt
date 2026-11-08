package com.metrolist.music.chroma.spotify

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

// ---------------- models ----------------
data class ArtistRef(val id: String, val name: String)

data class SpTrack(
    val id: String,
    val uri: String,
    val name: String,
    val artists: List<ArtistRef>,
    val albumId: String = "",
    val albumName: String = "",
    val image: String? = null,
    val durationMs: Long = 0,
    val explicit: Boolean = false,
    val imageLarge: String? = null,
) {
    val artistLine get() = artists.joinToString(", ") { it.name }
}

/** A playlist, album, artist, show or the Liked Songs collection. */
data class SpItem(
    val type: String,
    val id: String,
    val uri: String,
    val name: String,
    val subtitle: String = "",
    val image: String? = null,
    val owner: String = "",
    val addedAt: String = "",
)

data class SpHeader(
    val item: SpItem,
    val description: String = "",
    val followers: Int = 0,
    val total: Int = 0,
    val genres: List<String> = emptyList(),
    val release: String = "",
)

data class SpDevice(val id: String, val name: String, val type: String, val active: Boolean, val volume: Int)

data class PlayerSnap(
    val playing: Boolean,
    val progressMs: Long,
    val track: SpTrack?,
    val shuffle: Boolean,
    /** off | context | track */
    val repeat: String,
    val device: SpDevice?,
    val contextUri: String?,
    val at: Long = System.currentTimeMillis(),
)

class SpotifyApiException(msg: String, val code: Int = 0) : Exception(msg)

/** Spotify Web API client: OAuth (PKCE, your own Client ID), library, browse, search and playback. */
object SpotifyApi {
    const val REDIRECT = "chroma://callback"
    private const val SCOPE_VERSION = 2
    private const val SCOPES = "playlist-read-private playlist-read-collaborative playlist-modify-private playlist-modify-public " +
        "user-library-read user-library-modify user-read-playback-state user-modify-playback-state user-read-currently-playing " +
        "user-read-recently-played user-top-read user-follow-read user-read-private user-read-email"

    private var sp: SharedPreferences? = null
    val loggedIn = mutableStateOf(false)
    val userName = mutableStateOf("")
    val userImage = mutableStateOf<String?>(null)
    val userId = mutableStateOf("")
    private var access = ""
    private var expiresAt = 0L

    var clientId: String
        get() = sp?.getString("clientId", null) ?: "3113fc6a4bcb489788cb913973b0ff90"
        set(v) { sp?.edit()?.putString("clientId", v.trim())?.apply() }

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.applicationContext.getSharedPreferences("chroma_api", Context.MODE_PRIVATE)
        sp = p
        // A login made before new scopes were added must be redone.
        if (p.getInt("scopeVersion", 0) != SCOPE_VERSION) p.edit().remove("refresh").apply()
        loggedIn.value = p.getString("refresh", null) != null
        userName.value = p.getString("user", "") ?: ""
        userImage.value = p.getString("userImage", null)
        userId.value = p.getString("userId", "") ?: ""
    }

    fun logout() {
        sp?.edit()?.remove("refresh")?.remove("user")?.remove("userImage")?.remove("userId")?.apply()
        access = ""; loggedIn.value = false; userName.value = ""; userImage.value = null
    }

    // ---------------- OAuth PKCE ----------------
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun beginLogin(ctx: Context) {
        val verifier = b64(ByteArray(48).also { SecureRandom().nextBytes(it) })
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        sp?.edit()?.putString("verifier", verifier)?.apply()
        val u = "https://accounts.spotify.com/authorize?client_id=${enc(clientId)}&response_type=code" +
            "&redirect_uri=${enc(REDIRECT)}&code_challenge_method=S256&code_challenge=$challenge&scope=${enc(SCOPES)}"
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    suspend fun finishLogin(data: Uri) {
        val code = data.getQueryParameter("code") ?: throw SpotifyApiException(data.getQueryParameter("error") ?: "Login cancelled")
        val verifier = sp?.getString("verifier", null) ?: throw SpotifyApiException("Login expired, try again")
        val r = tokenRequest("grant_type=authorization_code&code=${enc(code)}&redirect_uri=${enc(REDIRECT)}&client_id=${enc(clientId)}&code_verifier=${enc(verifier)}")
        saveToken(r)
        sp?.edit()?.putInt("scopeVersion", SCOPE_VERSION)?.apply()
        refreshProfile()
        loggedIn.value = true
    }

    suspend fun refreshProfile() {
        val me = call("GET", "/me") ?: return
        val name = me.optString("display_name").ifBlank { me.optString("id") }
        val image = img(me)
        sp?.edit()?.putString("user", name)?.putString("userImage", image)?.putString("userId", me.optString("id"))?.apply()
        userName.value = name; userImage.value = image; userId.value = me.optString("id")
    }

    private suspend fun tokenRequest(form: String): JSONObject = withContext(Dispatchers.IO) {
        val c = URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(form.toByteArray()) }
        try {
            val ok = c.responseCode in 200..299
            val body = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (!ok) throw SpotifyApiException("Auth failed: " + runCatching { JSONObject(body).optString("error_description") }.getOrDefault(body))
            JSONObject(body)
        } finally { c.disconnect() }
    }

    private fun saveToken(r: JSONObject) {
        access = r.getString("access_token")
        expiresAt = System.currentTimeMillis() + r.optLong("expires_in", 3600) * 1000 - 60_000
        r.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { sp?.edit()?.putString("refresh", it)?.apply() }
    }

    /** Current OAuth access token (refreshed when needed), for spclient requests like Canvas. */
    suspend fun bearer(): String = token()

    private suspend fun token(): String {
        if (access.isNotEmpty() && System.currentTimeMillis() < expiresAt) return access
        val rt = sp?.getString("refresh", null) ?: throw SpotifyApiException("Not connected to Spotify")
        try {
            saveToken(tokenRequest("grant_type=refresh_token&refresh_token=${enc(rt)}&client_id=${enc(clientId)}"))
        } catch (e: SpotifyApiException) {
            if (e.message?.contains("revoked", true) == true || e.message?.contains("invalid", true) == true) logout()
            throw e
        }
        return access
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    // ---------------- requests ----------------
    suspend fun call(method: String, path: String, body: String? = null): JSONObject? {
        val tk = token()
        return withContext(Dispatchers.IO) {
            val c = URL(if (path.startsWith("http")) path else "https://api.spotify.com/v1$path").openConnection() as HttpURLConnection
            c.requestMethod = method
            c.setRequestProperty("Authorization", "Bearer $tk")
            c.connectTimeout = 10000; c.readTimeout = 12000
            if (body != null) {
                c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            } else if (method == "PUT" || method == "POST" || method == "DELETE") {
                if (method != "DELETE") { c.doOutput = true; c.setFixedLengthStreamingMode(0); c.outputStream.close() }
            }
            try {
                val code = c.responseCode
                val ok = code in 200..299
                val txt = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
                when {
                    ok && txt.isBlank() -> null
                    ok -> runCatching { JSONObject(txt) }.getOrNull()
                    code == 403 -> throw SpotifyApiException("Spotify refused this (403). Playback control needs Premium, and your account must be on the app's user list in the developer dashboard.", code)
                    code == 404 && path.contains("/me/player") -> throw SpotifyApiException("No active Spotify device. Open Spotify on your phone first.", code)
                    code == 429 -> throw SpotifyApiException("Spotify is rate limiting requests. Try again in a moment.", code)
                    else -> throw SpotifyApiException("Spotify error $code: " + runCatching { JSONObject(txt).getJSONObject("error").optString("message") }.getOrDefault(txt.take(120)), code)
                }
            } finally { c.disconnect() }
        }
    }

    // ---------------- parsing ----------------
    fun img(o: JSONObject?, large: Boolean = false): String? {
        val a = o?.optJSONArray("images") ?: return null
        if (a.length() == 0) return null
        val i = if (large || a.length() == 1) 0 else 1
        return a.optJSONObject(i)?.optString("url")?.ifBlank { null }
    }

    private fun artists(o: JSONObject): List<ArtistRef> =
        o.optJSONArray("artists")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { ArtistRef(it.optString("id"), it.optString("name")) } }.orEmpty()

    fun track(o: JSONObject?, albumImage: String? = null, albumId: String = "", albumName: String = ""): SpTrack? {
        o ?: return null
        val uri = o.optString("uri"); if (uri.isEmpty() || o.optString("type") == "episode" && uri.isEmpty()) return null
        val al = o.optJSONObject("album")
        return SpTrack(
            o.optString("id"), uri, o.optString("name"), artists(o),
            al?.optString("id") ?: albumId, al?.optString("name") ?: albumName,
            img(al) ?: albumImage, o.optLong("duration_ms"), o.optBoolean("explicit"),
            img(al, large = true) ?: albumImage,
        )
    }

    fun item(type: String, o: JSONObject?): SpItem? {
        o ?: return null
        val uri = o.optString("uri"); if (uri.isEmpty()) return null
        val owner = o.optJSONObject("owner")?.optString("display_name").orEmpty()
        val sub = when (type) {
            "album" -> listOfNotNull(o.optString("release_date").take(4).ifBlank { null }, artists(o).joinToString(", ") { it.name }).joinToString(" • ")
            "playlist" -> if (owner.isNotBlank()) "Playlist • $owner" else "Playlist"
            "show" -> o.optString("publisher")
            else -> "Artist"
        }
        return SpItem(type, o.optString("id"), uri, o.optString("name"), sub, img(o), owner)
    }

    private fun <T> list(a: JSONArray?, f: (JSONObject) -> T?): List<T> =
        a?.let { x -> (0 until x.length()).mapNotNull { i -> x.optJSONObject(i)?.let(f) } }.orEmpty()

    // ---------------- home & library ----------------
    suspend fun recentlyPlayed(): List<Pair<SpTrack, String?>> =
        list(call("GET", "/me/player/recently-played?limit=50")?.optJSONArray("items")) { e ->
            track(e.optJSONObject("track"))?.let { it to e.optJSONObject("context")?.optString("uri") }
        }

    suspend fun topArtists(): List<SpItem> = list(call("GET", "/me/top/artists?limit=20&time_range=short_term")?.optJSONArray("items")) { item("artist", it) }
    suspend fun topTracks(): List<SpTrack> = list(call("GET", "/me/top/tracks?limit=20&time_range=short_term")?.optJSONArray("items")) { track(it) }

    suspend fun playlists(): List<SpItem> {
        val out = mutableListOf<SpItem>()
        var next: String? = "/me/playlists?limit=50"
        while (next != null && out.size < 300) {
            val r = call("GET", next) ?: break
            out += list(r.optJSONArray("items")) { item("playlist", it) }
            next = r.optString("next").takeIf { it.startsWith("http") }
        }
        return out
    }

    suspend fun savedAlbums(): List<SpItem> =
        list(call("GET", "/me/albums?limit=50")?.optJSONArray("items")) { e -> item("album", e.optJSONObject("album"))?.copy(addedAt = e.optString("added_at")) }

    suspend fun followedArtists(): List<SpItem> =
        list(call("GET", "/me/following?type=artist&limit=50")?.optJSONObject("artists")?.optJSONArray("items")) { item("artist", it) }

    suspend fun savedShows(): List<SpItem> =
        list(call("GET", "/me/shows?limit=50")?.optJSONArray("items")) { e -> item("show", e.optJSONObject("show")) }

    suspend fun liked(limit: Int = 200): List<SpTrack> {
        val out = mutableListOf<SpTrack>()
        var next: String? = "/me/tracks?limit=50"
        while (next != null && out.size < limit) {
            val r = call("GET", next) ?: break
            out += list(r.optJSONArray("items")) { track(it.optJSONObject("track")) }
            next = r.optString("next").takeIf { it.startsWith("http") }
        }
        return out
    }

    // ---------------- detail pages ----------------
    suspend fun playlist(id: String): Pair<SpHeader, List<SpTrack>> {
        val p = call("GET", "/playlists/$id") ?: throw SpotifyApiException("Playlist not found")
        val it = item("playlist", p)!!.copy(image = img(p, large = true))
        val tracksObj = p.optJSONObject("items") ?: p.optJSONObject("tracks")
        fun entries(r: JSONObject?) = list(r?.optJSONArray("items")) { e -> track(e.optJSONObject("item") ?: e.optJSONObject("track")) }
        val tracks = entries(tracksObj).toMutableList()
        var next = tracksObj?.optString("next")?.takeIf { s -> s.startsWith("http") }
        while (next != null && tracks.size < 500) {
            val r = runCatching { call("GET", next) }.getOrNull() ?: break
            tracks += entries(r)
            next = r.optString("next").takeIf { s -> s.startsWith("http") }
        }
        val h = SpHeader(it, android.text.Html.fromHtml(p.optString("description"), 0).toString(),
            p.optJSONObject("followers")?.optInt("total") ?: 0, tracksObj?.optInt("total") ?: tracks.size)
        return h to tracks
    }

    suspend fun album(id: String): Pair<SpHeader, List<SpTrack>> {
        val a = call("GET", "/albums/$id") ?: throw SpotifyApiException("Album not found")
        val it = item("album", a)!!.copy(image = img(a, large = true))
        val tracks = list(a.optJSONObject("tracks")?.optJSONArray("items")) { t -> track(t, it.image, it.id, it.name) }
        return SpHeader(it, release = a.optString("release_date"), total = a.optInt("total_tracks")) to tracks
    }

    suspend fun artist(id: String): SpHeader {
        val a = call("GET", "/artists/$id") ?: throw SpotifyApiException("Artist not found")
        val it = item("artist", a)!!.copy(image = img(a, large = true))
        val genres = a.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.optString(it) } }.orEmpty()
        return SpHeader(it, followers = a.optJSONObject("followers")?.optInt("total") ?: 0, genres = genres)
    }

    suspend fun track(id: String): SpTrack? = track(call("GET", "/tracks/$id"))

    suspend fun artistTopTracks(id: String): List<SpTrack> = list(call("GET", "/artists/$id/top-tracks")?.optJSONArray("tracks")) { track(it) }

    suspend fun artistAlbums(id: String): List<SpItem> =
        list(call("GET", "/artists/$id/albums?include_groups=album,single&limit=30")?.optJSONArray("items")) { item("album", it) }

    // ---------------- search ----------------
    data class SearchResult(val tracks: List<SpTrack>, val artists: List<SpItem>, val albums: List<SpItem>, val playlists: List<SpItem>)

    suspend fun search(q: String): SearchResult {
        val r = call("GET", "/search?q=${enc(q)}&type=track,artist,album,playlist&limit=10") ?: return SearchResult(emptyList(), emptyList(), emptyList(), emptyList())
        return SearchResult(
            list(r.optJSONObject("tracks")?.optJSONArray("items")) { track(it) },
            list(r.optJSONObject("artists")?.optJSONArray("items")) { item("artist", it) },
            list(r.optJSONObject("albums")?.optJSONArray("items")) { item("album", it) },
            list(r.optJSONObject("playlists")?.optJSONArray("items")) { item("playlist", it) },
        )
    }

    // ---------------- player ----------------
    suspend fun player(): PlayerSnap? {
        val p = call("GET", "/me/player?additional_types=episode") ?: return null
        val d = p.optJSONObject("device")
        return PlayerSnap(
            p.optBoolean("is_playing"), p.optLong("progress_ms"), track(p.optJSONObject("item")),
            p.optBoolean("shuffle_state"), p.optString("repeat_state", "off"),
            d?.let { SpDevice(it.optString("id"), it.optString("name"), it.optString("type"), it.optBoolean("is_active"), it.optInt("volume_percent")) },
            p.optJSONObject("context")?.optString("uri"),
        )
    }

    suspend fun queue(): List<SpTrack> = list(call("GET", "/me/player/queue")?.optJSONArray("queue")) { track(it) }

    suspend fun devices(): List<SpDevice> =
        list(call("GET", "/me/player/devices")?.optJSONArray("devices")) { SpDevice(it.optString("id"), it.optString("name"), it.optString("type"), it.optBoolean("is_active"), it.optInt("volume_percent")) }

    suspend fun transfer(deviceId: String) { call("PUT", "/me/player", JSONObject().put("device_ids", JSONArray(listOf(deviceId))).put("play", true).toString()) }

    private suspend fun deviceId(): String {
        val all = devices()
        if (all.isEmpty()) throw SpotifyApiException("No Spotify device found. Open Spotify on your phone first.")
        return (all.firstOrNull { it.active } ?: all.firstOrNull { it.name.equals(Build.MODEL, true) } ?: all.first()).id
    }

    /** Play a context (playlist/album/artist) from an optional track, or a plain list of track URIs. */
    suspend fun play(context: String? = null, uris: List<String>? = null, startUri: String? = null) {
        val body = JSONObject()
        if (context != null) {
            body.put("context_uri", context)
            if (startUri != null && !context.contains(":artist:")) body.put("offset", JSONObject().put("uri", startUri))
        } else if (uris != null) {
            val i = startUri?.let { uris.indexOf(it) }?.takeIf { it >= 0 } ?: 0
            body.put("uris", JSONArray(uris.drop(i).take(100)))
        }
        call("PUT", "/me/player/play?device_id=${deviceId()}", body.toString())
    }

    suspend fun resume() { call("PUT", "/me/player/play") }
    suspend fun pause() { call("PUT", "/me/player/pause") }
    suspend fun next() { call("POST", "/me/player/next") }
    suspend fun previous() { call("POST", "/me/player/previous") }
    suspend fun seek(ms: Long) { call("PUT", "/me/player/seek?position_ms=${ms.coerceAtLeast(0)}") }
    suspend fun shuffle(on: Boolean) { call("PUT", "/me/player/shuffle?state=$on") }
    suspend fun repeat(mode: String) { call("PUT", "/me/player/repeat?state=$mode") }
    suspend fun volume(pct: Int) { call("PUT", "/me/player/volume?volume_percent=${pct.coerceIn(0, 100)}") }
    suspend fun addToQueue(uri: String) { call("POST", "/me/player/queue?uri=${enc(uri)}") }

    // ---------------- library edits ----------------
    suspend fun isSaved(trackId: String): Boolean {
        val uri = "spotify:track:$trackId"
        return try {
            val r = call("GET", "/me/library/contains?uris=${enc(uri)}")
            r?.optJSONArray("result")?.optBoolean(0) ?: false
        } catch (e: SpotifyApiException) {
            // Older endpoint returns a bare JSON array, which call() can't parse as an object.
            withContext(Dispatchers.IO) {
                val c = URL("https://api.spotify.com/v1/me/tracks/contains?ids=$trackId").openConnection() as HttpURLConnection
                c.setRequestProperty("Authorization", "Bearer ${token()}")
                try { JSONArray(c.inputStream.bufferedReader().readText()).optBoolean(0) } catch (_: Exception) { false } finally { c.disconnect() }
            }
        }
    }

    suspend fun setSaved(trackId: String, saved: Boolean) {
        val uri = "spotify:track:$trackId"
        val m = if (saved) "PUT" else "DELETE"
        try { call(m, "/me/library?uris=${enc(uri)}") } catch (e: SpotifyApiException) { call(m, "/me/tracks?ids=$trackId") }
    }

    suspend fun addToPlaylist(playlistId: String, uri: String) {
        val body = JSONObject().put("uris", JSONArray(listOf(uri))).toString()
        try { call("POST", "/playlists/$playlistId/items", body) } catch (e: SpotifyApiException) { call("POST", "/playlists/$playlistId/tracks", body) }
    }
}
