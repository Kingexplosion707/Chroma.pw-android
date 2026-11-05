package pw.chroma.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import pw.chroma.android.Settings

// ---------------- colors ----------------
object C {
    val bg @Composable get() = if (Settings.amoled) Color.Black else Color(0xFF121212)
    val card = Color(0xFF1F1F1F)
    val elevated = Color(0xFF2A2A2A)
    val text = Color.White
    val sub = Color(0xFFB3B3B3)
    val faint = Color(0xFF7A7A7A)
    val accent @Composable get() = Color(Settings.accent)
}

@Composable
fun ChromaTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = C.accent, onPrimary = Color.Black, background = C.bg, surface = C.bg,
        surfaceVariant = C.elevated, onSurface = Color.White, onBackground = Color.White, secondaryContainer = C.elevated,
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

fun haptic(ctx: Context, strong: Boolean = false) {
    if (!Settings.haptics) return
    val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
    v.vibrate(VibrationEffect.createPredefined(if (strong) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_TICK))
}

// ---------------- liquid glass ----------------
/**
 * iOS-style liquid glass: translucent body, specular highlight along the top edge, bright rim that fades
 * down the sides, and a faint inner shadow at the bottom. Style follows Settings.glassStyle.
 */
fun Modifier.glass(shape: Shape = RoundedCornerShape(28.dp), enabled: Boolean = true, tint: Color? = null): Modifier {
    if (!enabled) return this.clip(shape).background(Color(0xF0181818))
    val style = Settings.glassStyle
    val fillTop = when (style) { 1 -> Color(0x40FFFFFF); 2 -> (tint ?: Color.White).copy(alpha = .30f); else -> Color(0x2EFFFFFF) }
    val fillBottom = when (style) { 1 -> Color(0x26FFFFFF); 2 -> (tint ?: Color.White).copy(alpha = .14f); else -> Color(0x12FFFFFF) }
    val base = if (style == 1) Color(0x99202020) else Color(0x66101010)
    return this
        .clip(shape)
        .background(base)
        .background(Brush.verticalGradient(listOf(fillTop, fillBottom)))
        .drawBehind {
            // specular sheen across the upper third
            drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .16f), Color.Transparent), endY = size.height * .45f))
            drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = .10f), Color.Transparent),
                center = Offset(size.width * .2f, 0f), radius = size.width * .6f))
        }
        .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = .55f), Color.White.copy(alpha = .08f), Color.White.copy(alpha = .20f))), shape)
}

// ---------------- images & colors from images ----------------
@Composable
fun Cover(url: String?, size: Dp, shape: Shape = RoundedCornerShape(4.dp), modifier: Modifier = Modifier, placeholder: ImageVectorIcon? = null) {
    Box(modifier.size(size).clip(shape).background(C.elevated), contentAlignment = Alignment.Center) {
        if (url != null) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(placeholder?.icon ?: Icons.Default.MusicNote, null, tint = C.faint, modifier = Modifier.size(size / 2.4f))
    }
}

class ImageVectorIcon(val icon: androidx.compose.ui.graphics.vector.ImageVector)

/** Dominant, darkened colour of an image URL, like Spotify's header gradients. */
@Composable
fun rememberImageColor(url: String?, fallback: Color = Color(0xFF404040)): Color {
    val ctx = LocalContext.current
    var color by remember(url) { mutableStateOf(fallback) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        val r = ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(url).allowHardware(false).size(48).build())
        val bmp = ((r as? SuccessResult)?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return@LaunchedEffect
        color = Color(dominant(bmp))
    }
    return color
}

fun dominant(b: Bitmap, darken: Double = 0.62): Int {
    val sm = Bitmap.createScaledBitmap(b, 24, 24, true)
    var r = 0L; var g = 0L; var bl = 0L; var n = 0L
    for (x in 0 until 24) for (y in 0 until 24) {
        val p = sm.getPixel(x, y)
        val rr = (p shr 16) and 255; val gg = (p shr 8) and 255; val bb = p and 255
        val sat = maxOf(rr, gg, bb) - minOf(rr, gg, bb)
        val w = 1 + sat * sat / 256
        r += rr * w; g += gg * w; bl += bb * w; n += w
    }
    val f = darken
    return (0xFF shl 24) or ((r / n * f).toInt() shl 16) or ((g / n * f).toInt() shl 8) or (bl / n * f).toInt()
}

// ---------------- building blocks ----------------
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) C.accent else Color.White.copy(alpha = .10f)
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
    ) { Text(text, fontSize = 14.sp, color = if (selected) Color.Black else Color.White, fontWeight = FontWeight.Medium) }
}

@Composable
fun PillRow(options: List<String>, selected: Int?, onSelect: (Int?) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
        if (selected != null) item {
            Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = .10f)).clickable { onSelect(null) }, Alignment.Center) {
                Icon(Icons.Default.Close, "Clear", Modifier.size(18.dp))
            }
        }
        items(options.size) { i -> if (selected == null || selected == i) Pill(options[i], selected == i) { onSelect(if (selected == i) null else i) } }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 26.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) Text(action, fontSize = 13.sp, color = C.sub, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onAction))
    }
}

/** Square card used in home carousels. Round for artists. */
@Composable
fun MediaCard(item: pw.chroma.android.Item, width: Dp = 150.dp, onClick: () -> Unit) {
    val round = item.type == "artist"
    Column(Modifier.width(width).clickable(onClick = onClick)) {
        Cover(item.image, width, if (round) CircleShape else RoundedCornerShape(6.dp))
        Spacer(Modifier.height(8.dp))
        Text(item.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = if (round) 1 else 2, overflow = TextOverflow.Ellipsis,
            modifier = if (round) Modifier.fillMaxWidth() else Modifier)
        if (!round || item.subtitle.isNotEmpty()) Text(item.subtitle, fontSize = 12.sp, color = C.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun TrackRow(
    t: pw.chroma.android.Track,
    playing: Boolean,
    showCover: Boolean = true,
    index: Int? = null,
    dense: Boolean = Settings.denserRows,
    onMore: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val v = if (dense) 5.dp else 8.dp
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = v), verticalAlignment = Alignment.CenterVertically) {
        if (index != null && !showCover) {
            Text("$index", color = if (playing) C.accent else C.sub, fontSize = 15.sp, modifier = Modifier.width(28.dp))
        }
        if (showCover) { Cover(t.image, if (dense) 42.dp else 50.dp); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(t.name, color = if (playing) C.accent else Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (t.explicit) {
                    Box(Modifier.padding(end = 6.dp).clip(RoundedCornerShape(2.dp)).background(C.sub).padding(horizontal = 4.dp)) {
                        Text("E", fontSize = 9.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
                Text(t.artistLine, color = C.sub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onMore != null) IconButton(onClick = onMore) { Icon(Icons.Default.MoreVert, "More", tint = C.sub) }
    }
}

@Composable
fun ItemRow(item: pw.chroma.android.Item, dense: Boolean = Settings.denserRows, pinned: Boolean = false, onClick: () -> Unit) {
    val size = if (dense) 52.dp else 64.dp
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = if (dense) 4.dp else 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (item.type == "liked") LikedTile(size) else Cover(item.image, size, if (item.type == "artist") CircleShape else RoundedCornerShape(4.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pinned) Icon(Icons.Default.PushPin, null, tint = C.accent, modifier = Modifier.size(13.dp).padding(end = 2.dp))
                Text(item.subtitle, fontSize = 13.sp, color = C.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun LikedTile(size: Dp, shape: Shape = RoundedCornerShape(4.dp)) {
    Box(
        Modifier.size(size).clip(shape).background(Brush.linearGradient(listOf(Color(0xFF4A1FD8), Color(0xFF9FB6E8)))),
        Alignment.Center,
    ) { Icon(Icons.Default.Favorite, null, tint = Color.White, modifier = Modifier.size(size / 2.6f)) }
}

@Composable
fun PlayButton(playing: Boolean, size: Dp = 56.dp, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val scale by animateFloatAsState(if (playing) 1f else 1f, label = "s")
    Box(
        Modifier.size(size).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape).background(C.accent)
            .clickable { haptic(ctx, true); onClick() },
        Alignment.Center,
    ) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play", tint = Color.Black, modifier = Modifier.size(size * .55f)) }
}

@Composable
fun Avatar(url: String?, name: String, size: Dp = 34.dp, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).background(C.accent).clickable(onClick = onClick), Alignment.Center) {
        if (url != null) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(name.take(1).uppercase().ifEmpty { "C" }, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = (size.value * .45f).sp)
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(40.dp), Alignment.Center) { CircularProgressIndicator(color = C.accent, strokeWidth = 3.dp, modifier = Modifier.size(28.dp)) }
}

@Composable
fun EmptyNote(text: String) {
    Text(text, color = C.sub, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().padding(32.dp))
}

fun fmt(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

fun fmtLong(ms: Long): String {
    val m = ms / 60000
    return if (m >= 60) "${m / 60} hr ${m % 60} min" else "$m min"
}
