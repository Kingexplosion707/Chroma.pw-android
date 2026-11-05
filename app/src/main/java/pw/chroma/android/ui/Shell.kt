package pw.chroma.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pw.chroma.android.*

private fun builtInTabs(): List<TabDef> = listOf(
    TabDef("home", "Home", Route.Home),
    TabDef("search", "Search", Route.Search),
    TabDef("library", "Your Library", Route.Library),
    TabDef("liked", "Liked", Route.Liked),
    TabDef("queue", "Queue", Route.Queue),
)

fun allTabs(): List<TabDef> {
    val custom = Settings.customTabs.lines().filter { it.contains('|') }.mapIndexedNotNull { i, l ->
        val (title, uri) = l.split('|', limit = 2)
        routeFor(uri)?.let { TabDef("custom$i", title, it) }
    }
    return builtInTabs() + custom
}

fun visibleTabs(): List<TabDef> {
    val all = allTabs().associateBy { it.id }
    val order = Settings.tabOrder.split(',').mapNotNull { all[it.trim()] }
    val customs = allTabs().filter { it.id.startsWith("custom") && it !in order }
    return (order + customs).ifEmpty { builtInTabs().take(3) }
}

private fun iconFor(id: String, selected: Boolean): ImageVector = when (id) {
    "home" -> if (selected) Icons.Filled.Home else Icons.Outlined.Home
    "search" -> Icons.Filled.Search
    "library" -> if (selected) Icons.Filled.LibraryMusic else Icons.Outlined.LibraryMusic
    "liked" -> if (selected) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder
    "queue" -> Icons.Filled.QueueMusic
    else -> if (selected) Icons.Filled.Star else Icons.Outlined.StarBorder
}

@Composable
fun AppShell() {
    val tabs = visibleTabs()
    var initialised by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!initialised) { Nav.tab.value = tabs.firstOrNull { it.id == Settings.launchTab }?.id ?: tabs.first().id; initialised = true }
    }
    val current = tabs.firstOrNull { it.id == Nav.tab.value } ?: tabs.first()
    val stack = Nav.stack(current.id, current.root)
    val np by Remote.state.collectAsStateWithLifecycle()

    BackHandler(enabled = Nav.trackMenu.value != null || Nav.sheet.value != null || Nav.lyricsOpen.value || Nav.playerOpen.value || stack.size > 1) { Nav.back() }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        // page content
        AnimatedContent(
            targetState = current.id to stack.lastOrNull(),
            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
            label = "page",
        ) { (_, route) -> RouteContent(route ?: current.root) }

        // bottom: mini player + glass tab bar
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(40.dp).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, C.bg.copy(alpha = .0f)))))
            if (np.connected && np.title.isNotEmpty()) MiniPlayer(np)
            TabBar(tabs, current.id)
        }

        AnimatedVisibility(
            Nav.playerOpen.value,
            enter = slideInVertically(tween(320)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(260)) { it } + fadeOut(tween(200)),
        ) { NowPlayingScreen(np) }

        AnimatedVisibility(Nav.lyricsOpen.value, enter = fadeIn(tween(220)), exit = fadeOut(tween(200))) { LyricsScreen(np) }

        Nav.sheet.value?.let { BottomSheets(it) }
        Nav.trackMenu.value?.let { TrackMenu(it) }
    }
}

@Composable
private fun RouteContent(route: Route) {
    when (route) {
        Route.Home -> HomeScreen()
        Route.Search -> SearchScreen()
        Route.Library -> LibraryScreen()
        Route.Liked -> LikedScreen()
        Route.Queue -> QueuePage()
        is Route.Playlist -> PlaylistScreen(route.id)
        is Route.Album -> AlbumScreen(route.id)
        is Route.Artist -> ArtistScreen(route.id)
        Route.Settings -> SettingsHome()
        is Route.SettingsPage -> SettingsPage(route.page)
    }
}

@Composable
private fun TabBar(tabs: List<TabDef>, current: String) {
    val ctx = LocalContext.current
    val glass = Settings.glassTabBar
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp)
            .glass(RoundedCornerShape(34.dp), enabled = glass).padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { t ->
            val sel = t.id == current
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(28.dp))
                    .background(if (sel && glass) Color.White.copy(alpha = .14f) else Color.Transparent)
                    .clickable {
                        haptic(ctx)
                        if (sel) Nav.reselect(t.id) else Nav.tab.value = t.id
                    }
                    .padding(vertical = if (Settings.tabHideLabels) 12.dp else 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(iconFor(t.id, sel), t.title, tint = if (sel) Color.White else C.sub, modifier = Modifier.size(25.dp))
                if (!Settings.tabHideLabels) Text(t.title, fontSize = 10.sp, color = if (sel) Color.White else C.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MiniPlayer(np: NowPlaying) {
    val ctx = LocalContext.current
    val tint = if (Settings.npbAlbumTint && np.artColor != null) Color(np.artColor!!) else Color(0xFF2A2A2A)
    var pos by remember { mutableLongStateOf(np.livePosition()) }
    LaunchedEffect(np) { while (true) { pos = np.livePosition(); kotlinx.coroutines.delay(500) } }
    val snap = Playback.snap.value
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp)
            .glass(RoundedCornerShape(16.dp), enabled = Settings.glassNowPlayingBar, tint = tint)
            .background(if (Settings.glassNowPlayingBar) tint.copy(alpha = .35f) else tint)
            .clickable { Nav.playerOpen.value = true },
    ) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val shape = if (Settings.npbRoundArtwork) CircleShape else RoundedCornerShape(6.dp)
            if (np.art != null) Image(BitmapPainter(np.art.asImageBitmap()), null, Modifier.size(42.dp).clip(shape), contentScale = ContentScale.Crop)
            else Box(Modifier.size(42.dp).clip(shape).background(C.elevated))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                if (Settings.npbTwoLines) {
                    Text(np.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (snap?.device != null && !snap.device.name.equals(android.os.Build.MODEL, true)) {
                            Icon(Icons.Default.Speaker, null, tint = C.accent, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(snap.device.name, fontSize = 12.sp, color = C.accent, maxLines = 1)
                        } else Text(np.artist, fontSize = 12.sp, color = C.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else Text("${np.title} • ${np.artist}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!Settings.npbHideConnect) IconButton(onClick = { Nav.sheet.value = "connect" }) { Icon(Icons.Default.Devices, "Connect", tint = C.sub) }
            if (Settings.npbSaveButton) IconButton(onClick = { haptic(ctx); Playback.toggleLike() }) {
                Icon(if (Playback.liked.value) Icons.Default.CheckCircle else Icons.Default.AddCircleOutline, "Save", tint = if (Playback.liked.value) C.accent else Color.White)
            }
            IconButton(onClick = { haptic(ctx); Playback.playPause() }) {
                Icon(if (np.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause", modifier = Modifier.size(30.dp))
            }
        }
        val frac = if (np.durationMs > 0) (pos.toFloat() / np.durationMs).coerceIn(0f, 1f) else 0f
        Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp).fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(Color.White.copy(alpha = .2f))) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Color.White))
        }
    }
}
