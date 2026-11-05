package com.metrolist.music.chroma

import android.Manifest
import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.metrolist.music.R
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem

private val hapticModes = listOf("Bass pulse", "Beat", "Beat + Bass")
private val aligns = listOf("Left", "Center", "Right")

/** Chroma section at the top of Appearance settings. */
@Composable
fun ChromaSettingsGroup() {
    val ctx = LocalContext.current
    ChromaPrefs.init(ctx)
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
    val hapticsOn = HapticService.running.value

    Material3SettingsGroup(
        title = "Chroma",
        items = listOf(
            Material3SettingsItem(
                icon = painterResource(R.drawable.palette),
                title = { Text("Liquid glass tab bar") },
                description = { Text("Floating translucent tab bar") },
                trailingContent = { Switch(ChromaPrefs.glassBar.value, { ChromaPrefs.setGlassBar(it) }) },
                onClick = { ChromaPrefs.setGlassBar(!ChromaPrefs.glassBar.value) },
            ),
            Material3SettingsItem(
                icon = painterResource(R.drawable.palette),
                title = { Text("Liquid glass mini player") },
                description = { Text("Glass panel tinted with the album colour") },
                trailingContent = { Switch(ChromaPrefs.glassMiniPlayer.value, { ChromaPrefs.setGlassMiniPlayer(it) }) },
                onClick = { ChromaPrefs.setGlassMiniPlayer(!ChromaPrefs.glassMiniPlayer.value) },
            ),
            Material3SettingsItem(
                icon = painterResource(R.drawable.graphic_eq),
                title = { Text("Music haptics") },
                description = { Text("Vibrate to the beat, even with the screen off. Android asks for screen-capture permission; only audio is captured.") },
                trailingContent = {
                    Switch(hapticsOn, {
                        if (hapticsOn) ctx.startService(Intent(ctx, HapticService::class.java).setAction(HapticService.ACTION_STOP))
                        else permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
                    })
                },
            ),
            Material3SettingsItem(
                icon = painterResource(R.drawable.tune),
                title = { Text("Haptics style") },
                description = { Text("${hapticModes[ChromaPrefs.hapticMode.intValue]} • ${(ChromaPrefs.hapticIntensity.floatValue * 100).toInt()}% — tap to change") },
                onClick = {
                    val m = ChromaPrefs.hapticMode.intValue
                    if (m < 2) ChromaPrefs.setHapticMode(m + 1)
                    else {
                        ChromaPrefs.setHapticMode(0)
                        val next = ChromaPrefs.hapticIntensity.floatValue + .2f
                        ChromaPrefs.setHapticIntensity(if (next > 1.01f) .4f else next)
                    }
                },
            ),
            Material3SettingsItem(
                icon = painterResource(R.drawable.lyrics),
                title = { Text("Lyrics live wallpaper") },
                description = { Text("Blurred cover, big centred artwork, title and word-by-word lyrics on your home and lock screen") },
                onClick = {
                    val i = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(ctx, ChromaWallpaperService::class.java))
                    try { ctx.startActivity(i) } catch (_: Exception) { ctx.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) }
                },
            ),
            Material3SettingsItem(
                icon = painterResource(R.drawable.lyrics),
                title = { Text("Wallpaper lyrics alignment") },
                description = { Text("${aligns[ChromaPrefs.wallpaperAlign.intValue]} — tap to change") },
                onClick = { ChromaPrefs.setWallpaperAlign((ChromaPrefs.wallpaperAlign.intValue + 1) % 3) },
            ),
        ),
    )
}
