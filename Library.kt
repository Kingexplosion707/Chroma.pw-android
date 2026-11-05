package pw.chroma.android

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable
private fun Cover(url: String?, size: Int, round: Boolean = false, modifier: Modifier = Modifier) {
    val shape = if (round) CircleShape else RoundedCornerShape(14.dp)
    AsyncImage(
        model = url, contentDescription = null, contentScale = ContentScale.Crop,
        modifier = modifier.size(size.dp).clip(shape).glass(shape, .5f),
    )
}

/** Runs a Spotify call, showing errors as a toast. */
@Composable
fun rememberSpotifyAction(): (suspend () -> Unit) -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    return { block ->
        scope.launch {
            try { block() } catch (e: Exception) { Toast.makeText(ctx, e.message ?: "Something went wrong", Toast.LENGTH_LONG).show() }
        }
    }
}

@Composable
fun ConnectScreen() {
    val ctx = LocalContext.current
    var id by remember { mutableStateOf(Api.clientId) }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("Connect Spotify", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Paste your app's Client ID from developer.spotify.com. Add  ${Api.REDIRECT}  as a Redirect URI there. " +
            "Playback control through Spotify's API needs Premium.", color = Color.White.copy(alpha = .7f), fontSize = 14.sp)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = id, onValueChange = { id = it.trim() }, singleLine = true, label = { Text("Client ID") },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        )
        Spacer(Modifier.height(16.dp))
        GlassPill("Connect", { if (id.isNotEmpty()) { Api.clientId = id; Api.beginLogin(ctx) } })
    }
}

@Composable
fun LibraryScreen() {
    if (!Api.loggedIn.value) { ConnectScreen(); return }
    var open by remember { mutableStateOf<Item?>(null) }
    open?.let { PlaylistScreen(it) { open = null }; return }

    var lists by remember { mutableStateOf<List<Item>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val act = rememberSpotifyAction()
    LaunchedEffect(Api.loggedIn.value) { act { lists = Api.playlists(); loading = false } }
    val all = listOf(Item("liked", "liked", "", "Liked Songs", "Your favourites")) + lists

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("Library", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        if (Api.userName.value.isNotEmpty()) Text(Api.userName.value, color = Color.White.copy(alpha = .6f), fontSize = 14.sp)
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 170.dp)) {
            items(all) { it0 ->
                Column(Modifier.clickable { open = it0 }.glass(RoundedCornerShape(22.dp), .6f).padding(10.dp)) {
                    if (it0.type == "liked") Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)).glass(RoundedCornerShape(14.dp)), Alignment.Center) {
                        Icon(Icons.Default.Favorite, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                    } else AsyncImage(it0.image, null, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
                    Spacer(Modifier.height(8.dp))
                    Text(it0.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(it0.subtitle, fontSize = 12.sp, color = Color.White.copy(alpha = .6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun PlaylistScreen(item: Item, onBack: () -> Unit) {
    var tracks by remember { mutableStateOf<List<Item>>(emptyList()) }
    val act = rememberSpotifyAction()
    LaunchedEffect(item.id) {
        act {
            tracks = when (item.type) { "liked" -> Api.liked(); "album" -> Api.albumTracks(item.id); else -> Api.playlistTracks(item.id) }
        }
    }
    fun playAll(start: String? = null) = act {
        when (item.type) {
            "liked" -> Api.play(uris = tracks.map { it.uri }, startUri = start)
            else -> Api.play(context = item.uri, startUri = start)
        }
    }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 18.dp), contentPadding = PaddingValues(bottom = 170.dp)) {
        item {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBackIosNew, "Back") }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (item.type == "liked") Box(Modifier.size(220.dp).glass(RoundedCornerShape(24.dp)), Alignment.Center) {
                    Icon(Icons.Default.Favorite, null, Modifier.size(90.dp), tint = MaterialTheme.colorScheme.primary)
                } else AsyncImage(item.image, null, Modifier.size(220.dp).clip(RoundedCornerShape(24.dp)).glass(RoundedCornerShape(24.dp), .4f), contentScale = ContentScale.Crop)
                Spacer(Modifier.height(14.dp))
                Text(item.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                Text(item.subtitle, color = Color.White.copy(alpha = .6f))
                Spacer(Modifier.height(14.dp))
                GlassPill("Play", { playAll() })
                Spacer(Modifier.height(16.dp))
            }
        }
        items(tracks) { t -> TrackRow(t) { playAll(t.uri) } }
    }
}

@Composable
fun TrackRow(t: Item, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(t.image, 52)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.subtitle, fontSize = 13.sp, color = Color.White.copy(alpha = .6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun SearchScreen() {
    if (!Api.loggedIn.value) { ConnectScreen(); return }
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Item>>(emptyList()) }
    var open by remember { mutableStateOf<Item?>(null) }
    open?.let { PlaylistScreen(it) { open = null }; return }
    val act = rememberSpotifyAction()
    fun go() = act { if (q.isNotBlank()) results = Api.search(q) }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("Search", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = q, onValueChange = { q = it }, singleLine = true, placeholder = { Text("Songs, albums, artists, playlists") },
            leadingIcon = { Icon(Icons.Default.Search, null) }, shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { go() }),
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 170.dp)) {
            val groups = listOf("track" to "Songs", "album" to "Albums", "artist" to "Artists", "playlist" to "Playlists")
            groups.forEach { (type, title) ->
                val part = results.filter { it.type == type }
                if (part.isNotEmpty()) {
                    item { Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)) }
                    items(part) { r ->
                        TrackRow(r) {
                            when (r.type) {
                                "track" -> act { Api.play(uris = listOf(r.uri)) }
                                "album", "playlist" -> open = r
                                else -> act { Api.play(context = r.uri) }
                            }
                        }
                    }
                }
            }
        }
    }
}
