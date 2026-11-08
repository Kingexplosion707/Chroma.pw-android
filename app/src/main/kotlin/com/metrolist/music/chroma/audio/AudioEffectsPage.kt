package com.metrolist.music.chroma.audio

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.chroma.ChromaPrefs
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import kotlinx.coroutines.launch

private fun fmtDb(v: Float) = (if (v > 0f) "+" else "") + String.format("%.1f dB", v)

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
}

@Composable
private fun LabeledSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, label: String, enabled: Boolean = true, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = { onChange(it); ChromaAudioFx.apply() }, valueRange = range, enabled = enabled)
    }
}

private fun toggle(title: String, desc: String?, value: Boolean, onChange: (Boolean) -> Unit) = Material3SettingsItem(
    title = { Text(title) },
    description = if (desc == null) null else { { Text(desc) } },
    trailingContent = { Switch(checked = value, onCheckedChange = { onChange(it); ChromaAudioFx.apply() }) },
    onClick = { onChange(!value); ChromaAudioFx.apply() },
)

@Composable
private fun Choices(title: String, desc: String?, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Material3SettingsGroup(
        title = title,
        items = options.mapIndexed { i, name ->
            Material3SettingsItem(
                leadingContent = { RadioButton(selected = selected == i, onClick = { onSelect(i); ChromaAudioFx.apply() }) },
                title = { Text(name) },
                onClick = { onSelect(i); ChromaAudioFx.apply() },
            )
        },
    )
    desc?.let { Note(it) }
}

@Composable
fun AudioEffectsPage() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { ChromaAudioFx.init(ctx) }
    var saving by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var autoEq by remember { mutableStateOf(false) }
    var paste by remember { mutableStateOf(false) }
    val on = ChromaPrefs.fxEnabled.value

    Material3SettingsGroup(
        items = listOf(toggle("Audio effects", ChromaAudioFx.status.value, on) { ChromaPrefs.fxEnabled.value = it }),
    )
    Note("Spotify hands its sound to equalizers when it starts playing. If it hasn't yet, the effects go on all the phone's audio.")
    Spacer(Modifier.height(12.dp))

    // ---- presets ----
    val presets = remember(ChromaPrefs.fxPresets.value) { ChromaAudioFx.presets() }
    Material3SettingsGroup(
        title = "My presets",
        items = buildList {
            presets.forEach { (name, _) ->
                add(Material3SettingsItem(
                    title = { Text(name) },
                    trailingContent = {
                        IconButton(onClick = { ChromaAudioFx.deletePreset(name) }) { Icon(painterResource(R.drawable.delete), "Delete preset") }
                    },
                    onClick = { ChromaAudioFx.applyPreset(name) },
                ))
            }
            add(Material3SettingsItem(title = { Text("Save as preset") }, description = { Text("A preset keeps the effects that are on") }, onClick = { saving = true }))
            add(Material3SettingsItem(title = { Text("Reset all effects") }, onClick = { confirmReset = true }))
        },
    )
    Spacer(Modifier.height(16.dp))

    // ---- output control ----
    Material3SettingsGroup(title = "Output control", items = listOf(Material3SettingsItem(title = { Text("Gain and limiter, always on") })))
    LabeledSlider("Post gain", ChromaPrefs.fxPostGain.value, -12f..12f, fmtDb(ChromaPrefs.fxPostGain.value)) { ChromaPrefs.fxPostGain.value = Math.round(it * 2) / 2f }
    LabeledSlider("Limiter threshold", ChromaPrefs.fxLimiterThreshold.value, -24f..0f, fmtDb(ChromaPrefs.fxLimiterThreshold.value)) { ChromaPrefs.fxLimiterThreshold.value = Math.round(it * 2) / 2f }
    LabeledSlider("Limiter release", ChromaPrefs.fxLimiterRelease.value, 10f..500f, "${ChromaPrefs.fxLimiterRelease.value.toInt()} ms") { ChromaPrefs.fxLimiterRelease.value = Math.round(it / 5f) * 5f }
    Spacer(Modifier.height(16.dp))

    // ---- equalizer ----
    Material3SettingsGroup(
        title = "Equalizer",
        items = listOf(toggle("Equalizer", ChromaPrefs.fxEqName.value, ChromaPrefs.fxEqOn.value) { ChromaPrefs.fxEqOn.value = it }),
    )
    Text("Equalizer presets", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 10.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        items(ChromaAudioFx.EQ_PRESETS) { (name, gains) ->
            FilterChip(
                selected = ChromaPrefs.fxEqName.value == name,
                onClick = { ChromaAudioFx.setEqGains(gains, name); ChromaPrefs.fxEqOn.value = true; ChromaAudioFx.apply() },
                label = { Text(name) },
            )
        }
    }
    val gains = ChromaAudioFx.eqGains()
    ChromaAudioFx.EQ_FREQS.forEachIndexed { i, f ->
        val label = if (f >= 1000) "${f / 1000} kHz" else "$f Hz"
        LabeledSlider(label, gains[i], -12f..12f, fmtDb(gains[i]), enabled = ChromaPrefs.fxEqOn.value) { v ->
            val g = ChromaAudioFx.eqGains(); g[i] = Math.round(v * 2) / 2f
            ChromaAudioFx.setEqGains(g, "Custom")
        }
    }
    Spacer(Modifier.height(16.dp))

    // ---- graphic EQ / AutoEq ----
    val curveName = ChromaPrefs.fxAutoEqName.value
    Material3SettingsGroup(
        title = "Graphic EQ",
        items = listOf(
            toggle("Graphic EQ", if (ChromaPrefs.fxAutoEqCurve.value.isEmpty()) "Your headphones' correction, or any curve" else curveName.ifEmpty { "Your curve" },
                ChromaPrefs.fxGraphicEqOn.value) { ChromaPrefs.fxGraphicEqOn.value = it },
            Material3SettingsItem(title = { Text("AutoEq's correction for your model") }, description = { Text("GraphicEQ files for thousands of headphones") }, onClick = { autoEq = true }),
            Material3SettingsItem(title = { Text("Paste a GraphicEQ line") }, description = { Text("From AutoEq, Wavelet or Equalizer APO") }, onClick = { paste = true }),
            Material3SettingsItem(title = { Text("Back to flat") }, onClick = {
                ChromaPrefs.fxAutoEqCurve.value = ""; ChromaPrefs.fxAutoEqName.value = ""; ChromaPrefs.fxGraphicEqOn.value = false; ChromaAudioFx.apply()
            }),
        ),
    )
    Spacer(Modifier.height(16.dp))

    // ---- bass boost ----
    Material3SettingsGroup(title = "Bass boost", items = listOf(toggle("Bass boost", "Lifts the low end", ChromaPrefs.fxBassOn.value) { ChromaPrefs.fxBassOn.value = it }))
    LabeledSlider("Maximum gain", ChromaPrefs.fxBassBoost.value.toFloat(), 1f..15f, "${ChromaPrefs.fxBassBoost.value} dB", enabled = ChromaPrefs.fxBassOn.value) { ChromaPrefs.fxBassBoost.value = Math.round(it) }
    Spacer(Modifier.height(16.dp))

    Choices("Multiband compander", "Evens out or livens up the dynamics",
        listOf("Off", "Evens out", "Livens up", "Evens out and livens up"), ChromaPrefs.fxCompander.value) { ChromaPrefs.fxCompander.value = it }
    Spacer(Modifier.height(16.dp))

    Choices("A room around the music", null, listOf("Off") + ChromaAudioFx.ROOMS, ChromaPrefs.fxReverb.value + 1) { ChromaPrefs.fxReverb.value = it - 1 }
    Spacer(Modifier.height(16.dp))

    Material3SettingsGroup(title = "Stereo widening", items = listOf(toggle("Stereo widening", "A wider stereo image", ChromaPrefs.fxWideningOn.value) { ChromaPrefs.fxWideningOn.value = it }))
    LabeledSlider("Width", ChromaPrefs.fxWidening.value.toFloat(), 0f..100f, "${ChromaPrefs.fxWidening.value}%", enabled = ChromaPrefs.fxWideningOn.value) { ChromaPrefs.fxWidening.value = Math.round(it) }
    Note("Rooms and widening use your phone's own effects, so some phones only apply them on headphones.")
    Note("AutoEq: MIT License, Copyright (c) 2018-2022 Jaakko Pasanen")

    if (saving) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text("Save as preset") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true, label = { Text("Name") }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { ChromaAudioFx.savePreset(name.trim()); saving = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { saving = false }) { Text("Cancel") } },
        )
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Reset all effects?") },
        text = { Text("Every effect goes off and back to its defaults. The headphone correction stays.") },
        confirmButton = { TextButton(onClick = { ChromaAudioFx.resetEffects(); confirmReset = false }) { Text("Reset") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
    )
    if (autoEq) AutoEqDialog { autoEq = false }
    if (paste) PasteDialog { paste = false }
}

@Composable
private fun AutoEqDialog(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var all by remember { mutableStateOf<List<ChromaAudioFx.Headphone>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { ChromaAudioFx.headphones(ctx) }.onSuccess { all = it }.onFailure { error = "Could not load the list" }
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("AutoEq") },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Search headphones") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                val list = all
                when {
                    error != null -> Text(error!!)
                    list == null || busy -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(); Spacer(Modifier.width(12.dp)); Text(if (busy) "Getting the correction" else "Loading the list") }
                    else -> {
                        val q = query.trim()
                        val hits = remember(q, list) { (if (q.isEmpty()) list else list.filter { it.name.contains(q, ignoreCase = true) }).take(150) }
                        if (hits.isEmpty()) Text("No headphones match")
                        LazyColumn(Modifier.heightIn(max = 380.dp)) {
                            items(hits) { h ->
                                Column(Modifier.fillMaxWidth().clickable {
                                    busy = true
                                    scope.launch {
                                        runCatching { ChromaAudioFx.correction(h) }
                                            .onSuccess {
                                                ChromaPrefs.fxAutoEqCurve.value = it
                                                ChromaPrefs.fxAutoEqName.value = h.name
                                                ChromaPrefs.fxGraphicEqOn.value = true
                                                ChromaAudioFx.apply()
                                                onDone()
                                            }
                                            .onFailure { busy = false; error = "Could not get the correction" }
                                    }
                                }.padding(vertical = 8.dp)) {
                                    Text(h.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (h.source.isNotEmpty()) Text(h.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Close") } },
    )
}

@Composable
private fun PasteDialog(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(ChromaPrefs.fxAutoEqCurve.value) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Graphic EQ") },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it; error = null }, label = { Text("GraphicEQ: 20 -0.3; 21 -0.3; …") }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp))
                TextButton(onClick = {
                    val clip = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                    val pasted = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
                    if (pasted.isNullOrBlank()) error = "Nothing to paste" else text = pasted
                }) { Text("Replace the text with what you copied") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (ChromaAudioFx.parseGraphicEq(text) == null) { error = "Not a GraphicEQ line"; return@TextButton }
                ChromaPrefs.fxAutoEqCurve.value = text.trim()
                ChromaPrefs.fxAutoEqName.value = "Your curve"
                ChromaPrefs.fxGraphicEqOn.value = true
                ChromaAudioFx.apply()
                onDone()
            }) { Text("Use the text above") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}
