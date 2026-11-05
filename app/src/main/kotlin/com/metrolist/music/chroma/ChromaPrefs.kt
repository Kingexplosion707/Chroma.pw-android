package com.metrolist.music.chroma

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

/** Chroma's own settings, kept separate from Metrolist's DataStore. */
object ChromaPrefs {
    private var sp: SharedPreferences? = null
    val glassBar = mutableStateOf(true)
    val glassMiniPlayer = mutableStateOf(true)
    /** 0 = bass pulse, 1 = beat, 2 = beat + bass */
    val hapticMode = mutableIntStateOf(2)
    val hapticIntensity = mutableFloatStateOf(0.8f)
    /** 0 = left, 1 = center, 2 = right */
    val wallpaperAlign = mutableIntStateOf(0)

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.applicationContext.getSharedPreferences("chroma", Context.MODE_PRIVATE)
        sp = p
        glassBar.value = p.getBoolean("glassBar", true)
        glassMiniPlayer.value = p.getBoolean("glassMini", true)
        hapticMode.intValue = p.getInt("hMode", 2)
        hapticIntensity.floatValue = p.getFloat("hInt", 0.8f)
        wallpaperAlign.intValue = p.getInt("wpAlign", 0)
    }

    fun setGlassBar(v: Boolean) { glassBar.value = v; sp?.edit()?.putBoolean("glassBar", v)?.apply() }
    fun setGlassMiniPlayer(v: Boolean) { glassMiniPlayer.value = v; sp?.edit()?.putBoolean("glassMini", v)?.apply() }
    fun setHapticMode(v: Int) { hapticMode.intValue = v; sp?.edit()?.putInt("hMode", v)?.apply() }
    fun setHapticIntensity(v: Float) { hapticIntensity.floatValue = v; sp?.edit()?.putFloat("hInt", v)?.apply() }
    fun setWallpaperAlign(v: Int) { wallpaperAlign.intValue = v; sp?.edit()?.putInt("wpAlign", v)?.apply() }
}
