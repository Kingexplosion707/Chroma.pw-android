package com.metrolist.music.chroma

import android.Manifest
import android.app.Activity
import android.app.StatusBarManager
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.chroma.audio.AudioEffectsPage
import com.metrolist.music.chroma.spotify.SpotifyApi
import com.metrolist.music.chroma.spotify.SpotifyRemote
import com.metrolist.music.constants.DarkModeKey
import com.metrolist.music.constants.DynamicThemeKey
import com.metrolist.music.constants.EnableBetterLyricsKey
import com.metrolist.music.constants.EnableKugouKey
import com.metrolist.music.constants.EnableLrcLibKey
import com.metrolist.music.constants.EnableLyricsPlus
import com.metrolist.music.constants.EnablePaxsenixKey
import com.metrolist.music.constants.EnableZemerKey
import com.metrolist.music.constants.LyricsProviderOrderKey
import com.metrolist.music.constants.PureBlackKey
import com.metrolist.music.constants.SelectedThemeColorKey
import com.metrolist.music.constants.SleepTimerFadeOutKey
import com.metrolist.music.constants.TranslateLanguageKey
import com.metrolist.music.lyrics.LyricsProviderRegistry
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.launch

private const val VERSION = "Chroma 1.1.0-beta.1 for Android"

private fun textSlot(s: String?): (@Composable () -> Unit)? = if (s == null) null else { { Text(s) } }

private fun iconSlot(res: Int?): (@Composable () -> Unit)? = if (res == null) null else { { Icon(painterResource(res), null) } }

private fun go(nav: NavController, page: String) = nav.navigate("chroma/$page")

private fun switchItem(icon: Int?, title: String, desc: String?, value: Boolean, onChange: (Boolean) -> Unit) = Material3SettingsItem(
    icon = null,
    leadingContent = iconSlot(icon),
    title = { Text(title) },
    description = textSlot(desc),
    trailingContent = { Switch(checked = value, onCheckedChange = onChange) },
    onClick = { onChange(!value) },
)

private fun navItem(icon: Int, title: String, desc: String?, onClick: () -> Unit) = Material3SettingsItem(
    leadingContent = { Icon(painterResource(icon), null) },
    title = { Text(title) },
    description = textSlot(desc),
    onClick = onClick,
)

private fun textItem(title: String, desc: String? = null, onClick: (() -> Unit)? = null) = Material3SettingsItem(
    title = { Text(title) },
    description = textSlot(desc),
    onClick = onClick,
)

@Composable
private fun ChoiceGroup(title: String, options: List<Pair<String, String?>>, selected: Int, footer: String? = null, onSelect: (Int) -> Unit) {
    Material3SettingsGroup(
        title = title,
        items = options.mapIndexed { i, (name, desc) ->
            Material3SettingsItem(
                leadingContent = { RadioButton(selected = selected == i, onClick = { onSelect(i) }) },
                title = { Text(name) },
                description = textSlot(desc),
                onClick = { onSelect(i) },
            )
        },
    )
    if (footer != null) Footer(footer)
}

@Composable
private fun Footer(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
}

@Composable
private fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, label: String, steps: Int = 0, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChromaSettingsScreen(navController: NavController, page: String?) {
    val title = when (page) {
        null -> "Chroma Settings"
        "appearance" -> "Appearance"
        "home" -> "Home & Library"
        "player" -> "Player page"
        "lyrics" -> "Lyrics"
        "haptics" -> "Music Haptics"
        "audio" -> "Audio effects"
        "sleep" -> "Sleep Timer"
        "live" -> "Live Activity & lock screen"
        "stats" -> "Listening stats"
        "icon" -> "App icon"
        "shortcuts" -> "Shortcuts & Quick Settings"
        "account" -> "Account & backup"
        else -> "Chroma"
    }
    Column(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom))) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = { IconButton(onClick = { navController.navigateUp() }) { Icon(painterResource(R.drawable.arrow_back), null) } },
        )
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            when (page) {
                null -> HubPage(navController)
                "appearance" -> AppearancePage(navController)
                "home" -> HomeLibraryPage()
                "player" -> PlayerPage(navController)
                "lyrics" -> LyricsPage(navController)
                "haptics" -> HapticsPage()
                "audio" -> AudioEffectsPage()
                "sleep" -> SleepPage()
                "live" -> LivePage()
                "stats" -> StatsPage(navController)
                "icon" -> IconPage()
                "shortcuts" -> ShortcutsPage()
                "account" -> AccountPage()
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------- hub ----------------
@Composable
private fun HubPage(nav: NavController) {
    val ctx = LocalContext.current
    var access by remember { mutableStateOf(SpotifyRemote.hasAccess(ctx)) }
    LaunchedEffect(Unit) { while (true) { access = SpotifyRemote.hasAccess(ctx); kotlinx.coroutines.delay(1500) } }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.mipmap.chroma_icon_chroma), null, modifier = Modifier.size(84.dp).clip(RoundedCornerShape(20.dp)))
        Spacer(Modifier.height(8.dp))
        Text("Chroma", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(VERSION, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Material3SettingsGroup(
        title = "Spotify",
        items = listOf(
            textItem(
                if (SpotifyApi.loggedIn.value) "Signed in as ${SpotifyApi.userName.value}" else "Log in with Spotify",
                if (SpotifyApi.loggedIn.value) "Tap to log out" else "Your library, search, Connect and starting music (Premium) come from your account",
            ) { if (SpotifyApi.loggedIn.value) SpotifyApi.logout() else SpotifyApi.beginLogin(ctx) },
            textItem(
                if (access) "Connected to the Spotify app" else "Allow notification access",
                if (access) "Chroma sees and controls what Spotify plays" else "Needed to see and control Spotify, count plays and show the Live Activity",
            ) { ctx.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            textItem("Device broadcast status", "Turn it on in Spotify's settings so Chroma always knows the exact song, even signed out. Tap to open Spotify.") {
                SpotifyRemote.openSpotify(ctx, "spotify:internal:preferences")
            },
            switchItem(null, "Drive the Spotify app", "Metrolist's player, Home, Search and Library control Spotify. Off returns to YouTube Music after a restart.",
                ChromaPrefs.spotifyMode.value) { ChromaPrefs.spotifyMode.value = it },
        ),
    )
    Spacer(Modifier.height(16.dp))
    Material3SettingsGroup(
        title = "Looks",
        items = listOf(
            navItem(R.drawable.palette, "Appearance", "Accent colour, AMOLED, Liquid Glass") { go(nav, "appearance") },
            navItem(R.drawable.home_outlined, "Home & Library", "Shortcuts, shelves, filters, sort") { go(nav, "home") },
            navItem(R.drawable.play, "Player page", "Spotify Canvas, fluid artwork, background") { go(nav, "player") },
            navItem(R.drawable.app_logo, "App icon", "Chroma's icons") { go(nav, "icon") },
        ),
    )
    Spacer(Modifier.height(16.dp))
    Material3SettingsGroup(
        title = "Listening",
        items = listOf(
            navItem(R.drawable.lyrics, "Lyrics", "Sources, word timing, Spicy Lyrics key") { go(nav, "lyrics") },
            navItem(R.drawable.equalizer, "Audio effects", "Equalizer, AutoEq, bass, rooms, limiter") { go(nav, "audio") },
            navItem(R.drawable.graphic_eq, "Music Haptics", "Taps on the beat, in the background too") { go(nav, "haptics") },
            navItem(R.drawable.bedtime, "Sleep Timer", "Fade out, one minute option") { go(nav, "sleep") },
            navItem(R.drawable.notification, "Live Activity & lock screen", "Lyrics in a live update, lyrics wallpaper") { go(nav, "live") },
            navItem(R.drawable.stats, "Listening stats", "Counted on this phone, import Spotify data") { go(nav, "stats") },
            navItem(R.drawable.shortcut_library, "Shortcuts & Quick Settings", "Like, skip and sleep timer tiles") { go(nav, "shortcuts") },
        ),
    )
    Spacer(Modifier.height(16.dp))
    Material3SettingsGroup(
        title = "Account & backup",
        items = listOf(navItem(R.drawable.backup, "Account & backup", "Export, import or reset settings, what's new") { go(nav, "account") }),
    )
}

// ---------------- appearance ----------------
private val accentPalette = listOf(0xFFFA2D48, 0xFF1ED760, 0xFF2979FF, 0xFFAB47BC, 0xFFFF4081, 0xFFFF6E40, 0xFFFFC400, 0xFF00E5FF, 0xFF8D67AB, 0xFFFFFFFF)

@Composable
private fun AppearancePage(nav: NavController) {
    val (_, setThemeColor) = rememberPreference(SelectedThemeColorKey, 0xFFFA2D48.toInt())
    val (_, setDynamic) = rememberPreference(DynamicThemeKey, true)
    val (pureBlack, setPureBlack) = rememberPreference(PureBlackKey, false)
    val (_, setDarkMode) = rememberPreference(DarkModeKey, "AUTO")
    fun applyAccent(mode: Int, color: Int = ChromaPrefs.accentColor.value) {
        ChromaPrefs.accentMode.value = mode
        when (mode) {
            0 -> { setDynamic(false); setThemeColor(0xFF1ED760.toInt()) }
            1 -> { setDynamic(false); setThemeColor(0xFFFA2D48.toInt()) }
            2 -> { setDynamic(false); setThemeColor(color) }
            else -> setDynamic(true)
        }
    }
    ChoiceGroup(
        "Accent colour",
        listOf("Apple Music red" to null, "Spotify green" to null, "Pick a colour" to null, "Accent from the cover" to "Follows the cover of the song playing"),
        when (ChromaPrefs.accentMode.value) { 1 -> 0; 0 -> 1; 2 -> 2; else -> 3 },
    ) { i -> applyAccent(when (i) { 0 -> 1; 1 -> 0; 2 -> 2; else -> 3 }) }
    if (ChromaPrefs.accentMode.value == 2) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 10.dp)) {
            items(accentPalette) { c ->
                Box(Modifier.size(38.dp).clip(CircleShape).background(Color(c))
                    .border(if (ChromaPrefs.accentColor.value == c.toInt()) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    .clickable { ChromaPrefs.accentColor.value = c.toInt(); applyAccent(2, c.toInt()) })
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Material3SettingsGroup(
        title = "Look",
        items = listOf(
            switchItem(R.drawable.contrast, "AMOLED background", "Pure black, dark mode on", pureBlack) { setPureBlack(it); if (it) setDarkMode("ON"); ChromaPrefs.amoled.value = it },
            switchItem(R.drawable.nav_bar, "Liquid Glass tab bar", "A floating translucent tab bar", ChromaPrefs.glassBar.value) { ChromaPrefs.glassBar.value = it },
            switchItem(R.drawable.queue_music, "Liquid Glass mini player", "Glass tinted by the cover", ChromaPrefs.glassMiniPlayer.value) { ChromaPrefs.glassMiniPlayer.value = it },
            switchItem(R.drawable.slow_motion_video, "Low Power Mode pauses animations", "Canvas and fluid artwork hold still in Battery Saver", ChromaPrefs.lowPowerPausesAnimations.value) { ChromaPrefs.lowPowerPausesAnimations.value = it },
            navItem(R.drawable.palette, "More appearance", "Metrolist's theme, player and lyrics looks") { nav.navigate("settings/appearance") },
        ),
    )
}

// ---------------- home & library ----------------
@Composable
private fun HomeLibraryPage() {
    Material3SettingsGroup(
        title = "Hide on Home",
        items = listOf(
            switchItem(null, "Filter pills", null, !ChromaPrefs.homeFilterPills.value) { ChromaPrefs.homeFilterPills.value = !it },
            switchItem(null, "Shortcuts grid", null, !ChromaPrefs.homeShortcuts.value) { ChromaPrefs.homeShortcuts.value = !it },
            switchItem(null, "Recently played", null, !ChromaPrefs.homeRecentlyPlayed.value) { ChromaPrefs.homeRecentlyPlayed.value = !it },
            switchItem(null, "On repeat", null, !ChromaPrefs.homeOnRepeat.value) { ChromaPrefs.homeOnRepeat.value = !it },
            switchItem(null, "Your top artists", null, !ChromaPrefs.homeTopArtists.value) { ChromaPrefs.homeTopArtists.value = !it },
            switchItem(null, "Podcasts and audiobooks", "Takes shows off Home", !ChromaPrefs.homePodcasts.value) { ChromaPrefs.homePodcasts.value = !it },
        ),
    )
    Spacer(Modifier.height(16.dp))
    Material3SettingsGroup(
        title = "Library settings",
        items = listOf(
            switchItem(null, "Grid view", null, ChromaPrefs.libraryGrid.value) { ChromaPrefs.libraryGrid.value = it },
            switchItem(null, "Denser rows", null, ChromaPrefs.denserRows.value) { ChromaPrefs.denserRows.value = it },
            switchItem(null, "Hide filter chips", null, ChromaPrefs.hideFilterChips.value) { ChromaPrefs.hideFilterChips.value = it },
        ),
    )
    Spacer(Modifier.height(16.dp))
    ChoiceGroup("Recents sort order", listOf("Recents" to null, "Recently added" to null, "Alphabetical" to null, "Creator" to null), ChromaPrefs.librarySort.value) {
        ChromaPrefs.librarySort.value = it
    }
}

// ---------------- player ----------------
@Composable
private fun PlayerPage(nav: NavController) {
    Material3SettingsGroup(
        title = "Player background",
        items = listOf(
            switchItem(R.drawable.slow_motion_video, "Spotify Canvas", "The song's looping clip behind the player, when it has one", ChromaPrefs.canvas.value) { ChromaPrefs.canvas.value = it },
            switchItem(R.drawable.gradient, "Fluid artwork", "For songs without a clip: the cover, warped and blurred, drifting slowly (Android 13+)", ChromaPrefs.fluidArtwork.value) { ChromaPrefs.fluidArtwork.value = it },
            navItem(R.drawable.tune, "Background style", "Canvas and fluid artwork show with Metrolist's Blur style") { nav.navigate("settings/player") },
        ),
    )
    Footer("Asked top to bottom until one has a clip. Canvas comes with your Spotify login; if Spotify refuses it, an sp_dc cookie below is tried.")
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = ChromaPrefs.spDc.value, onValueChange = { ChromaPrefs.spDc.value = it.trim() }, singleLine = true,
        label = { Text("sp_dc cookie (optional, stays on this phone)") }, visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    com.metrolist.music.chroma.player.CanvasProvider.lastError?.let { Footer("Last Canvas error: $it") }
}

// ---------------- lyrics ----------------
private val sourceInfo = mapOf(
    "SpicyLyrics" to ("Spicy Lyrics" to "Community syncs, word timing"),
    "BiniLyrics" to ("BiniLyrics" to "Apple Music word timing"),
    "Musixmatch" to ("Musixmatch" to "Spotify's licensed catalogue"),
    "Unison" to ("Unison" to "Hand-timed, few tracks"),
    "NetEase" to ("NetEase" to "Word timing, censored"),
    "KuGouKRC" to ("KuGou" to "Word timing, Chinese pop"),
    "LrcLib" to ("LRCLIB" to "Line timing, open fallback"),
    "BetterLyrics" to ("BetterLyrics" to "Apple Music timing (boidu)"),
    "Paxsenix" to ("Paxsenix" to "Mixed sources"),
    "LyricsPlus" to ("LyricsPlus" to "Word timing"),
    "KuGou" to ("KuGou (LRC)" to "Line timing"),
    "Zemer" to ("Zemer" to "Hebrew lyrics"),
    "YouTubeSubtitle" to ("YouTube captions" to "For YouTube Music only"),
    "YouTube" to ("YouTube Music" to "For YouTube Music only"),
)

@Composable
private fun LyricsPage(nav: NavController) {
    val ctx = LocalContext.current
    val (order, setOrder) = rememberPreference(LyricsProviderOrderKey, LyricsProviderRegistry.serializeProviderOrder(LyricsProviderRegistry.getDefaultProviderOrder()))
    val (lrclib, setLrclib) = rememberPreference(EnableLrcLibKey, true)
    val (better, setBetter) = rememberPreference(EnableBetterLyricsKey, true)
    val (pax, setPax) = rememberPreference(EnablePaxsenixKey, true)
    val (plus, setPlus) = rememberPreference(EnableLyricsPlus, false)
    val (kugou, setKugou) = rememberPreference(EnableKugouKey, true)
    val (zemer, setZemer) = rememberPreference(EnableZemerKey, true)
    val list = LyricsProviderRegistry.deserializeProviderOrder(order)
    fun enabled(n: String): Boolean? = when (n) {
        "SpicyLyrics" -> ChromaPrefs.enableSpicy.value
        "BiniLyrics" -> ChromaPrefs.enableBini.value
        "Musixmatch" -> ChromaPrefs.enableMusixmatch.value
        "Unison" -> ChromaPrefs.enableUnison.value
        "NetEase" -> ChromaPrefs.enableNetease.value
        "KuGouKRC" -> ChromaPrefs.enableKugouKrc.value
        "LrcLib" -> lrclib
        "BetterLyrics" -> better
        "Paxsenix" -> pax
        "LyricsPlus" -> plus
        "KuGou" -> kugou
        "Zemer" -> zemer
        else -> null
    }
    fun setEnabled(n: String, v: Boolean) {
        when (n) {
            "SpicyLyrics" -> ChromaPrefs.enableSpicy.value = v
            "BiniLyrics" -> ChromaPrefs.enableBini.value = v
            "Musixmatch" -> ChromaPrefs.enableMusixmatch.value = v
            "Unison" -> ChromaPrefs.enableUnison.value = v
            "NetEase" -> ChromaPrefs.enableNetease.value = v
            "KuGouKRC" -> ChromaPrefs.enableKugouKrc.value = v
            "LrcLib" -> setLrclib(v)
            "BetterLyrics" -> setBetter(v)
            "Paxsenix" -> setPax(v)
            "LyricsPlus" -> setPlus(v)
            "KuGou" -> setKugou(v)
            "Zemer" -> setZemer(v)
        }
    }
    fun move(n: String, by: Int) {
        val l = list.toMutableList(); val i = l.indexOf(n); val j = i + by
        if (i < 0 || j !in l.indices) return
        l.add(j, l.removeAt(i)); setOrder(LyricsProviderRegistry.serializeProviderOrder(l))
    }
    Material3SettingsGroup(
        title = "Lyrics sources",
        items = list.map { n ->
            val (name, desc) = sourceInfo[n] ?: (n to null)
            val on = enabled(n)
            Material3SettingsItem(
                title = { Text(name) },
                description = textSlot(desc?.let { d -> if (n == "SpicyLyrics" && !ChromaPrefs.spicyKey.value.startsWith("sl_pk_")) "$d · Needs a key" else d }),
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { move(n, -1) }) { Icon(painterResource(R.drawable.arrow_upward), "Up") }
                        IconButton(onClick = { move(n, 1) }) { Icon(painterResource(R.drawable.arrow_downward), "Down") }
                        if (on != null) Switch(checked = on, onCheckedChange = { setEnabled(n, it) })
                    }
                },
            )
        },
    )
    Footer("Asked top to bottom until one has word timing. Sources get only the track, never your account.")
    Spacer(Modifier.height(12.dp))
    val key = ChromaPrefs.spicyKey.value
    OutlinedTextField(
        value = key, onValueChange = { ChromaPrefs.spicyKey.value = it.trim() }, singleLine = true,
        label = { Text("Spicy Lyrics key") },
        supportingText = {
            Text(when {
                key.isEmpty() -> "Not set"
                key.startsWith("sl_sk_") -> "That is a secret key, which belongs on a server. Use the application's publishable key, which starts with sl_pk_."
                !key.startsWith("sl_pk_") -> "A publishable key starts with sl_pk_."
                else -> "Set"
            })
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Footer("Spicy Lyrics needs a free key of your own. Sign up on its developer platform, create an application, turn on client access with No origin header allowed, and paste the publishable key here.")
    TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://developers.spicylyrics.org/dashboard")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Open the Spicy Lyrics developer platform") }
    Spacer(Modifier.height(12.dp))
    Material3SettingsGroup(
        title = "Word timing",
        items = listOf(
            switchItem(null, "Keep asking until word timing", "Off takes the first source that has any lyrics", ChromaPrefs.preferWordTiming.value) { ChromaPrefs.preferWordTiming.value = it },
            switchItem(null, "Simulate word timing", "Line-timed lyrics fill word by word, timed by each word's length", ChromaPrefs.simulateWordTiming.value) { ChromaPrefs.simulateWordTiming.value = it },
        ),
    )
    Spacer(Modifier.height(16.dp))
    val (_, setMetrolistLang) = rememberPreference(TranslateLanguageKey, "en")
    var pickLang by remember { mutableStateOf(false) }
    val langName = translationLanguages.firstOrNull { it.first == ChromaPrefs.translationLanguage.value }?.second ?: "Off"
    Material3SettingsGroup(
        title = "Lyrics look and translation",
        items = listOf(
            navItem(R.drawable.translate, "Lyrics translation", "$langName · Musixmatch's community translations, under each line") { pickLang = true },
            navItem(R.drawable.language, "Translate with AI", "Any song, with a key of your own (Gemini, OpenRouter or DeepL)") { nav.navigate("settings/ai") },
            navItem(R.drawable.lyrics, "Lyrics look", "Text size, glow, blur, animation, position") { nav.navigate("settings/player") },
            navItem(R.drawable.translate, "Pronunciation", "Romanization of Japanese, Korean, Chinese and more") { nav.navigate("settings/content/romanization") },
        ),
    )
    if (pickLang) AlertDialog(
        onDismissRequest = { pickLang = false },
        title = { Text("Translate lyrics into") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(translationLanguages) { (code, name) ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            ChromaPrefs.translationLanguage.value = code
                            if (code.isNotEmpty()) setMetrolistLang(code)
                            pickLang = false
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = ChromaPrefs.translationLanguage.value == code, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(name)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { pickLang = false }) { Text("Close") } },
    )
}

private val translationLanguages = listOf(
    "" to "Off", "en" to "English", "es" to "Spanish", "pt" to "Portuguese", "fr" to "French", "de" to "German",
    "it" to "Italian", "nl" to "Dutch", "sv" to "Swedish", "pl" to "Polish", "ro" to "Romanian", "el" to "Greek",
    "tr" to "Turkish", "ru" to "Russian", "uk" to "Ukrainian", "ar" to "Arabic", "fa" to "Persian", "he" to "Hebrew",
    "hi" to "Hindi", "id" to "Indonesian", "vi" to "Vietnamese", "th" to "Thai", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese",
)

// ---------------- music haptics ----------------
@Composable
private fun HapticsPage() {
    val ctx = LocalContext.current
    val projLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) {
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
    Material3SettingsGroup(
        title = null,
        items = listOf(switchItem(R.drawable.graphic_eq, "Music Haptics", "Following the song, in the background and on the lock screen", on) {
            if (on) ctx.startService(Intent(ctx, HapticService::class.java).setAction(HapticService.ACTION_STOP))
            else permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
        }),
    )
    Footer("Android asks for screen-capture permission: only audio is captured, never the screen.")
    Spacer(Modifier.height(12.dp))
    ChoiceGroup(
        "Vibrations",
        listOf(
            "Kick, snare and bass" to "A tap on each kick and snare, and a rumble under the bass",
            "Kick and snare" to "A tap on each kick and snare, no rumble",
            "Kick and bass" to "A tap on each kick, and a rumble under the bass",
        ),
        ChromaPrefs.hapticMode.value,
    ) { ChromaPrefs.hapticMode.value = it }
    SliderRow("Strength", ChromaPrefs.hapticIntensity.value, 0.2f..1f, "${(ChromaPrefs.hapticIntensity.value * 100).toInt()}%") { ChromaPrefs.hapticIntensity.value = it }
}

// ---------------- sleep timer ----------------
@Composable
private fun SleepPage() {
    val pc = LocalPlayerConnection.current
    val (fade, setFade) = rememberPreference(SleepTimerFadeOutKey, false)
    val timer = pc?.service?.sleepTimer
    Material3SettingsGroup(
        title = null,
        items = listOf(
            switchItem(R.drawable.volume_down, "Fade out", "Lowers the music over the last minute", fade) { setFade(it); ChromaPrefs.sleepFade.value = it },
            switchItem(R.drawable.timer, "One minute option", null, ChromaPrefs.sleepOneMinute.value) { ChromaPrefs.sleepOneMinute.value = it },
        ),
    )
    Spacer(Modifier.height(16.dp))
    val options = (if (ChromaPrefs.sleepOneMinute.value) listOf(1) else emptyList()) + listOf(5, 10, 15, 30, 45, 60)
    Material3SettingsGroup(
        title = if (timer?.isActive == true) "Timer is on" else "Start the timer",
        items = buildList {
            if (timer?.isActive == true) add(textItem("Turn off the timer") { timer.clear() })
            options.forEach { m -> add(textItem(if (m == 60) "In an hour" else "In $m minutes") { timer?.start(m, false, fade) }) }
            add(textItem("At the end of this song", "Spotify will pause at the end of this song") { timer?.start(-1, false, fade) })
        },
    )
    if (timer == null) Footer("Open the player once so the timer is ready.")
}

// ---------------- live activity & lock screen ----------------
@Composable
private fun LivePage() {
    val ctx = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    Material3SettingsGroup(
        title = "Live Activity",
        items = listOf(
            switchItem(R.drawable.notification, "Live Activity", "The song and its current lyric line as a live update, in the status bar and on the lock screen", ChromaPrefs.liveActivity.value) {
                ChromaPrefs.liveActivity.value = it
                if (it && Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            switchItem(R.drawable.translate, "Translation under the line", "Pick a translation language in Lyrics first", ChromaPrefs.liveActivityTranslation.value) { ChromaPrefs.liveActivityTranslation.value = it },
        ),
    )
    Footer("On Android 16 it shows as a Live Update chip. Needs notification access and notifications allowed.")
    Spacer(Modifier.height(16.dp))
    Material3SettingsGroup(
        title = "Lock screen lyrics",
        items = listOf(
            textItem("Set the lyrics wallpaper", "Blurred cover, big artwork, title and word-by-word lyrics on the home and lock screen") {
                val i = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                    .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(ctx, ChromaWallpaperService::class.java))
                try { ctx.startActivity(i) } catch (_: Exception) { ctx.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) }
            },
        ),
    )
    Spacer(Modifier.height(12.dp))
    ChoiceGroup("Wallpaper lyrics alignment", listOf("Left" to null, "Center" to null, "Right" to null), ChromaPrefs.wallpaperAlign.value) { ChromaPrefs.wallpaperAlign.value = it }
}

// ---------------- listening stats ----------------
@Composable
private fun StatsPage(nav: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    val db = com.metrolist.music.LocalDatabase.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) { status = "Nothing was picked."; return@rememberLauncherForActivityResult }
        status = "Import under way"
        scope.launch { status = SpotifyHistoryImport.import(ctx, db, uris) }
    }
    Material3SettingsGroup(
        title = "On this phone",
        items = listOf(
            switchItem(R.drawable.history, "Keep listening history", "Plays of 30 seconds or more are counted", ChromaPrefs.keepHistory.value) { ChromaPrefs.keepHistory.value = it },
            navItem(R.drawable.stats, "Top songs, artists and albums", "Week, month, year or all time") { nav.navigate("stats") },
            navItem(R.drawable.history, "Listening history", null) { nav.navigate("history") },
        ),
    )
    Spacer(Modifier.height(16.dp))
    Material3SettingsGroup(
        title = "Import Spotify data",
        items = listOf(textItem("Import Spotify data", "Pick the StreamingHistory .json files (or the .zip) from Spotify's privacy export") {
            picker.launch(arrayOf("application/json", "application/zip", "application/octet-stream", "*/*"))
        }),
    )
    status?.let { Footer(it) }
    Footer("Request your data at spotify.com/account/privacy. Extended streaming history keeps Spotify's track ids; the account-data export only has names.")
}

// ---------------- app icon ----------------
private val chromaIcons = listOf(
    "MainActivityAlias" to "Chroma", "ChromaIconCobalt" to "Chroma Cobalt", "ChromaIconDark" to "Chroma Dark",
    "ChromaIconFlat" to "Chroma Flat", "ChromaIconMono" to "Chroma Mono", "ChromaIconRings" to "Chroma Rings",
)
private val iconRes = mapOf(
    "MainActivityAlias" to R.mipmap.chroma_icon_chroma, "ChromaIconCobalt" to R.mipmap.chroma_icon_chromacobalt, "ChromaIconDark" to R.mipmap.chroma_icon_chromadark,
    "ChromaIconFlat" to R.mipmap.chroma_icon_chromaflat, "ChromaIconMono" to R.mipmap.chroma_icon_chromamono, "ChromaIconRings" to R.mipmap.chroma_icon_chromarings,
)

private fun setChromaIcon(ctx: Context, chosen: String) {
    val pm = ctx.packageManager
    (chromaIcons.map { it.first } + "MainActivityStatic").forEach { alias ->
        runCatching {
            pm.setComponentEnabledSetting(
                ComponentName(ctx, "com.metrolist.music.$alias"),
                if (alias == chosen) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
    ChromaPrefs.icon.value = chosen
}

@Composable
private fun IconPage() {
    val ctx = LocalContext.current
    Material3SettingsGroup(
        title = "App icon",
        items = chromaIcons.map { (alias, name) ->
            val res = iconRes.getValue(alias)
            Material3SettingsItem(
                leadingContent = { Image(painterResource(res), null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))) },
                title = { Text(name) },
                trailingContent = { RadioButton(selected = (ChromaPrefs.icon.value.ifEmpty { "MainActivityAlias" }) == alias, onClick = { setChromaIcon(ctx, alias) }) },
                onClick = { setChromaIcon(ctx, alias) },
            )
        },
    )
    Footer("Your launcher may take a moment to show the new icon. Chroma's other iOS icons are Spotify's logo, so they aren't included.")
}

// ---------------- shortcuts ----------------
@Composable
private fun ShortcutsPage() {
    val ctx = LocalContext.current
    fun addTile(cls: Class<*>, label: String, icon: Int) {
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.getSystemService(StatusBarManager::class.java)?.requestAddTileService(
                ComponentName(ctx, cls), label, android.graphics.drawable.Icon.createWithResource(ctx, icon), ctx.mainExecutor,
            ) {}
        }
    }
    Material3SettingsGroup(
        title = "Quick Settings tiles",
        items = listOf(
            textItem("Add “Like song”", "Likes what Spotify is playing") { addTile(ChromaLikeTile::class.java, "Like song", R.drawable.favorite) },
            textItem("Add “Next song”", null) { addTile(ChromaNextTile::class.java, "Next song", R.drawable.skip_next) },
            textItem("Add “Sleep timer”", "Pauses Spotify in 30 minutes, with a fade") { addTile(ChromaSleepTile::class.java, "Sleep timer", R.drawable.bedtime) },
        ),
    )
    Footer("You can also add them from the Quick Settings edit screen. Long-press Chroma's icon for Like, Next and Sleep timer shortcuts.")
}

// ---------------- account & backup ----------------
@Composable
private fun AccountPage() {
    val ctx = LocalContext.current
    var confirmReset by remember { mutableStateOf(false) }
    var whatsNew by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        status = runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(ChromaPrefs.exportJson().toByteArray()) }; "Settings exported" }.getOrElse { "Couldn't export" }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val text = runCatching { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() }.getOrNull()
        status = if (text != null && ChromaPrefs.importJson(text)) "Settings imported" else "Not a settings file. Pick a file made by Export settings."
    }
    Material3SettingsGroup(
        title = "Settings backup",
        items = listOf(
            textItem("Export settings", "Saves Chroma's switches and sliders to a file (never your sp_dc cookie)") { exporter.launch("chroma-settings.json") },
            textItem("Import settings", "Pick a file made by Export settings") { importer.launch(arrayOf("application/json", "*/*")) },
            textItem("Reset all settings") { confirmReset = true },
        ),
    )
    status?.let { Footer(it) }
    Spacer(Modifier.height(16.dp))
    Material3SettingsGroup(
        title = "About",
        items = listOf(
            textItem("What's new", VERSION) { whatsNew = true },
            textItem("Chroma for iOS", "chroma.pw") {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chroma.pw")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            textItem("Licenses", "Built on Metrolist (GPL-3.0). Chroma's look and features follow chroma.pw.") {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/metrolistgroup/metrolist")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
        ),
    )
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Reset all settings?") },
        text = { Text("Chroma's settings go back to their defaults. Your Spotify login stays.") },
        confirmButton = { TextButton(onClick = { ChromaPrefs.resetAll(); confirmReset = false; status = "Settings reset" }) { Text("Reset") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
    )
    if (whatsNew) AlertDialog(
        onDismissRequest = { whatsNew = false },
        title = { Text("What's new") },
        text = {
            Text(
                "• Metrolist's player, Home, Search and Library drive the Spotify app\n" +
                    "• Lyrics sources in Chroma's order: Spicy Lyrics, BiniLyrics, Musixmatch, Unison, NetEase, KuGou, LRCLIB — asked until one has word timing\n" +
                    "• Spotify Canvas and fluid artwork behind the player\n" +
                    "• Live Activity with the current lyric line\n" +
                    "• Audio effects on Spotify's sound: equalizer, AutoEq, bass, rooms, limiter\n" +
                    "• Music Haptics styles, sleep timer fade, Quick Settings tiles\n" +
                    "• Listening stats counted on this phone, Spotify data import",
            )
        },
        confirmButton = { TextButton(onClick = { whatsNew = false }) { Text("Done") } },
    )
}
