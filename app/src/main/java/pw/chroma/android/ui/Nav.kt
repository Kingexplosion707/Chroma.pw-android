package pw.chroma.android.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList

sealed interface Route {
    data object Home : Route
    data object Search : Route
    data object Library : Route
    data object Liked : Route
    data object Queue : Route
    data class Playlist(val id: String) : Route
    data class Album(val id: String) : Route
    data class Artist(val id: String) : Route
    data object Settings : Route
    data class SettingsPage(val page: String) : Route
}

/** Bottom tabs. Custom tabs open any Spotify URI (Chroma "Custom tabs"). */
data class TabDef(val id: String, val title: String, val root: Route)

object Nav {
    val tab = mutableStateOf("home")
    private val stacks = HashMap<String, SnapshotStateList<Route>>()
    val playerOpen = mutableStateOf(false)
    val lyricsOpen = mutableStateOf(false)
    val sheet = mutableStateOf<String?>(null) // "queue" | "connect" | "timer"
    val trackMenu = mutableStateOf<pw.chroma.android.Track?>(null)

    fun stack(tabId: String, root: Route): SnapshotStateList<Route> = stacks.getOrPut(tabId) { mutableStateListOf(root) }

    fun push(r: Route) { stacks[tab.value]?.add(r) }

    fun open(uri: String) {
        val id = uri.substringAfterLast(':')
        when {
            uri.contains(":playlist:") -> push(Route.Playlist(id))
            uri.contains(":album:") -> push(Route.Album(id))
            uri.contains(":artist:") -> push(Route.Artist(id))
            uri.contains(":collection") -> push(Route.Liked)
        }
    }

    /** Returns true if something was closed. */
    fun back(): Boolean {
        if (trackMenu.value != null) { trackMenu.value = null; return true }
        if (sheet.value != null) { sheet.value = null; return true }
        if (lyricsOpen.value) { lyricsOpen.value = false; return true }
        if (playerOpen.value) { playerOpen.value = false; return true }
        val s = stacks[tab.value] ?: return false
        if (s.size > 1) { s.removeAt(s.lastIndex); return true }
        return false
    }

    fun reselect(tabId: String) { stacks[tabId]?.let { while (it.size > 1) it.removeAt(it.lastIndex) } }
}

fun routeFor(uri: String): Route? {
    val id = uri.substringAfterLast(':')
    return when {
        uri.contains(":playlist:") -> Route.Playlist(id)
        uri.contains(":album:") -> Route.Album(id)
        uri.contains(":artist:") -> Route.Artist(id)
        uri.contains(":collection") -> Route.Liked
        else -> null
    }
}

/** Accepts spotify:x:id or https://open.spotify.com/x/id links. */
fun normalizeUri(input: String): String? {
    val s = input.trim()
    if (s.startsWith("spotify:")) return s
    val m = Regex("""open\.spotify\.com/(?:intl-[a-z]+/)?(playlist|album|artist|track)/([A-Za-z0-9]+)""").find(s) ?: return null
    return "spotify:${m.groupValues[1]}:${m.groupValues[2]}"
}
