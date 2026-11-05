package pw.chroma.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.Manifest
import androidx.compose.ui.text.input.PasswordVisualTransformation
import android.media.projection.MediaProjectionManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

private fun haptic(ctx: Context) {
    if (!Settings.haptics) return
    val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    v.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
}

@Composable
fun ChromaApp() {
    val accent = Color(Settings.accent)
    val bg = if (Settings.amoled) Color.Black else Color(0xFF121212)
    val scheme = darkColorScheme(
        primary = accent, onPrimary = Color.Black, background = bg, surface = bg,
        surfaceVariant = Color(0xFF242424), onSurface = Color.White, onBackground = Color.White,
    )
    MaterialTheme(colorScheme = scheme) {
        val ctx = LocalContext.current
        var access by remember { mutableStateOf(Remote.hasAccess(ctx)) }
        // re-check when the user returns from system settings
        LaunchedEffect(Unit) { while (true) { access = Remote.hasAccess(ctx); if (access) Remote.start(ctx); delay(1500) } }
        Surface(Modifier.fillMaxSize(), color = bg) {
            if (!access) SetupScreen() else Main()
        }
    }
}

@Composable
fun SetupScreen() {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(32.dp).systemBarsPadding(),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.GraphicEq, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Chroma", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            "Chroma is a client for the Spotify app on this phone. To see and control what Spotify is playing, " +
                "allow Chroma notification access. It only reads Spotify's media session.",
            textAlign = TextAlign.Center, color = Color.White.copy(alpha = .7f),
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = { ctx.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
            Text("Grant notification access")
        }
        if (!Remote.spotifyInstalled(ctx)) {
            Spacer(Modifier.height(16.dp))
            Text("Spotify doesn't appear to be installed.", color = Color(0xFFFF8A80))
        }
    }
}

private fun fmt(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

@Composable
fun PlayerScreen(np: NowPlaying) {
    val ctx = LocalContext.current
    if (!np.connected || np.title.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("No current track", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Start playing something in Spotify and it will show up here.", color = Color.White.copy(alpha = .65f), textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            Button(onClick = { Remote.openSpotify(ctx) }) { Text("Open Spotify") }
        }
        return
    }
    var pos by remember { mutableLongStateOf(np.livePosition()) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(np) { while (true) { if (!dragging) pos = np.livePosition(); delay(250) } }
    val am = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var volume by remember { mutableFloatStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }
    val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()
    var shuffleOn by remember { mutableStateOf(false) }
    var repeatOn by remember { mutableStateOf(false) }
    var liked by remember { mutableStateOf(false) }
    LaunchedEffect(np.title, np.artist) { liked = false }
    val accent = MaterialTheme.colorScheme.primary

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).statusBarsPadding().padding(bottom = 110.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        Text("PLAYING FROM SPOTIFY", fontSize = 11.sp, letterSpacing = 2.sp, color = Color.White.copy(alpha = .6f))
        Spacer(Modifier.height(16.dp))
        val shape = RoundedCornerShape(if (Settings.roundArtwork) 22.dp else 4.dp)
        val artMod = Modifier.fillMaxWidth().aspectRatio(1f).clip(shape).background(Color(0xFF282828))
            .pointerInput(Settings.doubleTap) {
                detectTapGestures(onDoubleTap = { o ->
                    val right = o.x > size.width / 2
                    haptic(ctx)
                    when (Settings.doubleTap) {
                        1 -> Remote.seekBy(if (right) 10_000 else -10_000)
                        2 -> if (right) Remote.next() else Remote.previous()
                    }
                })
            }
        if (np.art != null) Image(BitmapPainter(np.art.asImageBitmap()), null, artMod, contentScale = ContentScale.Crop)
        else Box(artMod)
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(np.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(np.artist, fontSize = 16.sp, color = Color.White.copy(alpha = .7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = {
                haptic(ctx)
                if (Remote.custom("like", "heart", "save", "add_to", "favorite")) liked = !liked
                else Remote.openSpotify(ctx)
            }) { Icon(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Like", tint = if (liked) accent else Color.White) }
        }
        Spacer(Modifier.height(8.dp))
        val dur = np.durationMs.coerceAtLeast(1)
        Slider(
            value = if (dragging) dragValue else (pos.toFloat() / dur).coerceIn(0f, 1f),
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = { Remote.seekTo((dragValue * dur).toLong()); pos = (dragValue * dur).toLong(); dragging = false },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = .25f)),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(fmt(if (dragging) (dragValue * dur).toLong() else pos), fontSize = 12.sp, color = Color.White.copy(alpha = .6f))
            Spacer(Modifier.weight(1f))
            Text(fmt(np.durationMs), fontSize = 12.sp, color = Color.White.copy(alpha = .6f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { haptic(ctx); if (Remote.custom("shuffle")) shuffleOn = !shuffleOn }) {
                Icon(Icons.Default.Shuffle, "Shuffle", tint = if (shuffleOn) accent else Color.White)
            }
            IconButton(onClick = { haptic(ctx); Remote.previous() }, Modifier.size(56.dp)) {
                Icon(Icons.Default.SkipPrevious, "Previous", Modifier.size(40.dp))
            }
            FilledIconButton(onClick = { haptic(ctx); Remote.playPause() }, Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black)) {
                Icon(if (np.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause", Modifier.size(40.dp))
            }
            IconButton(onClick = { haptic(ctx); Remote.next() }, Modifier.size(56.dp)) {
                Icon(Icons.Default.SkipNext, "Next", Modifier.size(40.dp))
            }
            IconButton(onClick = { haptic(ctx); if (Remote.custom("repeat")) repeatOn = !repeatOn }) {
                Icon(Icons.Default.Repeat, "Repeat", tint = if (repeatOn) accent else Color.White)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.VolumeDown, null, tint = Color.White.copy(alpha = .7f))
            Slider(
                value = volume, valueRange = 0f..maxVol,
                onValueChange = { volume = it; am.setStreamVolume(AudioManager.STREAM_MUSIC, it.toInt(), 0) },
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = .25f)),
            )
            Icon(Icons.Default.VolumeUp, null, tint = Color.White.copy(alpha = .7f))
        }
        TextButton(onClick = { Remote.openSpotify(ctx) }) { Text("Open Spotify") }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun LyricsScreen(np: NowPlaying) {
    var lyrics by remember { mutableStateOf<Lyrics?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(np.title, np.artist) {
        lyrics = null; failed = false
        if (np.title.isEmpty()) return@LaunchedEffect
        loading = true
        val r = LyricsRepo.load(np.title, np.artist, np.album, np.durationMs)
        lyrics = r; failed = r == null || r.isEmpty; loading = false
    }
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(np) { while (true) { pos = np.livePosition(); delay(150) } }
    val align = when (Settings.lyricsAlign) { 1 -> TextAlign.Center; 2 -> TextAlign.End; else -> TextAlign.Start }
    val accent = MaterialTheme.colorScheme.primary

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(16.dp))
        Text(np.title.ifEmpty { "Lyrics" }, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(np.artist, color = Color.White.copy(alpha = .65f), maxLines = 1)
        Spacer(Modifier.height(12.dp))
        val l = lyrics
        when {
            loading -> Text("Loading lyrics…", color = Color.White.copy(alpha = .6f))
            l != null && l.instrumental -> Text("This song is instrumental.", color = Color.White.copy(alpha = .6f))
            l != null && l.synced.isNotEmpty() -> {
                val idx = l.synced.indexOfLast { it.timeMs <= pos + 250 }.coerceAtLeast(0)
                val state = rememberLazyListState()
                LaunchedEffect(idx) { state.animateScrollToItem((idx - 2).coerceAtLeast(0)) }
                LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                    items(l.synced.size) { i ->
                        val line = l.synced[i]
                        val cur = i == idx
                        Text(
                            line.text.ifBlank { "♪" }, fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = align,
                            color = if (cur) Color.White else Color.White.copy(alpha = if (i < idx) .35f else .5f),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                                .clickable { Remote.seekTo(line.timeMs) },
                        )
                    }
                    item { Spacer(Modifier.height(200.dp)) }
                }
            }
            l?.plain != null -> Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(l.plain, fontSize = 20.sp, textAlign = align, modifier = Modifier.fillMaxWidth())
            }
            failed -> if (!Settings.hideLyricsOnError) Text("Couldn't load the lyrics for this song.", color = Color.White.copy(alpha = .6f))
        }
        if (l != null && !l.isEmpty) {
            Spacer(Modifier.height(6.dp))
            Text("Lyrics provided by LRCLIB", fontSize = 11.sp, color = accent.copy(alpha = .8f))
        }
    }
}

@Composable
fun TimerScreen() {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { now = SystemClock.elapsedRealtime(); delay(500) } }
    val end = SleepTimer.endAt.longValue
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)) {
        Text("Sleep Timer", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Pauses Spotify after the chosen time.", color = Color.White.copy(alpha = .65f))
        Spacer(Modifier.height(24.dp))
        if (end > now) {
            Text(fmt(end - now), fontSize = 56.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { SleepTimer.cancel() }) { Text("Cancel timer") }
        } else {
            listOf(1, 5, 15, 30, 45, 60).chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { m ->
                        FilledTonalButton(onClick = { haptic(ctx); SleepTimer.start(ctx, m) }, Modifier.weight(1f)) {
                            Text(if (m == 60) "1 hour" else "$m min")
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        SwitchRow("Fade out", "Lower the volume over the last 15 seconds", Settings.sleepFade) { Settings.sleepFade = it }
    }
}

@Composable
fun SwitchRow(title: String, sub: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            if (sub != null) Text(sub, fontSize = 12.sp, color = Color.White.copy(alpha = .55f))
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(20.dp))
    Text(title.uppercase(), fontSize = 12.sp, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Choice(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, o ->
            SegmentedButton(selected = selected == i, onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size)) { Text(o, maxLines = 1) }
        }
    }
}

private val accents = listOf(0xFF1ED760, 0xFF2979FF, 0xFFAB47BC, 0xFFFF4081, 0xFFFF6E40, 0xFFFFC400, 0xFF00E5FF, 0xFFFFFFFF)

private val iconChoices = listOf(
    "icon_chroma" to "Chroma", "icon_chromacobalt" to "Cobalt", "icon_chromadark" to "Dark",
    "icon_chromaflat" to "Flat", "icon_chromamono" to "Mono", "icon_chromarings" to "Rings",
)

private fun applyIcon(ctx: Context, chosen: String) {
    val pm = ctx.packageManager
    iconChoices.forEach { (slug, _) ->
        pm.setComponentEnabledSetting(
            ComponentName(ctx.packageName, "pw.chroma.android.alias.$slug"),
            if (slug == chosen) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }
    Settings.icon = chosen
}

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 110.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("Settings", fontSize = 30.sp, fontWeight = FontWeight.Bold)

        Section("Spotify account")
        Text(if (Api.loggedIn.value) "Connected as ${Api.userName.value}" else "Not connected", fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (Api.loggedIn.value) GlassPill("Disconnect", { Api.logout() })
            GlassPill("Sleep timer", { Nav.showTimer.value = true })
        }

        Section("Appearance")
        Text("Accent color", fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(accents) { c ->
                val sel = Settings.accent == c.toInt()
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color(c))
                    .border(if (sel) 3.dp else 0.dp, Color.White.copy(alpha = .9f), CircleShape)
                    .clickable { Settings.accent = c.toInt() })
            }
        }
        SwitchRow("AMOLED black", "Pure black background", Settings.amoled) { Settings.amoled = it }
        SwitchRow("Tint with album color", null, Settings.tintAlbum) { Settings.tintAlbum = it }
        SwitchRow("Round artwork", null, Settings.roundArtwork) { Settings.roundArtwork = it }

        Section("Player")
        Text("Double tap artwork", fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        Choice(listOf("Off", "Seek 10s", "Skip track"), Settings.doubleTap) { Settings.doubleTap = it }
        SwitchRow("Control haptics", "Short vibration on control taps", Settings.haptics) { Settings.haptics = it }

        Section("Lyrics")
        Text("Lyrics alignment", fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        Choice(listOf("Left", "Center", "Right"), Settings.lyricsAlign) { Settings.lyricsAlign = it }
        SwitchRow("Hide on error", "Don't show a message when lyrics can't be found", Settings.hideLyricsOnError) { Settings.hideLyricsOnError = it }

        Section("Music haptics")
        Text("Vibrates to the beat of whatever is playing (Spotify or any other app), even with the screen off.", fontSize = 12.sp, color = Color.White.copy(alpha = .55f))
        Spacer(Modifier.height(8.dp))
        val projLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            if (r.resultCode == android.app.Activity.RESULT_OK && data != null) {
                ctx.startForegroundService(Intent(ctx, HapticService::class.java).putExtra("code", r.resultCode).putExtra("data", data))
            }
        }
        val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            if (res[Manifest.permission.RECORD_AUDIO] == true) {
                val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projLauncher.launch(mpm.createScreenCaptureIntent())
            }
        }
        val on = HapticService.running.value
        Button(onClick = {
            if (on) ctx.startService(Intent(ctx, HapticService::class.java).setAction(HapticService.ACTION_STOP))
            else permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
        }) { Text(if (on) "Stop haptics" else "Start haptics") }
        Spacer(Modifier.height(8.dp))
        Choice(listOf("Bass pulse", "Beat", "Beat + Bass"), Settings.hapticMode) { Settings.hapticMode = it }
        Spacer(Modifier.height(6.dp))
        Text("Intensity", fontSize = 14.sp)
        Slider(value = Settings.hapticIntensity, onValueChange = { Settings.hapticIntensity = it }, valueRange = 0.2f..1f)
        Text("Android asks for screen-capture permission; only audio is captured, never the screen. Some apps block audio capture.",
            fontSize = 11.sp, color = Color.White.copy(alpha = .5f))

        Section("Live wallpaper")
        Text("Blurred background, big cover, title and Spicy-style lyrics on your home/lock screen.", fontSize = 12.sp, color = Color.White.copy(alpha = .55f))
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            val i = Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(ctx, ChromaWallpaperService::class.java))
            try { ctx.startActivity(i) } catch (_: Exception) { ctx.startActivity(Intent(android.app.WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) }
        }) { Text("Set live wallpaper") }
        SwitchRow("Canvas video background", "Use a looping canvas video when one is available (falls back to blurred cover)", Settings.wallpaperVideo) { Settings.wallpaperVideo = it }
        OutlinedTextField(
            value = Settings.spDc, onValueChange = { Settings.spDc = it.trim() }, singleLine = true,
            label = { Text("Spotify sp_dc cookie (stored only on this phone)") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Used to fetch Canvas videos from Spotify's unofficial web endpoints. Treat it like a password. Log out of Spotify web to revoke it.",
            fontSize = 11.sp, color = Color.White.copy(alpha = .5f))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = Settings.canvasUrl, onValueChange = { Settings.canvasUrl = it }, singleLine = true,
            label = { Text("Canvas endpoint (optional)") }, placeholder = { Text("https://example.com/canvas/{id}") },
            modifier = Modifier.fillMaxWidth(),
        )

        Section("App icon")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(iconChoices) { (slug, name) ->
                val res = ctx.resources.getIdentifier(slug, "mipmap", ctx.packageName)
                val bmp = remember(slug) { androidx.core.content.res.ResourcesCompat.getDrawable(ctx.resources, res, null)?.let { d ->
                    val b = android.graphics.Bitmap.createBitmap(192, 192, android.graphics.Bitmap.Config.ARGB_8888)
                    d.setBounds(0, 0, 192, 192); d.draw(android.graphics.Canvas(b)); b.asImageBitmap() } }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { applyIcon(ctx, slug) }) {
                    if (bmp != null) Image(BitmapPainter(bmp), name, Modifier.size(64.dp).clip(RoundedCornerShape(14.dp))
                        .border(if (Settings.icon == slug) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp)))
                    Text(name, fontSize = 12.sp)
                }
            }
        }
        Text("Launcher may take a moment to refresh.", fontSize = 11.sp, color = Color.White.copy(alpha = .5f))

        Section("About")
        Text("Chroma for Android 1.1.0 beta 1", fontSize = 16.sp)
        Text("A companion client that controls the official Spotify app on this phone. Requires Spotify to be installed; it does not modify Spotify or change your plan.",
            fontSize = 12.sp, color = Color.White.copy(alpha = .55f))
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { ctx.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Notification access settings") }
        Spacer(Modifier.height(24.dp))
    }
}
