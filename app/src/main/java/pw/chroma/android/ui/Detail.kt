package pw.chroma.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import pw.chroma.android.*

@Composable
fun BackBar(title: String = "", visibleTitle: Boolean = false) {
    Row(Modifier.statusBarsPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { Nav.back() }) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(Color.Black.copy(alpha = .35f)), Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", Modifier.size(20.dp))
            }
        }
        if (visibleTitle) Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PlaylistScreen(id: String) {
    var data by remember(id) { mutableStateOf<Pair<Header, List<Track>>?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(id) { try { data = Api.playlist(id) } catch (e: Exception) { error = e.message } }
    val h = data?.first
    CollectionPage(
        title = h?.item?.name ?: "", subtitle = h?.let { "${it.total} songs" } ?: "", image = h?.item?.image,
        tracks = data?.second.orEmpty(), contextUri = "spotify:playlist:$id", description = h?.description.orEmpty(),
        creator = h?.item?.owner.orEmpty(), followers = h?.followers ?: 0, loading = data == null && error == null, error = error,
    )
}

@Composable
fun AlbumScreen(id: String) {
    var data by remember(id) { mutableStateOf<Pair<Header, List<Track>>?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(id) { try { data = Api.album(id) } catch (e: Exception) { error = e.message } }
    val h = data?.first
    val tracks = data?.second.orEmpty()
    CollectionPage(
        title = h?.item?.name ?: "", subtitle = "Album • ${h?.release?.take(4).orEmpty()}", image = h?.item?.image, tracks = tracks,
        contextUri = "spotify:album:$id", description = "", creator = tracks.firstOrNull()?.artistLine.orEmpty(),
        creatorArtistId = tracks.firstOrNull()?.artists?.firstOrNull()?.id, numbered = true, loading = data == null && error == null, error = error,
    )
}

/** Shared page for playlists, albums and Liked Songs, with Chroma's playlist options. */
@Composable
fun CollectionPage(
    title: String, subtitle: String, image: String?, tracks: List<Track>, contextUri: String?, description: String, creator: String,
    followers: Int = 0, liked: Boolean = false, color: Color? = null, numbered: Boolean = false, creatorArtistId: String? = null,
    loading: Boolean = false, error: String? = null,
) {
    val ctx = LocalContext.current
    val imgColor = rememberImageColor(image, Color(0xFF3A3A3A))
    val top = color ?: imgColor
    var find by remember { mutableStateOf("") }
    val cur = Playback.trackId()
    val playingHere = contextUri != null && Playback.snap.value?.contextUri == contextUri && Remote.state.value.playing
    val shown = if (find.isBlank()) tracks else tracks.filter { it.name.contains(find, true) || it.artistLine.contains(find, true) }
    val uris = tracks.map { it.uri }
    val totalMs = tracks.sumOf { it.durationMs }
    fun playFrom(t: Track?) {
        if (contextUri != null) Playback.play(context = contextUri, startUri = t?.uri) else Playback.play(uris = uris, startUri = t?.uri)
    }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 180.dp)) {
            item {
                Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(top, C.bg)))) {
                    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 48.dp)) {
                        if (Settings.playlistFullCover && image != null) {
                            AsyncImage(image, null, Modifier.fillMaxWidth().aspectRatio(1f), contentScale = ContentScale.Crop)
                        } else Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), Alignment.Center) {
                            if (liked) LikedTile(240.dp) else Cover(image, 240.dp, RoundedCornerShape(if (Settings.glassPlaylist) 12.dp else 2.dp))
                        }
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            Spacer(Modifier.height(14.dp))
                            Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (Settings.playlistDescription && description.isNotBlank()) {
                                Spacer(Modifier.height(6.dp)); Text(description, fontSize = 13.sp, color = C.sub, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                            if (Settings.playlistCreator && creator.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(enabled = creatorArtistId != null) { creatorArtistId?.let { Nav.push(Route.Artist(it)) } }) {
                                    Avatar(null, creator, 22.dp) {}
                                    Spacer(Modifier.width(8.dp))
                                    Text(creator, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            if (Settings.playlistMetadata) {
                                Spacer(Modifier.height(6.dp))
                                val parts = listOfNotNull(subtitle.ifBlank { null }, followers.takeIf { it > 0 }?.let { "%,d saves".format(it) }, totalMs.takeIf { it > 0 }?.let { fmtLong(it) })
                                Text(parts.joinToString(" • "), fontSize = 13.sp, color = C.sub)
                            }
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { contextUri?.let { Playback.share(ctx, it, title) } }) { Icon(Icons.Default.Share, "Share", tint = C.sub) }
                                IconButton(onClick = { Playback.toast("Downloads aren't available — Spotify only allows them in its own app.") }) {
                                    Icon(Icons.Default.DownloadForOffline, "Download", tint = C.sub)
                                }
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = {
                                    haptic(ctx)
                                    if (Playback.snap.value?.shuffle != true) Playback.toggleShuffle()
                                    playFrom(tracks.randomOrNull())
                                }) { Icon(Icons.Default.Shuffle, "Shuffle", tint = if (Playback.snap.value?.shuffle == true) C.accent else C.sub, modifier = Modifier.size(28.dp)) }
                                Spacer(Modifier.width(8.dp))
                                PlayButton(playingHere) { if (playingHere) Playback.playPause() else playFrom(null) }
                            }
                        }
                    }
                }
            }
            if (!Settings.playlistHideFind && tracks.size > 8) item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(38.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = .08f)).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, null, Modifier.size(18.dp), tint = C.sub)
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(find, { find = it }, singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 14.sp), cursorBrush = SolidColor(C.accent),
                        modifier = Modifier.weight(1f), decorationBox = { inner -> if (find.isEmpty()) Text("Find in ${if (numbered) "album" else "playlist"}", color = C.sub, fontSize = 14.sp); inner() })
                }
            }
            if (loading) item { LoadingBox() }
            if (error != null) item { EmptyNote(error) }
            itemsIndexed(shown, key = { i, t -> "$i${t.uri}" }) { i, t ->
                TrackRow(t, t.id == cur, showCover = !numbered, index = if (numbered) i + 1 else null, onMore = { Nav.trackMenu.value = t }) { playFrom(t) }
                if (Settings.playlistDividers) HorizontalDivider(Modifier.padding(start = if (numbered) 44.dp else 78.dp, end = 16.dp), color = Color.White.copy(alpha = .07f))
            }
        }
        BackBar()
    }
}

@Composable
fun ArtistScreen(id: String) {
    var h by remember(id) { mutableStateOf<Header?>(null) }
    var top by remember(id) { mutableStateOf<List<Track>>(emptyList()) }
    var albums by remember(id) { mutableStateOf<List<Item>>(emptyList()) }
    var showAll by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        runCatching { h = Api.artist(id) }
        runCatching { top = Api.artistTopTracks(id) }
        runCatching { albums = Api.artistAlbums(id) }
    }
    val uri = "spotify:artist:$id"
    val cur = Playback.trackId()
    val playingHere = Playback.snap.value?.contextUri == uri && Remote.state.value.playing
    Box(Modifier.fillMaxSize().background(C.bg)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 180.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(340.dp)) {
                    h?.item?.image?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, C.bg))))
                    Text(h?.item?.name.orEmpty(), fontSize = 44.sp, fontWeight = FontWeight.Black, lineHeight = 46.sp,
                        modifier = Modifier.align(Alignment.BottomStart).padding(16.dp))
                }
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("%,d followers".format(h?.followers ?: 0), fontSize = 13.sp, color = C.sub)
                        if (!h?.genres.isNullOrEmpty()) Text(h!!.genres.take(3).joinToString(" • "), fontSize = 12.sp, color = C.faint, maxLines = 1)
                    }
                    PlayButton(playingHere) { if (playingHere) Playback.playPause() else Playback.play(context = uri) }
                }
            }
            if (top.isNotEmpty()) {
                item { SectionTitle("Popular") }
                itemsIndexed(top.take(if (showAll) 10 else 5)) { i, t ->
                    TrackRow(t, t.id == cur, showCover = true, onMore = { Nav.trackMenu.value = t }) { Playback.play(uris = top.map { it.uri }, startUri = t.uri) }
                }
                if (top.size > 5) item {
                    Text(if (showAll) "Show less" else "See more", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = C.sub,
                        modifier = Modifier.padding(16.dp).clickable { showAll = !showAll })
                }
            }
            if (albums.isNotEmpty()) {
                item { SectionTitle("Discography") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(albums, key = { it.uri }) { a -> MediaCard(a) { Nav.open(a.uri) } }
                    }
                }
            }
            if (h == null) item { LoadingBox() }
        }
        BackBar()
    }
}
