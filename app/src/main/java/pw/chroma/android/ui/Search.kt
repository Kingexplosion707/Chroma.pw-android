package pw.chroma.android.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import pw.chroma.android.*

private val genres = listOf(
    "Pop" to 0xFF8D67AB, "Hip-Hop" to 0xFFBA5D07, "Rock" to 0xFFE91429, "Indie" to 0xFF608108,
    "Dance/Electronic" to 0xFF0D73EC, "R&B" to 0xFFDC148C, "Latin" to 0xFFE1118C, "Chill" to 0xFF477D95,
    "Workout" to 0xFF777777, "Focus" to 0xFF503750, "Sleep" to 0xFF1E3264, "Party" to 0xFF8C1932,
    "Mood" to 0xFFE1118C, "Country" to 0xFFD84000, "Jazz" to 0xFF8D67AB, "Classical" to 0xFF7D4B32,
    "K-pop" to 0xFF148A08, "Metal" to 0xFF503750, "Soul" to 0xFFDC148C, "Gaming" to 0xFFE8115B,
)

private fun recentSearches(ctx: Context): List<String> =
    ctx.getSharedPreferences("chroma", Context.MODE_PRIVATE).getString("recentSearches", "").orEmpty().split('\n').filter { it.isNotBlank() }

private fun saveRecent(ctx: Context, q: String) {
    val list = (listOf(q) + recentSearches(ctx).filter { it != q }).take(12)
    ctx.getSharedPreferences("chroma", Context.MODE_PRIVATE).edit().putString("recentSearches", list.joinToString("\n")).apply()
}

@Composable
fun SearchScreen() {
    if (!Api.loggedIn.value) { ConnectScreen(); return }
    val ctx = LocalContext.current
    var active by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Api.SearchResult?>(null) }
    var filter by remember { mutableStateOf<Int?>(null) }
    var loading by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val fm = LocalFocusManager.current

    LaunchedEffect(q) {
        if (q.isBlank()) { result = null; return@LaunchedEffect }
        delay(350)
        loading = true
        result = runCatching { Api.search(q) }.getOrNull()
        loading = false
    }
    LaunchedEffect(active) { if (active) runCatching { focus.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        HeaderGradient(C.accent.copy(alpha = .5f), Settings.gradientSearch && !active)
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            if (!active) {
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(Api.userImage.value, Api.userName.value) { Nav.push(Route.Settings) }
                    Spacer(Modifier.width(12.dp))
                    Text("Search", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
            }
            // search field
            Row(
                Modifier.padding(16.dp).fillMaxWidth().height(48.dp)
                    .then(if (Settings.glassSearch) Modifier.glass(RoundedCornerShape(if (active) 12.dp else 8.dp)) else Modifier.clip(RoundedCornerShape(8.dp)).background(if (active) C.elevated else Color.White))
                    .clickable { active = true }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val fg = if (Settings.glassSearch || active) Color.White else Color.Black
                Icon(Icons.Default.Search, null, tint = fg)
                Spacer(Modifier.width(10.dp))
                if (active) {
                    BasicTextField(
                        q, { q = it }, singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                        cursorBrush = SolidColor(C.accent), modifier = Modifier.weight(1f).focusRequester(focus),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { if (q.isNotBlank()) saveRecent(ctx, q); fm.clearFocus() }),
                        decorationBox = { inner -> if (q.isEmpty()) Text("What do you want to listen to?", color = C.sub, fontSize = 16.sp); inner() },
                    )
                    if (q.isNotEmpty()) Icon(Icons.Default.Close, "Clear", Modifier.clickable { q = "" })
                    Spacer(Modifier.width(8.dp))
                    Text("Cancel", fontSize = 14.sp, modifier = Modifier.clickable { active = false; q = ""; fm.clearFocus() })
                } else Text("What do you want to listen to?", color = fg.copy(alpha = .75f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }

            when {
                !active -> BrowseAll { g -> active = true; q = g }
                q.isBlank() -> RecentSearches(recentSearches(ctx)) { q = it }
                else -> Results(result, loading, filter, { filter = it }) { saveRecent(ctx, q) }
            }
        }
    }
}

@Composable
private fun BrowseAll(onGenre: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 180.dp)) {
        item { Text("Browse all", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 10.dp)) }
        items(genres.chunked(2)) { row ->
            Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (name, color) ->
                    Box(Modifier.weight(1f).height(96.dp).clip(RoundedCornerShape(8.dp)).background(Color(color)).clickable { onGenre(name) }) {
                        Text(name, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(12.dp))
                        Box(Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 6.dp).size(54.dp).rotate(22f).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = .18f)))
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RecentSearches(list: List<String>, onPick: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 180.dp)) {
        if (list.isNotEmpty()) item { Text("Recent searches", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp)) }
        else item { EmptyNote("Search for songs, artists, albums and playlists.") }
        items(list) { s ->
            Row(Modifier.fillMaxWidth().clickable { onPick(s) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.History, null, tint = C.sub)
                Spacer(Modifier.width(14.dp))
                Text(s, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun Results(r: Api.SearchResult?, loading: Boolean, filter: Int?, onFilter: (Int?) -> Unit, onUse: () -> Unit) {
    val cur = Playback.trackId()
    Column {
        PillRow(listOf("Songs", "Artists", "Albums", "Playlists"), filter, onFilter, Modifier.padding(bottom = 8.dp))
        if (r == null) { if (loading) LoadingBox(); return@Column }
        LazyColumn(contentPadding = PaddingValues(bottom = 180.dp)) {
            if (filter == null) {
                val top: Item? = r.artists.firstOrNull() ?: r.albums.firstOrNull()
                if (top != null) item {
                    Text("Top result", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
                    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.card).clickable { onUse(); Nav.open(top.uri) }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Cover(top.image, 92.dp, if (top.type == "artist") CircleShape else RoundedCornerShape(4.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(top.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(top.type.replaceFirstChar { it.uppercase() }, color = C.sub, fontSize = 13.sp)
                        }
                        PlayButton(false, 46.dp) { Playback.play(context = top.uri) }
                    }
                }
            }
            if (filter == null || filter == 0) {
                if (filter == null && r.tracks.isNotEmpty()) item { SectionTitle("Songs") }
                items(r.tracks.take(if (filter == null) 5 else 50)) { t ->
                    TrackRow(t, t.id == cur, onMore = { Nav.trackMenu.value = t }) { onUse(); Playback.play(uris = r.tracks.map { it.uri }, startUri = t.uri) }
                }
            }
            listOf(1 to r.artists, 2 to r.albums, 3 to r.playlists).forEach { (f, list) ->
                if (filter == null || filter == f) {
                    if (filter == null && list.isNotEmpty()) item { SectionTitle(listOf("", "Artists", "Albums", "Playlists")[f]) }
                    items(list.take(if (filter == null) 4 else 50), key = { "$f${it.uri}" }) { i -> ItemRow(i) { onUse(); Nav.open(i.uri) } }
                }
            }
        }
    }
}
