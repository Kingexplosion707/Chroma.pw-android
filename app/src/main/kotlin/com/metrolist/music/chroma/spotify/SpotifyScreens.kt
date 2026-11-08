package com.metrolist.music.chroma.spotify

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.metrolist.music.LocalNavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.chroma.ChromaPrefs
import com.metrolist.music.constants.ListItemHeight
import com.metrolist.music.constants.ThumbnailCornerRadius
import com.metrolist.music.ui.component.ChipsRow
import com.metrolist.music.ui.component.GridItem
import com.metrolist.music.ui.component.ListItem
import com.metrolist.music.ui.component.NavigationTitle
import java.util.Calendar

// ---------------- routing ----------------
private val spotifyIdRegex = Regex("^[0-9A-Za-z]{22}$")

/** True for Spotify ids (22 base62 characters), which YouTube ids never are. */
fun isSpotifyId(id: String?): Boolean = ChromaPrefs.spotifyMode.value && id != null && spotifyIdRegex.matches(id)

fun spotifyRoute(uri: String): String? {
    val id = uri.substringAfterLast(':')
    return when {
        uri.contains(":playlist:") -> "online_playlist/$id"
        uri.contains(":album:") -> "album/$id"
        uri.contains(":artist:") -> "artist/$id"
        uri.contains(":collection") -> "spotify/liked"
        else -> null
    }
}

private fun Context.shareSpotify(uri: String, title: String) {
    val p = uri.split(':')
    if (p.size < 3) return
    val i = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, "https://open.spotify.com/${p[1]}/${p[2]}")
        .putExtra(Intent.EXTRA_SUBJECT, title)
    startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ---------------- caches ----------------
object SpotifyHomeCache {
    var recent by mutableStateOf<List<Pair<SpTrack, String?>>>(emptyList())
    var playlists by mutableStateOf<List<SpItem>>(emptyList())
    var albums by mutableStateOf<List<SpItem>>(emptyList())
    var topArtists by mutableStateOf<List<SpItem>>(emptyList())
    var topTracks by mutableStateOf<List<SpTrack>>(emptyList())
    var artists by mutableStateOf<List<SpItem>>(emptyList())
    var shows by mutableStateOf<List<SpItem>>(emptyList())
    var liked by mutableStateOf<List<SpTrack>>(emptyList())
    private var loadedAt = 0L

    suspend fun load(force: Boolean = false) {
        if (!SpotifyApi.loggedIn.value) return
        if (!force && playlists.isNotEmpty() && System.currentTimeMillis() - loadedAt < 5 * 60_000) return
        loadedAt = System.currentTimeMillis()
        runCatching { playlists = SpotifyApi.playlists() }
        runCatching { recent = SpotifyApi.recentlyPlayed() }
        runCatching { topTracks = SpotifyApi.topTracks() }
        runCatching { topArtists = SpotifyApi.topArtists() }
        runCatching { albums = SpotifyApi.savedAlbums() }
        runCatching { artists = SpotifyApi.followedArtists() }
        runCatching { shows = SpotifyApi.savedShows() }
    }

    /** Recent playlists/albums/artists from listening history, newest first. */
    fun recentContexts(): List<SpItem> {
        val byUri = playlists.associateBy { it.uri }
        val out = LinkedHashMap<String, SpItem>()
        for ((t, ctx) in recent) {
            val item = when {
                ctx != null && byUri[ctx] != null -> byUri[ctx]
                ctx?.contains(":collection") == true -> SpItem("liked", "liked", "spotify:collection", "Liked Songs", "Playlist")
                (ctx == null || ctx.contains(":album:")) && t.albumId.isNotEmpty() ->
                    SpItem("album", t.albumId, "spotify:album:${t.albumId}", t.albumName, t.artistLine, t.image)
                else -> null
            } ?: continue
            out.putIfAbsent(item.uri, item)
        }
        return out.values.toList()
    }
}

// ---------------- shared pieces ----------------
@Composable
fun SpThumb(url: String?, size: Dp, shape: Shape = RoundedCornerShape(ThumbnailCornerRadius), liked: Boolean = false) {
    Box(Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        if (liked) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF4A1FD8), Color(0xFF9FB6E8)))), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.favorite), null, tint = Color.White, modifier = Modifier.size(size / 2.6f))
            }
        } else if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(painterResource(R.drawable.music_note), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(size / 2.4f))
        }
    }
}

@Composable
fun SpTrackRow(
    t: SpTrack,
    onClick: () -> Unit,
    index: Int? = null,
    showThumb: Boolean = true,
) {
    val ctx = LocalContext.current
    val nav = LocalNavController.current
    val now by SpotifyRemote.state.collectAsState()
    var menu by remember { mutableStateOf(false) }
    val active = SpotifyBridge.trackId(now) == t.id
    ListItem(
        title = t.name,
        subtitle = (if (index != null && !showThumb) "" else "") + t.artistLine,
        badges = {
            if (t.explicit) Icon(painterResource(R.drawable.explicit), null, modifier = Modifier.size(18.dp).padding(end = 2.dp))
        },
        thumbnailContent = {
            if (showThumb) SpThumb(t.image, if (ChromaPrefs.denserRows.value) 40.dp else ListItemHeight - 16.dp)
            else Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Text("${index ?: ""}", style = MaterialTheme.typography.bodyLarge, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.more_vert), null) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Play next") }, leadingIcon = { Icon(painterResource(R.drawable.playlist_play), null) },
                        onClick = { menu = false; SpotifyBridge.launch { SpotifyApi.addToQueue(t.uri); SpotifyBridge.toast("Added to queue") } })
                    DropdownMenuItem(text = { Text("Add to Liked Songs") }, leadingIcon = { Icon(painterResource(R.drawable.favorite_border), null) },
                        onClick = { menu = false; SpotifyBridge.launch { SpotifyApi.setSaved(t.id, true); SpotifyBridge.toast("Added to Liked Songs") } })
                    t.artists.firstOrNull()?.let { a ->
                        DropdownMenuItem(text = { Text("Go to artist") }, leadingIcon = { Icon(painterResource(R.drawable.artist), null) },
                            onClick = { menu = false; nav.navigate("artist/${a.id}") })
                    }
                    if (t.albumId.isNotEmpty()) DropdownMenuItem(text = { Text("Go to album") }, leadingIcon = { Icon(painterResource(R.drawable.album), null) },
                        onClick = { menu = false; nav.navigate("album/${t.albumId}") })
                    DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(painterResource(R.drawable.share), null) },
                        onClick = { menu = false; ctx.shareSpotify(t.uri, t.name) })
                    DropdownMenuItem(text = { Text("Open in Spotify") }, leadingIcon = { Icon(painterResource(R.drawable.link), null) },
                        onClick = { menu = false; SpotifyRemote.openSpotify(ctx, t.uri) })
                }
            }
        },
        isActive = active,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
fun SpItemRow(item: SpItem, pinned: Boolean = false, onClick: () -> Unit) {
    val dense = ChromaPrefs.denserRows.value
    ListItem(
        title = item.name,
        subtitle = item.subtitle,
        badges = { if (pinned) Icon(painterResource(R.drawable.ic_push_pin), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp).padding(end = 2.dp)) },
        thumbnailContent = {
            SpThumb(item.image, if (dense) 40.dp else ListItemHeight - 16.dp, if (item.type == "artist") CircleShape else RoundedCornerShape(ThumbnailCornerRadius), liked = item.type == "liked")
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
fun SpGridItem(item: SpItem, onClick: () -> Unit) {
    GridItem(
        title = item.name,
        subtitle = item.subtitle,
        thumbnailContent = {
            SpThumb(item.image, maxWidth, if (item.type == "artist") CircleShape else RoundedCornerShape(ThumbnailCornerRadius * 2), liked = item.type == "liked")
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun SpCarousel(items: List<SpItem>) {
    val nav = LocalNavController.current
    LazyRow(contentPadding = PaddingValues(horizontal = 6.dp)) {
        items(items, key = { it.uri }) { i -> SpGridItem(i) { spotifyRoute(i.uri)?.let { r -> nav.navigate(r) } } }
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** Sign-in and notification-access prompts shown at the top of the Spotify pages. */
@Composable
fun SpotifySetupCards() {
    val ctx = LocalContext.current
    val hasAccess = remember { mutableStateOf(SpotifyRemote.hasAccess(ctx)) }
    LaunchedEffect(Unit) { while (true) { hasAccess.value = SpotifyRemote.hasAccess(ctx); kotlinx.coroutines.delay(2000) } }
    fun notificationsAllowed() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val notifications = remember { mutableStateOf(notificationsAllowed()) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifications.value = it }
    if (!hasAccess.value) {
        SetupCard(
            "Connect to the Spotify app",
            "Allow Chroma notification access so it can see and control what Spotify plays on this phone, count your plays and show the Live Activity.",
            "Allow access",
        ) { ctx.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    if (hasAccess.value && !notifications.value && ChromaPrefs.liveActivity.value) {
        SetupCard(
            "Allow notifications",
            "For the Live Activity: the song and its current lyric line in the status bar and on the lock screen.",
            "Allow",
        ) { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
    if (!SpotifyApi.loggedIn.value) {
        SetupCard(
            "Log in with Spotify",
            "Your playlists, albums, artists, search and Connect come from your Spotify account. Starting music from Chroma needs Premium; controlling what's playing works on any account.",
            "Log in",
        ) { SpotifyApi.beginLogin(ctx) }
    }
}

@Composable
private fun SetupCard(title: String, text: String, button: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onClick) { Text(button) }
        }
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

// ---------------- Home ----------------
@Composable
fun SpotifyHomeScreen() {
    val ctx = LocalContext.current
    val nav = LocalNavController.current
    var filter by remember { mutableStateOf(0) }
    LaunchedEffect(SpotifyApi.loggedIn.value) { SpotifyHomeCache.load() }
    val contexts = remember(SpotifyHomeCache.recent, SpotifyHomeCache.playlists) { SpotifyHomeCache.recentContexts() }
    val shortcuts = remember(contexts, SpotifyHomeCache.playlists) {
        (listOf(SpItem("liked", "liked", "spotify:collection", "Liked Songs", "Playlist")) + contexts + SpotifyHomeCache.playlists).distinctBy { it.uri }.take(8)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()) {
        item { SpotifySetupCards() }
        if (ChromaPrefs.homeFilterPills.value && SpotifyApi.loggedIn.value) item {
            ChipsRow(chips = listOf(0 to "All", 1 to "Music", 2 to "Podcasts"), currentValue = filter, onValueUpdate = { filter = it })
        }
        item { Text(greeting(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }

        if (filter == 2) {
            item { NavigationTitle(title = "Your shows") }
            items(SpotifyHomeCache.shows, key = { it.uri }) { s -> SpItemRow(s) { SpotifyRemote.openSpotify(ctx, s.uri) } }
            return@LazyColumn
        }

        if (ChromaPrefs.homeShortcuts.value && SpotifyApi.loggedIn.value) item {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                shortcuts.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { s ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(10.dp)).clickable { spotifyRoute(s.uri)?.let { r -> nav.navigate(r) } },
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    SpThumb(s.image, 56.dp, RoundedCornerShape(0.dp), liked = s.type == "liked")
                                    Spacer(Modifier.width(10.dp))
                                    Text(s.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 8.dp))
                                }
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        if (contexts.isNotEmpty()) item {
            NavigationTitle(title = "Jump back in")
            SpCarousel(contexts.take(14))
        }
        if (ChromaPrefs.homeRecentlyPlayed.value && SpotifyHomeCache.recent.isNotEmpty()) item {
            val tracks = SpotifyHomeCache.recent.map { it.first }.distinctBy { it.id }.take(20)
            NavigationTitle(title = "Recently played", onPlayAllClick = { SpotifyBridge.playUri(ctx, uris = tracks.map { it.uri }) })
            LazyHorizontalGrid(rows = GridCells.Fixed(4), modifier = Modifier.fillMaxWidth().height(ListItemHeight * 4)) {
                items(tracks, key = { it.id }) { t ->
                    Box(Modifier.width(300.dp)) { SpTrackRow(t, onClick = { SpotifyBridge.playUri(ctx, uris = tracks.map { it.uri }, startUri = t.uri) }) }
                }
            }
        }
        if (ChromaPrefs.homeOnRepeat.value && SpotifyHomeCache.topTracks.isNotEmpty()) item {
            val tracks = SpotifyHomeCache.topTracks
            NavigationTitle(title = "On repeat", onPlayAllClick = { SpotifyBridge.playUri(ctx, uris = tracks.map { it.uri }) })
            LazyRow(contentPadding = PaddingValues(horizontal = 6.dp)) {
                items(tracks, key = { it.id }) { t ->
                    GridItem(title = t.name, subtitle = t.artistLine, thumbnailContent = { SpThumb(t.image, maxWidth, RoundedCornerShape(ThumbnailCornerRadius * 2)) },
                        modifier = Modifier.clickable { SpotifyBridge.playUri(ctx, uris = tracks.map { it.uri }, startUri = t.uri) })
                }
            }
        }
        if (ChromaPrefs.homeTopArtists.value && SpotifyHomeCache.topArtists.isNotEmpty()) item {
            NavigationTitle(title = "Your top artists")
            SpCarousel(SpotifyHomeCache.topArtists)
        }
        if (SpotifyHomeCache.playlists.isNotEmpty()) item {
            NavigationTitle(title = "Your playlists", onClick = { nav.navigate("library") })
            SpCarousel(SpotifyHomeCache.playlists.take(20))
        }
        if (SpotifyHomeCache.albums.isNotEmpty()) item {
            NavigationTitle(title = "Albums you saved")
            SpCarousel(SpotifyHomeCache.albums.take(20))
        }
        if (ChromaPrefs.homePodcasts.value && filter == 0 && SpotifyHomeCache.shows.isNotEmpty()) item {
            NavigationTitle(title = "Your shows")
            LazyRow(contentPadding = PaddingValues(horizontal = 6.dp)) {
                items(SpotifyHomeCache.shows, key = { it.uri }) { s -> SpGridItem(s) { SpotifyRemote.openSpotify(ctx, s.uri) } }
            }
        }
        if (SpotifyApi.loggedIn.value && SpotifyHomeCache.playlists.isEmpty() && SpotifyHomeCache.recent.isEmpty()) item { Loading() }
    }
}

// ---------------- Search ----------------
private val genres = listOf(
    "Pop" to 0xFF8D67AB, "Hip-Hop" to 0xFFBA5D07, "Rock" to 0xFFE91429, "Indie" to 0xFF608108,
    "Dance/Electronic" to 0xFF0D73EC, "R&B" to 0xFFDC148C, "Latin" to 0xFFE1118C, "Chill" to 0xFF477D95,
    "Workout" to 0xFF777777, "Focus" to 0xFF503750, "Sleep" to 0xFF1E3264, "Party" to 0xFF8C1932,
    "Mood" to 0xFFE1118C, "Country" to 0xFFD84000, "Jazz" to 0xFF8D67AB, "Classical" to 0xFF7D4B32,
    "K-pop" to 0xFF148A08, "Metal" to 0xFF503750, "Soul" to 0xFFDC148C, "Gaming" to 0xFFE8115B,
)

private fun recentSearches(ctx: Context) =
    ctx.getSharedPreferences("chroma", Context.MODE_PRIVATE).getString("recentSearches", "").orEmpty().split('\n').filter { it.isNotBlank() }

private fun saveRecent(ctx: Context, q: String) {
    val list = (listOf(q) + recentSearches(ctx).filter { it != q }).take(15)
    ctx.getSharedPreferences("chroma", Context.MODE_PRIVATE).edit().putString("recentSearches", list.joinToString("\n")).apply()
}

@Composable
fun SpotifySearchScreen() {
    val ctx = LocalContext.current
    val nav = LocalNavController.current
    var q by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<SpotifyApi.SearchResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(0) }
    LaunchedEffect(q) {
        if (q.isBlank()) { result = null; return@LaunchedEffect }
        kotlinx.coroutines.delay(350)
        loading = true
        result = runCatching { SpotifyApi.search(q) }.getOrNull()
        loading = false
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()) {
        item {
            OutlinedTextField(
                value = q, onValueChange = { q = it }, singleLine = true,
                placeholder = { Text("Artists, songs, albums and playlists") },
                leadingIcon = { Icon(painterResource(R.drawable.search), null) },
                trailingIcon = { if (q.isNotEmpty()) IconButton(onClick = { q = "" }) { Icon(painterResource(R.drawable.close), null) } },
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (q.isNotBlank()) saveRecent(ctx, q) }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        if (!SpotifyApi.loggedIn.value) { item { SpotifySetupCards() }; return@LazyColumn }
        val r = result
        when {
            q.isBlank() -> {
                val recents = recentSearches(ctx)
                if (recents.isNotEmpty()) {
                    item { NavigationTitle(title = "Recently searched") }
                    items(recents) { s ->
                        ListItem(title = s, subtitle = "", thumbnailContent = { Icon(painterResource(R.drawable.history), null, modifier = Modifier.size(24.dp)) },
                            modifier = Modifier.clickable { q = s })
                    }
                }
                item { NavigationTitle(title = "Browse categories") }
                items(genres.chunked(2)) { row ->
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { (name, color) ->
                            Box(Modifier.weight(1f).height(92.dp).clip(RoundedCornerShape(14.dp)).background(Color(color)).clickable { q = name }) {
                                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(12.dp))
                                Box(Modifier.align(Alignment.BottomEnd).padding(6.dp).size(52.dp).rotate(22f).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = .18f)))
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            r == null -> if (loading) item { Loading() }
            else -> {
                item { ChipsRow(chips = listOf(0 to "Top", 1 to "Songs", 2 to "Artists", 3 to "Albums", 4 to "Playlists"), currentValue = filter, onValueUpdate = { filter = it }) }
                if (filter == 0) {
                    val top = r.artists.firstOrNull() ?: r.albums.firstOrNull()
                    if (top != null) item {
                        NavigationTitle(title = "Top result")
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).clickable { saveRecent(ctx, q); spotifyRoute(top.uri)?.let { r -> nav.navigate(r) } },
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                SpThumb(top.image, 88.dp, if (top.type == "artist") CircleShape else RoundedCornerShape(10.dp))
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(top.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(top.type.replaceFirstChar { it.uppercase() }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { SpotifyBridge.playUri(ctx, context = top.uri) }) { Icon(painterResource(R.drawable.play), null) }
                            }
                        }
                    }
                }
                if (filter == 0 || filter == 1) {
                    if (filter == 0 && r.tracks.isNotEmpty()) item { NavigationTitle(title = "Songs") }
                    items(r.tracks.take(if (filter == 0) 5 else 50), key = { "t" + it.id }) { t ->
                        SpTrackRow(t, onClick = { saveRecent(ctx, q); SpotifyBridge.playUri(ctx, uris = r.tracks.map { it.uri }, startUri = t.uri) })
                    }
                }
                listOf(2 to r.artists, 3 to r.albums, 4 to r.playlists).forEach { (f, list) ->
                    if (filter == 0 || filter == f) {
                        if (filter == 0 && list.isNotEmpty()) item { NavigationTitle(title = listOf("", "", "Artists", "Albums", "Playlists")[f]) }
                        items(list.take(if (filter == 0) 4 else 50), key = { "$f" + it.uri }) { i -> SpItemRow(i) { saveRecent(ctx, q); spotifyRoute(i.uri)?.let { r -> nav.navigate(r) } } }
                    }
                }
            }
        }
    }
}

// ---------------- Library ----------------
private val sortNames = listOf("Recents", "Recently added", "Alphabetical", "Creator")

@Composable
fun SpotifyLibraryScreen() {
    val nav = LocalNavController.current
    var filter by remember { mutableStateOf(0) }
    var sortOpen by remember { mutableStateOf(false) }
    LaunchedEffect(SpotifyApi.loggedIn.value) { SpotifyHomeCache.load() }
    val recentOrder = remember(SpotifyHomeCache.recent) { SpotifyHomeCache.recent.mapNotNull { it.second }.distinct().withIndex().associate { (i, u) -> u to i } }
    val sort = ChromaPrefs.librarySort.value
    val items = remember(filter, SpotifyHomeCache.playlists, SpotifyHomeCache.albums, SpotifyHomeCache.artists, sort, recentOrder) {
        val base = when (filter) {
            1 -> SpotifyHomeCache.playlists
            2 -> SpotifyHomeCache.albums
            3 -> SpotifyHomeCache.artists
            else -> SpotifyHomeCache.playlists + SpotifyHomeCache.albums + SpotifyHomeCache.artists
        }
        when (sort) {
            0 -> base.sortedBy { recentOrder[it.uri] ?: Int.MAX_VALUE }
            1 -> base.sortedByDescending { it.addedAt }
            2 -> base.sortedBy { it.name.lowercase() }
            else -> base.sortedBy { it.owner.ifBlank { it.subtitle }.lowercase() }
        }
    }
    val liked = SpItem("liked", "liked", "spotify:collection", "Liked Songs", "Playlist • ${SpotifyHomeCache.liked.size.takeIf { it > 0 }?.let { "$it songs" } ?: "Pinned"}")
    val showLiked = filter == 0 || filter == 1
    Column(Modifier.fillMaxSize()) {
        if (!SpotifyApi.loggedIn.value) {
            LazyColumn(contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()) { item { SpotifySetupCards() } }
            return@Column
        }
        if (!ChromaPrefs.hideFilterChips.value) {
            ChipsRow(chips = listOf(0 to "All", 1 to "Playlists", 2 to "Albums", 3 to "Artists"), currentValue = filter, onValueUpdate = { filter = it })
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Row(Modifier.clickable { sortOpen = true }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.list), null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(sortNames[sort], style = MaterialTheme.typography.labelLarge)
                }
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    sortNames.forEachIndexed { i, n -> DropdownMenuItem(text = { Text(n) }, onClick = { ChromaPrefs.librarySort.value = i; sortOpen = false }) }
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { ChromaPrefs.libraryGrid.value = !ChromaPrefs.libraryGrid.value }) {
                Icon(painterResource(if (ChromaPrefs.libraryGrid.value) R.drawable.list else R.drawable.grid_view), null)
            }
        }
        if (ChromaPrefs.libraryGrid.value) {
            LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()) {
                if (showLiked) item { SpGridItem(liked) { nav.navigate("spotify/liked") } }
                items(items, key = { it.uri }) { i -> SpGridItem(i) { spotifyRoute(i.uri)?.let { r -> nav.navigate(r) } } }
            }
        } else {
            LazyColumn(contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()) {
                if (showLiked) item { SpItemRow(liked, pinned = true) { nav.navigate("spotify/liked") } }
                items(items, key = { it.uri }) { i -> SpItemRow(i) { spotifyRoute(i.uri)?.let { r -> nav.navigate(r) } } }
                if (items.isEmpty() && SpotifyHomeCache.playlists.isEmpty()) item { Loading() }
            }
        }
    }
}

// ---------------- detail pages ----------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScaffold(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val nav = LocalNavController.current
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = { nav.navigateUp() }) { Icon(painterResource(R.drawable.arrow_back), null) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(), content = content)
    }
}

@Composable
private fun CollectionHeader(
    title: String, subtitle: String, image: String?, description: String, liked: Boolean,
    playing: Boolean, onPlay: () -> Unit, onShuffle: () -> Unit, onShare: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SpThumb(image, 240.dp, RoundedCornerShape(16.dp), liked = liked)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        if (description.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onPlay, modifier = Modifier.weight(1f)) {
                Icon(painterResource(if (playing) R.drawable.pause else R.drawable.play), null)
                Spacer(Modifier.width(6.dp)); Text(if (playing) "Pause" else "Play")
            }
            FilledTonalButton(onClick = onShuffle, modifier = Modifier.weight(1f)) {
                Icon(painterResource(R.drawable.shuffle), null)
                Spacer(Modifier.width(6.dp)); Text("Shuffle")
            }
            if (onShare != null) IconButton(onClick = onShare) { Icon(painterResource(R.drawable.share), null) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun fmtLong(ms: Long): String {
    val m = ms / 60000
    return if (m >= 60) "${m / 60} hr ${m % 60} min" else "$m min"
}

@Composable
fun SpotifyCollectionScreen(type: String, id: String) {
    val ctx = LocalContext.current
    var header by remember(id) { mutableStateOf<SpHeader?>(null) }
    var tracks by remember(id) { mutableStateOf<List<SpTrack>?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    val snap by SpotifyBridge.snap.collectAsState()
    val now by SpotifyRemote.state.collectAsState()
    LaunchedEffect(id) {
        try {
            when (type) {
                "liked" -> { tracks = SpotifyApi.liked(500).also { SpotifyHomeCache.liked = it } }
                "album" -> SpotifyApi.album(id).let { (h, t) -> header = h; tracks = t }
                else -> SpotifyApi.playlist(id).let { (h, t) -> header = h; tracks = t }
            }
        } catch (e: Exception) { error = e.message }
    }
    val contextUri = when (type) { "liked" -> null; "album" -> "spotify:album:$id"; else -> "spotify:playlist:$id" }
    val list = tracks.orEmpty()
    val playingHere = contextUri != null && snap?.contextUri == contextUri && now.playing
    fun playFrom(t: SpTrack?) {
        if (contextUri != null) SpotifyBridge.playUri(ctx, context = contextUri, startUri = t?.uri)
        else SpotifyBridge.playUri(ctx, uris = list.map { it.uri }, startUri = t?.uri)
    }
    val title = if (type == "liked") "Liked Songs" else header?.item?.name.orEmpty()
    val subtitle = when (type) {
        "liked" -> "${list.size} songs"
        "album" -> listOfNotNull(list.firstOrNull()?.artistLine, header?.release?.take(4)).joinToString(" · ")
        else -> header?.item?.owner.orEmpty()
    }
    DetailScaffold(title) {
        item {
            CollectionHeader(
                title = title, subtitle = subtitle, image = header?.item?.image, description = header?.description.orEmpty(), liked = type == "liked",
                playing = playingHere,
                onPlay = { if (playingHere) SpotifyBridge.pause() else playFrom(null) },
                onShuffle = { SpotifyBridge.setShuffle(true); playFrom(list.randomOrNull()) },
                onShare = contextUri?.let { u -> { ctx.shareSpotify(u, title) } },
            )
            val total = list.sumOf { it.durationMs }
            if (list.isNotEmpty()) Text("${list.size} songs · ${fmtLong(total)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        if (tracks == null && error == null) item { Loading() }
        error?.let { e -> item { Text(e, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } }
        itemsIndexed(list, key = { i, t -> "$i${t.uri}" }) { i, t ->
            SpTrackRow(t, onClick = { playFrom(t) }, index = i + 1, showThumb = type != "album")
        }
    }
}

@Composable
fun SpotifyArtistScreen(id: String) {
    val ctx = LocalContext.current
    val nav = LocalNavController.current
    var header by remember(id) { mutableStateOf<SpHeader?>(null) }
    var top by remember(id) { mutableStateOf<List<SpTrack>>(emptyList()) }
    var albums by remember(id) { mutableStateOf<List<SpItem>>(emptyList()) }
    var showAll by remember { mutableStateOf(false) }
    val snap by SpotifyBridge.snap.collectAsState()
    val now by SpotifyRemote.state.collectAsState()
    LaunchedEffect(id) {
        runCatching { header = SpotifyApi.artist(id) }
        runCatching { top = SpotifyApi.artistTopTracks(id) }
        runCatching { albums = SpotifyApi.artistAlbums(id) }
    }
    val uri = "spotify:artist:$id"
    val playingHere = snap?.contextUri == uri && now.playing
    DetailScaffold(header?.item?.name.orEmpty()) {
        item {
            Box(Modifier.fillMaxWidth().aspectRatio(1.2f).padding(horizontal = 16.dp).clip(RoundedCornerShape(24.dp))) {
                header?.item?.image?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .65f)))))
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    Text(header?.item?.name.orEmpty(), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, color = Color.White)
                    Text("%,d followers".format(header?.followers ?: 0), color = Color.White.copy(alpha = .8f), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { if (playingHere) SpotifyBridge.pause() else SpotifyBridge.playUri(ctx, context = uri) }, modifier = Modifier.weight(1f)) {
                    Icon(painterResource(if (playingHere) R.drawable.pause else R.drawable.play), null); Spacer(Modifier.width(6.dp)); Text(if (playingHere) "Pause" else "Play")
                }
                FilledTonalButton(onClick = { SpotifyBridge.setShuffle(true); SpotifyBridge.playUri(ctx, context = uri) }, modifier = Modifier.weight(1f)) {
                    Icon(painterResource(R.drawable.shuffle), null); Spacer(Modifier.width(6.dp)); Text("Shuffle")
                }
                IconButton(onClick = { ctx.shareSpotify(uri, header?.item?.name.orEmpty()) }) { Icon(painterResource(R.drawable.share), null) }
            }
            if (!header?.genres.isNullOrEmpty()) Text(header!!.genres.take(4).joinToString(" · "), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        if (top.isNotEmpty()) {
            item { NavigationTitle(title = "Top songs", onPlayAllClick = { SpotifyBridge.playUri(ctx, uris = top.map { it.uri }) }) }
            items(top.take(if (showAll) 10 else 5), key = { "top" + it.id }) { t -> SpTrackRow(t, onClick = { SpotifyBridge.playUri(ctx, uris = top.map { it.uri }, startUri = t.uri) }) }
            if (top.size > 5) item {
                Text(if (showAll) "Show less" else "See all", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(16.dp).clickable { showAll = !showAll })
            }
        }
        val full = albums.filter { !it.subtitle.contains("Single", true) }
        if (albums.isNotEmpty()) item {
            NavigationTitle(title = "Albums & singles")
            LazyRow(contentPadding = PaddingValues(horizontal = 6.dp)) {
                items((full + albums).distinctBy { it.uri }, key = { it.uri }) { a -> SpGridItem(a) { nav.navigate("album/${a.id}") } }
            }
        }
        if (header == null) item { Loading() }
    }
}
