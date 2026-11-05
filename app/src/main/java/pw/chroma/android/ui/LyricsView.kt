package pw.chroma.android.ui

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.isActive
import pw.chroma.android.*

/** Lyrics for the current track, loaded once per track. */
@Composable
fun rememberLyrics(np: NowPlaying): Pair<Lyrics?, Boolean> {
    var lyrics by remember { mutableStateOf<Lyrics?>(null) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(np.title, np.artist) {
        lyrics = null
        if (np.title.isEmpty()) return@LaunchedEffect
        loading = true
        lyrics = LyricsRepo.load(np.title, np.artist, np.album, np.durationMs)
        loading = false
    }
    return lyrics to loading
}

/** Playback position updated every frame while visible, for smooth word fills. */
@Composable
fun rememberFramePosition(np: NowPlaying): Long {
    var pos by remember { mutableLongStateOf(np.livePosition()) }
    LaunchedEffect(np) { while (isActive) { withFrameMillis { pos = np.livePosition() } } }
    return pos
}

/** Background colour behind lyrics: album colour ("original colours") or the static colour. */
@Composable
fun lyricsBackground(np: NowPlaying): Color =
    if (Settings.lyricsStaticColor) Color(Settings.lyricsColor)
    else if (Settings.lyricsOriginalColors && np.artColor != null) Color(np.artColor!!)
    else Color(0xFF2B2B2B)

/**
 * Spicy-style word-by-word lyrics. Each word fills from left to right as it's sung, lifts slightly
 * and glows; inactive lines dim and blur by distance from the active one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WordLyrics(
    lyrics: Lyrics,
    pos: Long,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 30.sp,
    compact: Boolean = false,
    topPad: Int = 0,
) {
    val idx = lyrics.indexAt(pos)
    val state = rememberLazyListState()
    LaunchedEffect(idx) { if (idx >= 0) state.animateScrollToItem(maxOf(0, idx - if (compact) 0 else 1), if (compact) 0 else -topPad) }
    val align = when (Settings.lyricsAlign) { 1 -> Arrangement.Center; 2 -> Arrangement.End; else -> Arrangement.Start }
    val textAlign = when (Settings.lyricsAlign) { 1 -> TextAlign.Center; 2 -> TextAlign.End; else -> TextAlign.Start }
    LazyColumn(state = state, modifier = modifier, userScrollEnabled = !compact) {
        itemsIndexed(lyrics.lines) { i, line ->
            val d = if (idx < 0) i + 1 else kotlin.math.abs(i - idx)
            val active = i == idx
            val blurPx = if (Build.VERSION.SDK_INT >= 31 && !active) (minOf(d, 4) * 1.6f * Settings.lyricsBlur).dp else 0.dp
            val scale by animateFloatAsState(if (active) 1f else .96f, tween(300), label = "scale")
            val alpha by animateFloatAsState(when { active -> 1f; i < idx -> .45f; else -> .55f }, tween(300), label = "alpha")
            Box(
                Modifier.fillMaxWidth().clickable { Playback.seekTo(line.startMs) }
                    .padding(vertical = if (compact) 5.dp else 10.dp)
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale; this.alpha = alpha
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
                            when (Settings.lyricsAlign) { 1 -> .5f; 2 -> 1f; else -> 0f }, .5f)
                    }
                    .blur(blurPx),
            ) {
                if (line.isBreak) {
                    if (active) InterludeDots(line, pos)
                } else if (!Settings.wordSync) {
                    Text(line.text, fontSize = fontSize, fontWeight = FontWeight.Bold, lineHeight = fontSize * 1.15f, textAlign = textAlign,
                        color = if (active) Color.White else Color.White.copy(alpha = .5f), modifier = Modifier.fillMaxWidth())
                } else {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = align) {
                        line.words.forEach { w ->
                            val p = if (i < idx) 1f else if (i > idx) 0f else w.progress(pos)
                            LyricWord(w.text, p, active, fontSize)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(if (compact) 40.dp else 320.dp)) }
    }
}

/** One word: dim base, bright copy clipped to the sung fraction, with a small lift and glow. */
@Composable
private fun LyricWord(text: String, progress: Float, active: Boolean, fontSize: TextUnit) {
    val style = TextStyle(fontSize = fontSize, fontWeight = FontWeight.ExtraBold, lineHeight = fontSize * 1.15f)
    val lift = if (active && progress > 0f && progress < 1f) -2f else 0f
    val glow = active && progress > 0f && progress < 1f
    Box(Modifier.graphicsLayer { translationY = lift * density }) {
        Text(text, style = style, color = Color.White.copy(alpha = .38f))
        Text(
            text, style = style.copy(shadow = if (glow) androidx.compose.ui.graphics.Shadow(Color.White.copy(alpha = .7f), blurRadius = 18f) else null),
            color = Color.White,
            modifier = Modifier.drawWithContent { clipRect(right = size.width * progress) { this@drawWithContent.drawContent() } },
        )
    }
}

@Composable
private fun InterludeDots(line: LyricLine, pos: Long) {
    val p = ((pos - line.startMs).toFloat() / (line.endMs - line.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        repeat(3) { k ->
            val lit = ((p * 3) - k).coerceIn(0f, 1f)
            Box(Modifier.size(12.dp).graphicsLayer { scaleX = .8f + lit * .3f; scaleY = .8f + lit * .3f }.clip(CircleShape).background(Color.White.copy(alpha = .3f + .7f * lit)))
        }
    }
}

/** Full-screen custom lyrics view (Chroma "Custom Lyrics View"). */
@Composable
fun LyricsScreen(np: NowPlaying) {
    val (lyrics, loading) = rememberLyrics(np)
    val pos = rememberFramePosition(np)
    val bg = lyricsBackground(np)
    Box(Modifier.fillMaxSize().background(C.bg)) {
        if (np.art != null && Settings.glassLyrics) {
            Image(BitmapPainter(np.art.asImageBitmap()), null, Modifier.fillMaxSize().blur(90.dp), contentScale = ContentScale.Crop, alpha = .8f)
            Box(Modifier.fillMaxSize().background(bg.copy(alpha = .55f)))
        } else Box(Modifier.fillMaxSize().background(bg))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .25f), Color.Transparent, Color.Black.copy(alpha = .35f)))))
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { Nav.lyricsOpen.value = false }) { Icon(Icons.Default.KeyboardArrowDown, "Close", Modifier.size(30.dp)) }
                Column(Modifier.weight(1f)) {
                    Text(np.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(np.artist, fontSize = 13.sp, color = Color.White.copy(alpha = .7f), maxLines = 1)
                }
            }
            LyricsBody(lyrics, loading, pos, Modifier.weight(1f), 32.sp, topPad = 0)
            PlayerMiniControls(np)
        }
    }
}

@Composable
fun LyricsBody(lyrics: Lyrics?, loading: Boolean, pos: Long, modifier: Modifier, fontSize: TextUnit, compact: Boolean = false, topPad: Int = 0) {
    Box(modifier) {
        when {
            loading -> Text("Loading lyrics…", color = Color.White.copy(alpha = .6f))
            lyrics == null || lyrics.isEmpty -> if (!Settings.hideLyricsOnError) Text("Couldn't load the lyrics for this song.", color = Color.White.copy(alpha = .6f))
            lyrics.instrumental -> Text("This song is instrumental.", color = Color.White.copy(alpha = .7f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            lyrics.synced -> WordLyrics(lyrics, pos, Modifier.fillMaxSize(), fontSize, compact, topPad)
            else -> Text(lyrics.plain.orEmpty(), fontSize = fontSize * .7f, fontWeight = FontWeight.Bold,
                modifier = Modifier.verticalScroll(rememberScrollState(), enabled = !compact))
        }
        if (lyrics != null && !lyrics.isEmpty && !compact) {
            Text(if (lyrics.wordTimed || !lyrics.synced) "Lyrics provided by LRCLIB" else "Lyrics provided by LRCLIB • word timing estimated",
                fontSize = 10.sp, color = Color.White.copy(alpha = .45f), modifier = Modifier.align(Alignment.BottomStart))
        }
    }
}
