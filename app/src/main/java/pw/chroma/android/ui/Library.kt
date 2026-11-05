package pw.chroma.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pw.chroma.android.*

object LibraryCache {
    var artists by mutableStateOf<List<Item>>(emptyList())
    var liked by mutableStateOf<List<Track>>(emptyList())
}

private val sortNames = listOf("Recents", "Recently added", "Alphabetical", "Creator")

@Composable
fun LibraryScreen() {
    if (!Api.loggedIn.value) { ConnectScreen(); return }
    var filter by remember { mutableStateOf<Int?>(null) }
    var sortOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        HomeCache.load()
        runCatching { LibraryCache.artists = Api.followedArtists() }
    }
    val recentOrder = remember(HomeCache.recent) {
        HomeCache.recent.mapNotNull { it.second }.distinct().withIndex().associate { (i, u) -> u to i }
    }
    val liked = Item("liked", "liked", "spotify:collection", "Liked Songs", "Playlist • ${LibraryCache.liked.size.takeIf { it > 0 }?.let { "$it songs" } ?: "Your favourites"}")
    val items = remember(filter, HomeCache.playlists, HomeCache.albums, LibraryCache.artists, Settings.librarySort, recentOrder) {
        val base = when (filter) {
            0 -> HomeCache.playlists
            1 -> HomeCache.albums
            2 -> LibraryCache.artists
            else -> HomeCache.playlists + HomeCache.albums + LibraryCache.artists
        }
        when (Settings.librarySort) {
            0 -> base.sortedBy { recentOrder[it.uri] ?: Int.MAX_VALUE }
            1 -> base.sortedByDescending { it.addedAt }
            2 -> base.sortedBy { it.name.lowercase() }
            else -> base.sortedBy { it.owner.ifBlank { it.subtitle }.lowercase() }
        }
    }
    val showLiked = filter == null || filter == 0

    Box(Modifier.fillMaxSize()) {
        HeaderGradient(C.accent.copy(alpha = .45f), Settings.gradientLibrary)
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(Api.userImage.value, Api.userName.value) { Nav.push(Route.Settings) }
                Spacer(Modifier.width(12.dp))
                Text("Your Library", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { Nav.tab.value = "search" }) { Icon(Icons.Default.Search, "Search") }
            }
            PillRow(listOf("Playlists", "Albums", "Artists"), filter, { filter = it }, Modifier.padding(vertical = 10.dp))
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.clickable { sortOpen = true }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(sortNames[Settings.librarySort], fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                DropdownMenu(sortOpen, { sortOpen = false }) {
                    sortNames.forEachIndexed { i, n -> DropdownMenuItem({ Text(n) }, { Settings.librarySort = i; sortOpen = false }) }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { Settings.libraryGrid = !Settings.libraryGrid }) {
                    Icon(if (Settings.libraryGrid) Icons.Default.ViewList else Icons.Default.GridView, "Layout", Modifier.size(18.dp))
                }
            }
            if (HomeCache.playlists.isEmpty() && HomeCache.albums.isEmpty()) LoadingBox()
            if (Settings.libraryGrid) {
                LazyVerticalGrid(GridCells.Fixed(3), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 180.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (showLiked) item {
                        Column(Modifier.clickable { Nav.push(Route.Liked) }) {
                            LikedTile(110.dp); Spacer(Modifier.height(6.dp))
                            Text("Liked Songs", fontSize = 13.sp, maxLines = 1)
                        }
                    }
                    items(items, key = { it.uri }) { i ->
                        Column(Modifier.clickable { Nav.open(i.uri) }) {
                            Cover(i.image, 110.dp, if (i.type == "artist") CircleShape else RoundedCornerShape(4.dp))
                            Spacer(Modifier.height(6.dp))
                            Text(i.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(i.subtitle, fontSize = 11.sp, color = C.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(top = 6.dp, bottom = 180.dp)) {
                    if (showLiked) item { ItemRow(liked, pinned = true) { Nav.push(Route.Liked) } }
                    items(items, key = { it.uri }) { i -> ItemRow(i) { Nav.open(i.uri) } }
                }
            }
        }
    }
}

@Composable
fun LikedScreen() {
    LaunchedEffect(Unit) { runCatching { LibraryCache.liked = Api.liked(500) } }
    val tracks = LibraryCache.liked
    CollectionPage(
        title = "Liked Songs", subtitle = "${tracks.size} songs", image = null, liked = true, color = Color(0xFF4A2FC0),
        tracks = tracks, contextUri = null, description = "", creator = Api.userName.value, loading = tracks.isEmpty(),
    )
}

@Composable
fun QueuePage() {
    var q by remember { mutableStateOf<List<Track>?>(null) }
    LaunchedEffect(Playback.trackId()) { q = runCatching { Api.queue() }.getOrNull() ?: emptyList() }
    val np = Remote.state.value
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 180.dp)) {
        item { Text("Queue", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp)) }
        if (np.title.isNotEmpty()) item {
            Text("Now playing", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Playback.snap.value?.track?.let { TrackRow(it, true) { Nav.playerOpen.value = true } }
        }
        item { Text("Next up", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        when {
            q == null -> item { LoadingBox() }
            q!!.isEmpty() -> item { EmptyNote("Nothing queued.") }
            else -> items(q!!) { t -> TrackRow(t, false, onMore = { Nav.trackMenu.value = t }) { Playback.play(uris = q!!.map { it.uri }, startUri = t.uri) } }
        }
    }
}
