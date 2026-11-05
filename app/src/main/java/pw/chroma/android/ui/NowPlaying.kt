package pw.chroma.android.ui

import android.media.AudioManager
import android.content.Context
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import pw.chroma.android.*

/** Canvas video URL for the current track (via your sp_dc cookie or custom endpoint). */
@Composable
fun rememberCanvasUrl(): String? {
    val id = Playback.trackId()
    var url by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(id, Settings.canvas, Settings.spDc) {
        url = null
        if (!Settings.canvas || id == null) return@LaunchedEffect
        if (Settings.spDc.isNotBlank()) url = CanvasProvider.find("spotify:track:$id")
    }
    return url
}

@OptIn(UnstableApi::class)
@Composable
fun CanvasVideo(url: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(ctx).build().apply {
            volume = 0f; repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(url)); prepare(); playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = {
            PlayerView(it).apply {
                useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                this.player = player
            }
        },
        update = { it.player = player },
        modifier = modifier,
    )
}

@Composable
fun NowPlayingScreen(np: NowPlaying) {
    val ctx = LocalContext.current
    val canvasUrl = rememberCanvasUrl()
    val tint = np.artColor?.let { Color(it) } ?: Color(0xFF3A3A3A)
    val snap = Playback.snap.value
    Box(Modifier.fillMaxSize().background(C.bg)) {
        // backdrop
        if (canvasUrl != null) {
            CanvasVideo(canvasUrl, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .45f), Color.Transparent, Color.Black.copy(alpha = .75f)))))
        } else if (Settings.playerBackdrop && np.art != null) {
            Image(BitmapPainter(np.art.asImageBitmap()), null, Modifier.fillMaxSize().blur(80.dp), contentScale = ContentScale.Crop, alpha = .85f)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f)))
        } else Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(tint, C.bg))))

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding()) {
            // header
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { Nav.playerOpen.value = false }) { Icon(Icons.Default.KeyboardArrowDown, "Close", Modifier.size(32.dp)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Playback.contextLabel(), fontSize = 10.sp, letterSpacing = 1.5.sp, color = Color.White.copy(alpha = .75f))
                    Text(Playback.contextName.value.ifBlank { np.album }, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { snap?.track?.let { Nav.trackMenu.value = it } }) { Icon(Icons.Default.MoreVert, "More") }
            }
            val area = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            // artwork (Spotify hides it when a Canvas plays)
            if (canvasUrl == null) {
                Spacer(Modifier.height(24.dp))
                val shape = RoundedCornerShape(if (Settings.roundArtwork) 12.dp else 2.dp)
                Box(area.aspectRatio(1f).clip(shape).background(C.elevated).pointerInput(Settings.doubleTap, Settings.playerZones) {
                    detectTapGestures(onDoubleTap = { o -> handleArtTap(ctx, o.x / size.width) })
                }) { if (np.art != null) Image(BitmapPainter(np.art.asImageBitmap()), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                Spacer(Modifier.height(28.dp))
            } else Spacer(Modifier.fillMaxWidth().height(420.dp).pointerInput(Settings.doubleTap) { detectTapGestures(onDoubleTap = { o -> handleArtTap(ctx, o.x / size.width) }) })

            // title
            Row(area, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(np.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(np.artist, fontSize = 16.sp, color = Color.White.copy(alpha = .7f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { snap?.track?.artists?.firstOrNull()?.let { Nav.playerOpen.value = false; Nav.push(Route.Artist(it.id)) } })
                }
                if (!Settings.isHidden("addTo")) IconButton(onClick = { haptic(ctx); Playback.toggleLike() }) {
                    Icon(if (Playback.liked.value) Icons.Default.CheckCircle else Icons.Default.AddCircleOutline, "Save",
                        tint = if (Playback.liked.value) C.accent else Color.White, modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            SeekBar(np, area)
            Spacer(Modifier.height(4.dp))
            Controls(np, area)
            Spacer(Modifier.height(6.dp))
            Row(area, verticalAlignment = Alignment.CenterVertically) {
                if (!Settings.isHidden("connect")) Row(Modifier.clickable { Nav.sheet.value = "connect" }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Devices, "Connect", tint = if (snap?.device != null) C.accent else Color.White, modifier = Modifier.size(20.dp))
                    snap?.device?.let { Spacer(Modifier.width(6.dp)); Text(it.name, fontSize = 12.sp, color = C.accent, maxLines = 1) }
                }
                Spacer(Modifier.weight(1f))
                if (!Settings.isHidden("share")) IconButton(onClick = { snap?.track?.let { Playback.share(ctx, it.uri, it.name) } }) { Icon(Icons.Default.Share, "Share", Modifier.size(20.dp)) }
                if (!Settings.isHidden("queue")) IconButton(onClick = { Nav.sheet.value = "queue" }) { Icon(Icons.Default.QueueMusic, "Queue", Modifier.size(22.dp)) }
            }
            Spacer(Modifier.height(18.dp))
            // cards below the player
            if (!Settings.isHidden("allCards")) {
                if (!Settings.isHidden("lyricsCard")) LyricsCard(np, tint)
                if (!Settings.isHidden("aboutArtist")) AboutArtistCard(snap?.track)
                if (!Settings.isHidden("credits")) CreditsCard(snap?.track)
                if (!Settings.isHidden("queuePreview")) QueueCard()
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

private fun handleArtTap(ctx: Context, fx: Float) {
    haptic(ctx)
    val zone = if (Settings.playerZones == 1) (if (fx < .33f) 0 else if (fx > .66f) 2 else 1) else (if (fx < .5f) 0 else 2)
    when (Settings.doubleTap) {
        1 -> when (zone) { 0 -> Playback.seekTo(Remote.state.value.livePosition() - 10_000); 2 -> Playback.seekTo(Remote.state.value.livePosition() + 10_000); else -> Playback.playPause() }
        2 -> when (zone) { 0 -> Playback.previous(); 2 -> Playback.next(); else -> Playback.playPause() }
    }
}

@Composable
fun SeekBar(np: NowPlaying, modifier: Modifier) {
    val ctx = LocalContext.current
    var pos by remember { mutableLongStateOf(np.livePosition()) }
    var dragging by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(np) { while (true) { if (!dragging) pos = np.livePosition(); delay(250) } }
    val dur = np.durationMs.coerceAtLeast(1)
    Column(modifier) {
        Slider(
            value = if (dragging) drag else (pos.toFloat() / dur).coerceIn(0f, 1f),
            onValueChange = { if (!dragging) haptic(ctx); dragging = true; drag = it },
            onValueChangeFinished = { Playback.seekTo((drag * dur).toLong()); pos = (drag * dur).toLong(); dragging = false; haptic(ctx) },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = .25f)),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(fmt(if (dragging) (drag * dur).toLong() else pos), fontSize = 11.sp, color = Color.White.copy(alpha = .65f))
            Spacer(Modifier.weight(1f))
            Text(fmt(np.durationMs), fontSize = 11.sp, color = Color.White.copy(alpha = .65f))
        }
    }
}

@Composable
fun Controls(np: NowPlaying, modifier: Modifier) {
    val ctx = LocalContext.current
    val snap = Playback.snap.value
    Row(modifier, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        if (!Settings.isHidden("shuffle")) IconButton(onClick = { haptic(ctx); Playback.toggleShuffle() }) {
            Icon(Icons.Default.Shuffle, "Shuffle", tint = if (snap?.shuffle == true) C.accent else Color.White)
        } else Spacer(Modifier.size(48.dp))
        IconButton(onClick = { haptic(ctx); Playback.previous() }, Modifier.size(56.dp)) { Icon(Icons.Default.SkipPrevious, "Previous", Modifier.size(42.dp)) }
        Box(Modifier.size(70.dp).clip(CircleShape).background(Color.White).clickable { haptic(ctx, true); Playback.playPause() }, Alignment.Center) {
            Icon(if (np.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause", tint = Color.Black, modifier = Modifier.size(40.dp))
        }
        IconButton(onClick = { haptic(ctx); Playback.next() }, Modifier.size(56.dp)) { Icon(Icons.Default.SkipNext, "Next", Modifier.size(42.dp)) }
        if (!Settings.isHidden("repeat")) IconButton(onClick = { haptic(ctx); Playback.cycleRepeat() }) {
            Icon(if (snap?.repeat == "track") Icons.Default.RepeatOne else Icons.Default.Repeat, "Repeat", tint = if (snap?.repeat != null && snap.repeat != "off") C.accent else Color.White)
        } else Spacer(Modifier.size(48.dp))
    }
}

/** Compact controls under the full lyrics view. */
@Composable
fun PlayerMiniControls(np: NowPlaying) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
        SeekBar(np, Modifier.fillMaxWidth())
        Controls(np, Modifier.fillMaxWidth())
    }
}

@Composable
private fun Card(title: String, color: Color, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()
            .then(if (Settings.glassLyrics) Modifier.glass(RoundedCornerShape(14.dp), tint = color) else Modifier.clip(RoundedCornerShape(14.dp)).background(color))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(16.dp),
    ) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun LyricsCard(np: NowPlaying, tint: Color) {
    val (lyrics, loading) = rememberLyrics(np)
    val pos = rememberFramePosition(np)
    if (!loading && (lyrics == null || lyrics.isEmpty) && Settings.hideLyricsOnError) return
    Card(if (Settings.isHidden("lyricsPreview")) "Lyrics" else "Lyrics preview", lyricsBackground(np).copy(alpha = .85f), onClick = { Nav.lyricsOpen.value = true }) {
        LyricsBody(lyrics, loading, pos, Modifier.fillMaxWidth().height(260.dp), 22.sp, compact = true)
        Text("Show lyrics", fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(50)).background(Color.White).padding(horizontal = 14.dp, vertical = 6.dp), color = Color.Black)
    }
}

@Composable
private fun AboutArtistCard(t: Track?) {
    val id = t?.artists?.firstOrNull()?.id ?: return
    var h by remember(id) { mutableStateOf<Header?>(null) }
    LaunchedEffect(id) { h = runCatching { Api.artist(id) }.getOrNull() }
    val a = h ?: return
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.card)
        .clickable { Nav.playerOpen.value = false; Nav.push(Route.Artist(id)) }) {
        Box(Modifier.fillMaxWidth().height(200.dp)) {
            AsyncImage(a.item.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Text("About the artist", fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
        }
        Column(Modifier.padding(16.dp)) {
            Text(a.item.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("%,d followers".format(a.followers), fontSize = 13.sp, color = C.sub)
            if (a.genres.isNotEmpty()) Text(a.genres.take(4).joinToString(" • "), fontSize = 13.sp, color = C.sub, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun CreditsCard(t: Track?) {
    t ?: return
    Card("Credits", C.card) {
        t.artists.forEach { a ->
            Row(Modifier.fillMaxWidth().clickable { Nav.playerOpen.value = false; Nav.push(Route.Artist(a.id)) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.name, fontWeight = FontWeight.SemiBold)
                    Text(if (a == t.artists.first()) "Main Artist" else "Featured Artist", fontSize = 12.sp, color = C.sub)
                }
            }
        }
        if (t.albumName.isNotEmpty()) Text("From ${t.albumName}", fontSize = 12.sp, color = C.sub, modifier = Modifier.padding(top = 6.dp).clickable { Nav.playerOpen.value = false; Nav.push(Route.Album(t.albumId)) })
    }
}

@Composable
private fun QueueCard() {
    var q by remember { mutableStateOf<List<Track>>(emptyList()) }
    LaunchedEffect(Playback.trackId()) { q = runCatching { Api.queue() }.getOrDefault(emptyList()) }
    if (q.isEmpty()) return
    Card("Next in queue", C.card, onClick = { Nav.sheet.value = "queue" }) {
        q.take(3).forEach { t ->
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(t.image, 40.dp); Spacer(Modifier.width(10.dp))
                Column { Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp); Text(t.artistLine, fontSize = 12.sp, color = C.sub, maxLines = 1) }
            }
        }
    }
}

fun volumePercent(ctx: Context): Int {
    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    return am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
}

@Suppress("unused") private val keepAlign = TextAlign.Start
