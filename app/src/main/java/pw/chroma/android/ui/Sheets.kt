package pw.chroma.android.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import pw.chroma.android.*

/** Bottom sheet container with a dimmed scrim and a glass panel. */
@Composable
fun Sheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f)).clickable(onClick = onDismiss)) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.75f)
                .glass(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)).background(Color(0xE6161616))
                .clickable(enabled = false) {}.navigationBarsPadding().padding(top = 10.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(width = 40.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .35f)))
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
fun BottomSheets(which: String) {
    val close = { Nav.sheet.value = null }
    Sheet(close) {
        when (which) {
            "queue" -> QueueSheet()
            "connect" -> ConnectSheet()
            "timer" -> TimerSheet()
        }
    }
}

@Composable
private fun QueueSheet() {
    var q by remember { mutableStateOf<List<Track>?>(null) }
    LaunchedEffect(Playback.trackId()) { q = runCatching { Api.queue() }.getOrNull() ?: emptyList() }
    Text("Queue", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
    Playback.snap.value?.track?.let {
        Text("Now playing", fontSize = 14.sp, color = C.sub, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        TrackRow(it, true) {}
    }
    Text("Next from: ${Playback.contextName.value.ifBlank { "Spotify" }}", fontSize = 14.sp, color = C.sub, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    LazyColumn {
        when {
            q == null -> item { LoadingBox() }
            q!!.isEmpty() -> item { EmptyNote("Nothing up next.") }
            else -> items(q!!) { t -> TrackRow(t, false, onMore = { Nav.trackMenu.value = t }) { Playback.play(uris = q!!.map { it.uri }, startUri = t.uri) } }
        }
    }
}

@Composable
private fun ConnectSheet() {
    var devices by remember { mutableStateOf<List<Device>?>(null) }
    LaunchedEffect(Unit) { while (true) { devices = runCatching { Api.devices() }.getOrNull() ?: emptyList(); delay(5000) } }
    Text("Connect to a device", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
    Spacer(Modifier.height(8.dp))
    if (!Api.loggedIn.value) { EmptyNote("Log in to see your Spotify devices."); return }
    LazyColumn {
        when {
            devices == null -> item { LoadingBox() }
            devices!!.isEmpty() -> item { EmptyNote("No devices found. Open Spotify on a phone, speaker or computer.") }
            else -> items(devices!!) { d ->
                Row(Modifier.fillMaxWidth().clickable { Playback.launch { Api.transfer(d.id); delay(800); Playback.refresh() }; Nav.sheet.value = null }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(deviceIcon(d.type), null, tint = if (d.active) C.accent else Color.White, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, color = if (d.active) C.accent else Color.White, fontSize = 16.sp)
                        Text(if (d.active) "Listening on" else d.type, fontSize = 12.sp, color = C.sub)
                    }
                    if (d.active) Icon(Icons.Default.GraphicEq, null, tint = C.accent)
                }
            }
        }
    }
}

private fun deviceIcon(type: String): ImageVector = when (type.lowercase()) {
    "smartphone" -> Icons.Default.Smartphone
    "computer" -> Icons.Default.Computer
    "speaker" -> Icons.Default.Speaker
    "tv" -> Icons.Default.Tv
    "tablet" -> Icons.Default.Tablet
    else -> Icons.Default.Devices
}

@Composable
private fun TimerSheet() {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { now = SystemClock.elapsedRealtime(); delay(500) } }
    val end = SleepTimer.endAt.longValue
    Text("Sleep timer", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
    if (end > now) {
        Text(fmt(end - now), fontSize = 52.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
        SheetAction(Icons.Default.Close, "Turn off timer") { SleepTimer.cancel() }
    } else {
        val opts = (if (Settings.sleepOneMinute) listOf(1) else emptyList()) + listOf(5, 10, 15, 30, 45, 60)
        LazyColumn { items(opts) { m -> SheetAction(Icons.Default.Bedtime, if (m == 60) "1 hour" else "$m minutes") { SleepTimer.start(ctx, m); Nav.sheet.value = null } } }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = C.sub)
        Spacer(Modifier.width(16.dp))
        Text(text, fontSize = 16.sp)
    }
}

/** Long-press / "…" menu for a track. */
@Composable
fun TrackMenu(t: Track) {
    val ctx = LocalContext.current
    var pickPlaylist by remember { mutableStateOf(false) }
    val close = { Nav.trackMenu.value = null }
    Sheet(close) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(t.image, 52.dp); Spacer(Modifier.width(12.dp))
            Column { Text(t.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(t.artistLine, fontSize = 13.sp, color = C.sub, maxLines = 1) }
        }
        HorizontalDivider(color = Color.White.copy(alpha = .1f), modifier = Modifier.padding(vertical = 6.dp))
        if (pickPlaylist) {
            val mine = HomeCache.playlists.filter { it.owner == Api.userName.value || it.owner.isBlank() }
            LazyColumn { items(mine) { p -> ItemRow(p, dense = true) { Playback.launch { Api.addToPlaylist(p.id, t.uri); Playback.toast("Added to ${p.name}") }; close() } } }
            return@Sheet
        }
        LazyColumn {
            item { SheetAction(Icons.Default.FavoriteBorder, "Add to Liked Songs") { Playback.launch { Api.setSaved(t.id, true); Playback.toast("Added to Liked Songs") }; close() } }
            item { SheetAction(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to playlist") { pickPlaylist = true } }
            item { SheetAction(Icons.Default.QueueMusic, "Add to queue") { Playback.launch { Api.addToQueue(t.uri); Playback.toast("Added to queue") }; close() } }
            item { SheetAction(Icons.Default.PlayCircleOutline, "Play now") { Playback.play(uris = listOf(t.uri)); close() } }
            t.artists.firstOrNull()?.let { a -> item { SheetAction(Icons.Default.Person, "Go to artist") { close(); Nav.playerOpen.value = false; Nav.push(Route.Artist(a.id)) } } }
            if (t.albumId.isNotEmpty()) item { SheetAction(Icons.Default.Album, "Go to album") { close(); Nav.playerOpen.value = false; Nav.push(Route.Album(t.albumId)) } }
            item { SheetAction(Icons.Default.Share, "Share") { Playback.share(ctx, t.uri, t.name); close() } }
            item { SheetAction(Icons.Default.Bedtime, "Sleep timer") { close(); Nav.sheet.value = "timer" } }
        }
    }
}
