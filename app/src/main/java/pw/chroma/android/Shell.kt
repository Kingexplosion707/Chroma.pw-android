package pw.chroma.android

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

object Nav { val showTimer = mutableStateOf(false) }

private data class Tab(val name: String, val icon: ImageVector)
private val tabs = listOf(
    Tab("Library", Icons.Default.LibraryMusic), Tab("Search", Icons.Default.Search),
    Tab("Player", Icons.Default.PlayCircle), Tab("Lyrics", Icons.Default.Lyrics), Tab("Settings", Icons.Default.Settings),
)

@Composable
fun Main() {
    var tab by remember { mutableIntStateOf(0) }
    val np by Remote.state.collectAsStateWithLifecycle()
    val bg = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize().background(bg)) {
        // blurred artwork backdrop that the glass surfaces sit on
        if (np.art != null) Image(
            BitmapPainter(np.art!!.asImageBitmap()), null,
            Modifier.fillMaxSize().blur(70.dp), contentScale = ContentScale.Crop, alpha = .75f,
        ) else Box(Modifier.fillMaxSize().background(Color(Settings.accent).copy(alpha = .18f)))
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .38f)))

        Crossfade(tab, label = "tab") { t ->
            when (t) {
                0 -> LibraryScreen()
                1 -> SearchScreen()
                2 -> PlayerScreen(np)
                3 -> LyricsScreen(np)
                else -> SettingsScreen()
            }
        }
        if (Nav.showTimer.value) Box(Modifier.fillMaxSize().background(bg.copy(alpha = .94f))) {
            TimerScreen()
            TextButton(onClick = { Nav.showTimer.value = false }, Modifier.align(Alignment.TopEnd).statusBarsPadding()) { Text("Done") }
        }

        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (tab != 2 && np.connected && np.title.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().glass(RoundedCornerShape(30.dp)).clickable { tab = 2 }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    np.art?.let { Image(BitmapPainter(it.asImageBitmap()), null, Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(np.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(np.artist, fontSize = 12.sp, color = Color.White.copy(alpha = .65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { Remote.playPause() }) { Icon(if (np.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause") }
                    IconButton(onClick = { Remote.next() }) { Icon(Icons.Default.SkipNext, "Next") }
                }
                Spacer(Modifier.height(10.dp))
            }
            Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(36.dp)).padding(6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                tabs.forEachIndexed { i, t ->
                    val sel = tab == i
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(30.dp))
                            .background(if (sel) Color.White.copy(alpha = .18f) else Color.Transparent)
                            .clickable { tab = i }.padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(t.icon, t.name, tint = if (sel) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .8f))
                        Text(t.name, fontSize = 10.sp, color = if (sel) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .8f))
                    }
                }
            }
        }
    }
}
