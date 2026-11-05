package pw.chroma.android.ui

import android.Manifest
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pw.chroma.android.*

// ---------------- building blocks (iOS-style grouped list) ----------------
@Composable
private fun Group(title: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (title != null) Text(title.uppercase(), fontSize = 12.sp, color = C.sub, letterSpacing = 1.sp, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.card), content = content)
        if (footer != null) Text(footer, fontSize = 12.sp, color = C.faint, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp))
    }
}

@Composable
private fun NavRow(icon: ImageVector?, title: String, sub: String? = null, iconTint: Color = C.accent, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(iconTint), Alignment.Center) { Icon(icon, null, tint = Color.Black, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            if (sub != null) Text(sub, fontSize = 12.sp, color = C.sub)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = C.faint)
    }
}

@Composable
private fun Toggle(title: String, value: Boolean, sub: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            if (sub != null) Text(sub, fontSize = 12.sp, color = C.sub)
        }
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = C.accent, checkedThumbColor = Color.White))
    }
}

@Composable
private fun Choice(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(title, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, o ->
                SegmentedButton(selected == i, { onSelect(i) }, SegmentedButtonDefaults.itemShape(i, options.size),
                    colors = SegmentedButtonDefaults.colors(activeContainerColor = C.accent, activeContentColor = Color.Black)) { Text(o, maxLines = 1, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun Slide(title: String, value: Float, range: ClosedFloatingPointRange<Float>, label: String, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row { Text(title, fontSize = 16.sp, modifier = Modifier.weight(1f)); Text(label, color = C.sub) }
        Slider(value, onChange, valueRange = range, colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = C.accent))
    }
}

@Composable
private fun Div() = HorizontalDivider(Modifier.padding(start = 14.dp), color = Color.White.copy(alpha = .06f))

@Composable
private fun Page(title: String, content: @Composable ColumnScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxSize().background(C.bg), contentPadding = PaddingValues(bottom = 180.dp)) {
        item {
            BackBar()
            Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
        item { Column(content = content) }
    }
}

private fun go(page: String) = Nav.push(Route.SettingsPage(page))

// ---------------- home ----------------
@Composable
fun SettingsHome() {
    val ctx = LocalContext.current
    Page("Settings") {
        Group {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(Api.userImage.value, Api.userName.value, 52.dp) {}
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(Api.userName.value.ifBlank { "Not connected" }, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(if (Api.loggedIn.value) "Spotify account" else "Log in on the Home tab", fontSize = 13.sp, color = C.sub)
                }
                if (Api.loggedIn.value) TextButton(onClick = { Api.logout() }) { Text("Log out", color = C.sub) }
            }
        }
        Group("Looks") {
            NavRow(Icons.Default.Palette, "Appearance", "Theme, glass and layout") { go("appearance") }; Div()
            NavRow(Icons.Default.Home, "Home", "Gradient, hide sections") { go("home") }; Div()
            NavRow(Icons.Default.Tab, "Tab bar", "Order, launch tab, custom tabs") { go("tabs") }; Div()
            NavRow(Icons.Default.SmartDisplay, "Now playing bar", "Tint, artwork, device button, flags") { go("npb") }; Div()
            NavRow(Icons.Default.PlayCircle, "Player", "Backdrop, glass lyrics, hide parts, gestures") { go("player") }; Div()
            NavRow(Icons.Default.QueueMusic, "Playlists & albums", "Cover, dividers, header parts") { go("playlist") }; Div()
            NavRow(Icons.Default.Apps, "App Icon", "Choose icon") { go("icon") }
        }
        Group("Listening") {
            NavRow(Icons.Default.Lyrics, "Lyrics", "Sources and display") { go("lyrics") }; Div()
            NavRow(Icons.Default.Bedtime, "Sleep Timer", null) { Nav.sheet.value = "timer" }; Div()
            NavRow(Icons.Default.Vibration, "Music haptics", "Vibrate to the beat") { go("haptics") }; Div()
            NavRow(Icons.Default.Wallpaper, "Live wallpaper & Canvas", "Lyrics wallpaper, Canvas videos") { go("canvas") }; Div()
            NavRow(Icons.Default.Tune, "Playback", "Shake for random, share links") { go("playback") }
        }
        Group("Advanced") {
            NavRow(Icons.Default.NotificationsActive, "Notification access", "Lets Chroma read what Spotify is playing", Color(0xFF8E8E93)) {
                ctx.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }; Div()
            NavRow(Icons.Default.Restore, "Reset", "Reset all changes", Color(0xFFFF453A)) { go("reset") }
        }
        Group(footer = "Chroma for Android 2.0 — a client for the official Spotify app. It doesn't modify Spotify or change your plan.") {
            NavRow(Icons.Default.Info, "About", null, Color(0xFF8E8E93)) { go("about") }
        }
    }
}

private val accents = listOf(0xFF1ED760, 0xFF2979FF, 0xFFAB47BC, 0xFFFF4081, 0xFFFF6E40, 0xFFFFC400, 0xFF00E5FF, 0xFFFFFFFF, 0xFFE91429, 0xFF8D67AB)

@Composable
fun SettingsPage(page: String) {
    val ctx = LocalContext.current
    when (page) {
        "appearance" -> Page("Appearance") {
            Group("Theme", "Accent colour is used for buttons, the progress bar and highlights.") {
                Column(Modifier.padding(14.dp)) {
                    Text("Accent color", fontSize = 16.sp)
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(accents) { c ->
                            Box(Modifier.size(36.dp).clip(CircleShape).background(Color(c))
                                .border(if (Settings.accent == c.toInt()) 3.dp else 0.dp, Color.White, CircleShape).clickable { Settings.accent = c.toInt() })
                        }
                    }
                }
                Div(); Toggle("AMOLED black", Settings.amoled) { Settings.amoled = it }
            }
            Group("Liquid Glass") {
                Choice("Style", listOf("Clear", "Frosted", "Tinted"), Settings.glassStyle) { Settings.glassStyle = it }
            }
            Group("Surfaces", "Turn glass on or off for each part of the app.") {
                Toggle("Tab bar", Settings.glassTabBar) { Settings.glassTabBar = it }; Div()
                Toggle("Now playing bar", Settings.glassNowPlayingBar) { Settings.glassNowPlayingBar = it }; Div()
                Toggle("Search", Settings.glassSearch) { Settings.glassSearch = it }; Div()
                Toggle("Home", Settings.glassHome) { Settings.glassHome = it }; Div()
                Toggle("Playlists & albums", Settings.glassPlaylist) { Settings.glassPlaylist = it }; Div()
                Toggle("Custom lyrics view", Settings.glassLyrics) { Settings.glassLyrics = it }
            }
            Group("Library") {
                Toggle("Grid layout", Settings.libraryGrid) { Settings.libraryGrid = it }; Div()
                Toggle("Denser rows", Settings.denserRows) { Settings.denserRows = it }
            }
        }
        "home" -> Page("Home") {
            Group("Gradient", "Changes apply live.") {
                Choice("Style", listOf("Gradient", "Solid", "Off"), Settings.homeGradientStyle) { Settings.homeGradientStyle = it }; Div()
                Choice("Strength", listOf("Subtle", "Medium", "Bold"), Settings.homeGradientStrength) { Settings.homeGradientStrength = it }; Div()
                Choice("Height", listOf("Short", "Medium", "Tall", "Full"), Settings.homeGradientHeight) { Settings.homeGradientHeight = it }
            }
            Group("Pages") {
                Toggle("Home", Settings.gradientHome) { Settings.gradientHome = it }; Div()
                Toggle("Search", Settings.gradientSearch) { Settings.gradientSearch = it }; Div()
                Toggle("Library", Settings.gradientLibrary) { Settings.gradientLibrary = it }
            }
            Group("Hide sections") {
                Toggle("Filter pills", Settings.homePills) { Settings.homePills = it }; Div()
                Toggle("Shortcuts grid", Settings.homeShortcuts) { Settings.homeShortcuts = it }
            }
        }
        "tabs" -> TabsPage()
        "npb" -> Page("Now playing bar") {
            Group {
                Toggle("Tint with album color", Settings.npbAlbumTint) { Settings.npbAlbumTint = it }; Div()
                Toggle("Round artwork", Settings.npbRoundArtwork) { Settings.npbRoundArtwork = it }; Div()
                Toggle("Two lines of track info", Settings.npbTwoLines) { Settings.npbTwoLines = it }; Div()
                Toggle("Hide device button", Settings.npbHideConnect) { Settings.npbHideConnect = it }; Div()
                Toggle("Save button", Settings.npbSaveButton) { Settings.npbSaveButton = it }
            }
        }
        "player" -> Page("Player") {
            Group("Backdrop") {
                Toggle("Artwork background", Settings.playerBackdrop, "Blurred cover instead of flat color") { Settings.playerBackdrop = it }; Div()
                Toggle("Canvas", Settings.canvas, "Looping video behind the player when available") { Settings.canvas = it }; Div()
                Toggle("Round artwork", Settings.roundArtwork) { Settings.roundArtwork = it }; Div()
                Toggle("Glass lyrics", Settings.glassLyrics) { Settings.glassLyrics = it }
            }
            Group("Gestures") {
                Choice("Double tap artwork", listOf("Off", "Seek 10s", "Skip track"), Settings.doubleTap) { Settings.doubleTap = it }; Div()
                Choice("Zones", listOf("Left · Right", "Left · Center · Right"), Settings.playerZones) { Settings.playerZones = it }; Div()
                Toggle("Control haptics", Settings.haptics, "Buttons and scrubbing") { Settings.haptics = it }
            }
            Group("Hide on player") {
                listOf("shuffle" to "Shuffle", "repeat" to "Repeat", "share" to "Share", "connect" to "Connect to a device", "queue" to "Queue", "addTo" to "Add to playlist").forEachIndexed { i, (k, n) ->
                    if (i > 0) Div(); Toggle(n, !Settings.isHidden(k)) { Settings.setHidden(k, !it) }
                }
            }
            Group("Hide cards below player") {
                listOf("allCards" to "All cards", "lyricsCard" to "Lyrics", "aboutArtist" to "About the artist", "credits" to "Credits", "queuePreview" to "Queue").forEachIndexed { i, (k, n) ->
                    if (i > 0) Div(); Toggle(n, !Settings.isHidden(k)) { Settings.setHidden(k, !it) }
                }
            }
        }
        "playlist" -> Page("Playlists & albums") {
            Group(footer = "Applies as you open or scroll a playlist.") {
                Toggle("Full-width cover", Settings.playlistFullCover) { Settings.playlistFullCover = it }; Div()
                Toggle("Track dividers", Settings.playlistDividers) { Settings.playlistDividers = it }; Div()
                Toggle("Hide find bar", Settings.playlistHideFind) { Settings.playlistHideFind = it }
            }
            Group("Header parts") {
                Toggle("Creator row", Settings.playlistCreator) { Settings.playlistCreator = it }; Div()
                Toggle("Description", Settings.playlistDescription) { Settings.playlistDescription = it }; Div()
                Toggle("Duration line", Settings.playlistMetadata) { Settings.playlistMetadata = it }
            }
        }
        "icon" -> IconPage()
        "lyrics" -> Page("Lyrics") {
            Group("Lyrics Source", "LRCLIB provides time-synced lyrics. When a track has no word timing, Chroma estimates it from each line so words still fill as they're sung.") {
                Toggle("Word-by-word", Settings.wordSync, "Fill each word as it's sung") { Settings.wordSync = it }; Div()
                Toggle("Hide on error", Settings.hideLyricsOnError, "Remove the lyrics card if lyrics fail to load") { Settings.hideLyricsOnError = it }
            }
            Group("Custom Lyrics View") {
                Choice("Lyrics alignment", listOf("Left", "Center", "Right"), Settings.lyricsAlign) { Settings.lyricsAlign = it }; Div()
                Slide("Blur intensity", Settings.lyricsBlur, 0f..1f, "${(Settings.lyricsBlur * 100).toInt()}%") { Settings.lyricsBlur = it }
            }
            Group("Lyrics Background Color") {
                Toggle("Display original colors", Settings.lyricsOriginalColors, "Use the track cover's colour") { Settings.lyricsOriginalColors = it }; Div()
                Toggle("Use static color", Settings.lyricsStaticColor) { Settings.lyricsStaticColor = it }
                if (Settings.lyricsStaticColor) Column(Modifier.padding(14.dp)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(listOf(0xFF3A2A5A, 0xFF1E3264, 0xFF5A1E2A, 0xFF1E5A3A, 0xFF4A3A1E, 0xFF2B2B2B)) { c ->
                            Box(Modifier.size(34.dp).clip(CircleShape).background(Color(c)).border(if (Settings.lyricsColor == c.toInt()) 3.dp else 0.dp, Color.White, CircleShape)
                                .clickable { Settings.lyricsColor = c.toInt() })
                        }
                    }
                }
            }
        }
        "playback" -> Page("Playback") {
            Group {
                Toggle("Shake for random", Settings.shakeRandom, "Shake your phone to play a random liked song") { Settings.shakeRandom = it }; Div()
                Toggle("Clean Share Links", Settings.cleanShareLinks, "Removes tracking parameters from shared links") { Settings.cleanShareLinks = it }
            }
            Group("Sleep Timer") {
                Toggle("1 minute option", Settings.sleepOneMinute) { Settings.sleepOneMinute = it }; Div()
                Toggle("Fade out", Settings.sleepFade) { Settings.sleepFade = it }
            }
        }
        "haptics" -> HapticsPage()
        "canvas" -> CanvasPage()
        "reset" -> Page("Reset") {
            Group(footer = "Resets every Chroma setting to its default. Your Spotify login is kept.") {
                Row(Modifier.fillMaxWidth().clickable { Settings.reset(); Playback.toast("Settings reset") }.padding(14.dp)) {
                    Text("Reset all changes", color = Color(0xFFFF453A), fontSize = 16.sp)
                }
            }
        }
        "about" -> Page("About") {
            Group(footer = "Lyrics from LRCLIB (lrclib.net). Chroma controls Spotify through Spotify's media session and Web API. Spotify is a trademark of Spotify AB; this app isn't affiliated with Spotify.") {
                Text("Chroma for Android 2.0", modifier = Modifier.padding(14.dp), fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun TabsPage() {
    val all = allTabs()
    var order by remember { mutableStateOf(Settings.tabOrder.split(',').map { it.trim() }.filter { it.isNotEmpty() }) }
    var title by remember { mutableStateOf("") }
    var uri by remember { mutableStateOf("") }
    fun save(o: List<String>) { order = o; Settings.tabOrder = o.joinToString(",") }
    Page("Tab bar") {
        Group("Tabs", "Tap the arrows to reorder. Turn a tab off to hide it. Tap the star to open it on launch.") {
            all.forEachIndexed { i, t ->
                if (i > 0) Div()
                val on = t.id in order
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { Settings.launchTab = t.id }) {
                        Icon(if (Settings.launchTab == t.id) Icons.Default.Star else Icons.Default.StarBorder, "Launch tab", tint = if (Settings.launchTab == t.id) C.accent else C.faint)
                    }
                    Text(t.title, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    if (on) {
                        IconButton(onClick = { val k = order.indexOf(t.id); if (k > 0) save(order.toMutableList().also { l -> l.add(k - 1, l.removeAt(k)) }) }) { Icon(Icons.Default.KeyboardArrowUp, "Up") }
                        IconButton(onClick = { val k = order.indexOf(t.id); if (k < order.lastIndex) save(order.toMutableList().also { l -> l.add(k + 1, l.removeAt(k)) }) }) { Icon(Icons.Default.KeyboardArrowDown, "Down") }
                    }
                    Switch(on, { v -> if (v) save(order + t.id) else if (order.size > 1) save(order - t.id) },
                        colors = SwitchDefaults.colors(checkedTrackColor = C.accent))
                }
            }
            Div(); Toggle("Hide labels", Settings.tabHideLabels) { Settings.tabHideLabels = it }
        }
        Group("Custom tabs", "Open any Spotify page from the tab bar. Paste a spotify: URI or an open.spotify.com link.") {
            Column(Modifier.padding(14.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(uri, { uri = it }, label = { Text("Custom URI") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    val u = normalizeUri(uri)
                    if (u == null || routeFor(u) == null || title.isBlank()) Playback.toast("Not a Spotify link or URI")
                    else {
                        val lines = Settings.customTabs.lines().filter { it.isNotBlank() } + "$title|$u"
                        Settings.customTabs = lines.joinToString("\n")
                        val id = "custom${lines.size - 1}"
                        save(order + id); title = ""; uri = ""
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = C.accent, contentColor = Color.Black)) { Text("Add tab") }
            }
            if (Settings.customTabs.isNotBlank()) {
                Div()
                Row(Modifier.fillMaxWidth().clickable {
                    Settings.customTabs = ""; save(order.filterNot { it.startsWith("custom") })
                }.padding(14.dp)) { Text("Remove all custom tabs", color = Color(0xFFFF453A)) }
            }
        }
    }
}

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
private fun IconPage() {
    val ctx = LocalContext.current
    Page("App Icon") {
        Group(footer = "Your launcher may take a few seconds to refresh.") {
            iconChoices.forEachIndexed { i, (slug, name) ->
                if (i > 0) Div()
                val res = ctx.resources.getIdentifier(slug, "mipmap", ctx.packageName)
                val bmp = remember(slug) {
                    androidx.core.content.res.ResourcesCompat.getDrawable(ctx.resources, res, null)?.let { d ->
                        val b = android.graphics.Bitmap.createBitmap(160, 160, android.graphics.Bitmap.Config.ARGB_8888)
                        d.setBounds(0, 0, 160, 160); d.draw(android.graphics.Canvas(b)); b.asImageBitmap()
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { applyIcon(ctx, slug) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (bmp != null) Image(BitmapPainter(bmp), name, Modifier.size(56.dp).clip(RoundedCornerShape(13.dp)))
                    Spacer(Modifier.width(14.dp))
                    Text(name, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    if (Settings.icon == slug) Icon(Icons.Default.Check, "Selected", tint = C.accent)
                }
            }
        }
    }
}

@Composable
private fun HapticsPage() {
    val ctx = LocalContext.current
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
    Page("Music haptics") {
        Group(footer = "Vibrates to the beat of whatever is playing, even with the screen off. Android asks for screen-capture permission; only audio is captured, never the screen. Some apps block audio capture.") {
            Toggle("Music haptics", on) {
                if (on) ctx.startService(Intent(ctx, HapticService::class.java).setAction(HapticService.ACTION_STOP))
                else permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
            }
        }
        Group("Style") {
            Choice("Mode", listOf("Bass pulse", "Beat", "Beat + Bass"), Settings.hapticMode) { Settings.hapticMode = it }; Div()
            Slide("Intensity", Settings.hapticIntensity, .2f..1f, "${(Settings.hapticIntensity * 100).toInt()}%") { Settings.hapticIntensity = it }
        }
    }
}

@Composable
private fun CanvasPage() {
    val ctx = LocalContext.current
    Page("Live wallpaper & Canvas") {
        Group("Live wallpaper", "Blurred Canvas or cover, big centred cover, title and word-by-word lyrics on your home and lock screen.") {
            Row(Modifier.fillMaxWidth().clickable {
                val i = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                    .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(ctx, ChromaWallpaperService::class.java))
                try { ctx.startActivity(i) } catch (_: Exception) { ctx.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) }
            }.padding(14.dp)) { Text("Set live wallpaper", color = C.accent, fontSize = 16.sp) }
            Div(); Toggle("Canvas video background", Settings.wallpaperVideo) { Settings.wallpaperVideo = it }
        }
        Group("Canvas", "Canvas videos come from Spotify's unofficial web endpoints using your sp_dc cookie, stored only on this phone. Treat it like a password. They may stop working at any time.") {
            Column(Modifier.padding(14.dp)) {
                OutlinedTextField(Settings.spDc, { Settings.spDc = it.trim() }, singleLine = true, label = { Text("sp_dc cookie") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(Settings.canvasUrl, { Settings.canvasUrl = it }, singleLine = true, label = { Text("Custom Canvas endpoint (optional)") },
                    placeholder = { Text("https://example.com/canvas/{id}") }, modifier = Modifier.fillMaxWidth())
                CanvasProvider.lastError?.let { Text(it, fontSize = 12.sp, color = Color(0xFFFF8A80), modifier = Modifier.padding(top = 6.dp)) }
            }
        }
    }
}
