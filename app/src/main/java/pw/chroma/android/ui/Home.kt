package pw.chroma.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pw.chroma.android.*
import java.util.Calendar

/** Small in-memory cache so tab switches don't refetch. */
object HomeCache {
    var recent by mutableStateOf<List<Pair<Track, String?>>>(emptyList())
    var playlists by mutableStateOf<List<Item>>(emptyList())
    var topArtists by mutableStateOf<List<Item>>(emptyList())
    var topTracks by mutableStateOf<List<Track>>(emptyList())
    var albums by mutableStateOf<List<Item>>(emptyList())
    var shows by mutableStateOf<List<Item>>(emptyList())
    var loadedAt = 0L

    suspend fun load(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - loadedAt < 5 * 60_000 && playlists.isNotEmpty()) return
        loadedAt = System.currentTimeMillis()
        runCatching { playlists = Api.playlists() }
        runCatching { recent = Api.recentlyPlayed() }
        runCatching { topArtists = Api.topArtists() }
        runCatching { topTracks = Api.topTracks() }
        runCatching { albums = Api.savedAlbums() }
        runCatching { shows = Api.savedShows() }
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

/** Chroma home gradient: style, strength and height from settings. */
@Composable
fun HeaderGradient(color: Color, enabled: Boolean) {
    if (!enabled || Settings.homeGradientStyle == 2) return
    val alpha = when (Settings.homeGradientStrength) { 0 -> .35f; 1 -> .6f; else -> .9f }
    val h: Dp = when (Settings.homeGradientHeight) { 0 -> 180.dp; 1 -> 300.dp; 2 -> 460.dp; else -> 2000.dp }
    val brush = if (Settings.homeGradientStyle == 1) Brush.verticalGradient(listOf(color.copy(alpha = alpha), color.copy(alpha = alpha)))
    else Brush.verticalGradient(listOf(color.copy(alpha = alpha), Color.Transparent))
    Box(Modifier.fillMaxWidth().height(h).background(brush))
}

/** Recent contexts (playlist/album/artist) built from listening history, newest first. */
private fun recentContexts(): List<Item> {
    val byUri = HomeCache.playlists.associateBy { it.uri }
    val seen = LinkedHashMap<String, Item>()
    for ((t, ctx) in HomeCache.recent) {
        val it = when {
            ctx != null && byUri[ctx] != null -> byUri[ctx]
            ctx == null || ctx.contains(":album:") -> Item("album", t.albumId, "spotify:album:${t.albumId}", t.albumName, t.artistLine, t.image)
            ctx?.contains(":collection") == true -> Item("liked", "liked", "spotify:collection", "Liked Songs", "Playlist")
            else -> null
        } ?: continue
        if (it.id.isNotEmpty()) seen.putIfAbsent(it.uri, it)
    }
    return seen.values.toList()
}

@Composable
fun HomeScreen() {
    if (!Api.loggedIn.value) { ConnectScreen(); return }
    var filter by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { HomeCache.load() }
    val contexts = remember(HomeCache.recent, HomeCache.playlists) { recentContexts() }
    val shortcuts = remember(contexts, HomeCache.playlists) {
        (listOf(Item("liked", "liked", "spotify:collection", "Liked Songs", "Playlist")) + contexts + HomeCache.playlists)
            .distinctBy { it.uri }.take(8)
    }
    val headColor = rememberImageColor(shortcuts.getOrNull(1)?.image, C.accent.copy(alpha = .6f))
    val playingUri = Playback.snap.value?.contextUri
    val playing = Remote.state.value.playing

    Box(Modifier.fillMaxSize()) {
        HeaderGradient(headColor, Settings.gradientHome)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 180.dp)) {
            item {
                Row(Modifier.statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(Api.userImage.value, Api.userName.value) { Nav.push(Route.Settings) }
                    Spacer(Modifier.width(12.dp))
                    if (Settings.homePills) PillRow(listOf("All", "Music", "Podcasts"), filter, { filter = it }, Modifier.weight(1f))
                    else Text(greeting(), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (Settings.homePills) item { Text(greeting(), fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 18.dp)) }

            if (filter == 2) {
                item { SectionTitle("Your shows") }
                if (HomeCache.shows.isEmpty()) item { EmptyNote("No saved podcasts yet.") }
                items(HomeCache.shows) { s -> ItemRow(s) { } }
                return@LazyColumn
            }

            if (Settings.homeShortcuts && shortcuts.isNotEmpty()) item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    shortcuts.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { s -> Shortcut(s, s.uri == playingUri && playing, Modifier.weight(1f)) }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            if (contexts.isNotEmpty()) item {
                SectionTitle("Jump back in")
                Carousel(contexts.take(12))
            }
            if (HomeCache.topTracks.isNotEmpty()) item {
                SectionTitle("On repeat")
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(HomeCache.topTracks) { t ->
                        MediaCard(Item("track", t.id, t.uri, t.name, t.artistLine, t.image)) { Playback.play(uris = HomeCache.topTracks.map { it.uri }, startUri = t.uri) }
                    }
                }
            }
            if (HomeCache.topArtists.isNotEmpty()) item {
                SectionTitle("Your top artists")
                Carousel(HomeCache.topArtists)
            }
            if (HomeCache.playlists.isNotEmpty() && filter != 2) item {
                SectionTitle("Your playlists")
                Carousel(HomeCache.playlists.take(20))
            }
            if (HomeCache.albums.isNotEmpty()) item {
                SectionTitle("Albums you saved")
                Carousel(HomeCache.albums.take(20))
            }
            if (HomeCache.recent.isNotEmpty()) item {
                SectionTitle("Recently played")
                val tracks = HomeCache.recent.map { it.first }.distinctBy { it.id }.take(10)
                Column { tracks.forEach { t -> TrackRow(t, t.id == Playback.trackId(), onMore = { Nav.trackMenu.value = t }) { Playback.play(uris = tracks.map { it.uri }, startUri = t.uri) } } }
            }
            if (HomeCache.playlists.isEmpty() && HomeCache.recent.isEmpty()) item { LoadingBox() }
        }
    }
}

@Composable
private fun Shortcut(item: Item, playing: Boolean, modifier: Modifier) {
    Row(
        modifier.height(56.dp).clip(RoundedCornerShape(6.dp))
            .then(if (Settings.glassHome) Modifier.glass(RoundedCornerShape(6.dp)) else Modifier.background(Color.White.copy(alpha = .10f)))
            .clickable { Nav.open(item.uri) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.type == "liked") LikedTile(56.dp, RoundedCornerShape(0.dp)) else Cover(item.image, 56.dp, RoundedCornerShape(0.dp))
        Spacer(Modifier.width(8.dp))
        Text(item.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = if (playing) C.accent else Color.White, modifier = Modifier.weight(1f).padding(end = 8.dp))
    }
}

@Composable
fun Carousel(items: List<Item>) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(items, key = { it.uri }) { i -> MediaCard(i) { Nav.open(i.uri) } }
    }
}

@Composable
fun ConnectScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var id by remember { mutableStateOf(Api.clientId) }
    Box(Modifier.fillMaxSize()) {
        HeaderGradient(C.accent.copy(alpha = .7f), true)
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(28.dp), verticalArrangement = Arrangement.Center) {
            Text("Chroma", fontSize = 40.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(6.dp))
            Text("Connect your Spotify account to browse your library, search and control playback.", color = C.sub)
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = id, onValueChange = { id = it.trim() }, singleLine = true, label = { Text("Spotify Client ID") },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text("Redirect URI to add in the Spotify developer dashboard: ${Api.REDIRECT}", fontSize = 12.sp, color = C.faint)
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { if (id.isNotEmpty()) { Api.clientId = id; Api.beginLogin(ctx) } },
                colors = ButtonDefaults.buttonColors(containerColor = C.accent, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(50),
            ) { Text("Log in with Spotify", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        }
    }
}
