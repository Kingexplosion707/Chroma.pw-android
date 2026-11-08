package com.metrolist.music.chroma.lyrics

import com.metrolist.music.lyrics.LyricsUtils

/** Lines up community translations with the lyrics Chroma shows, the way Metrolist stores them. */
object ChromaTranslations {
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")

    fun norm(s: String): String = s.lowercase().replace(nonWord, " ").trim()

    /**
     * One translation per non-blank lyric line, in order (blank where none matched), or null when
     * too few lines matched, which usually means the translation belongs to another version.
     */
    fun align(lyricsText: String, translations: Map<String, String>): List<String>? {
        if (translations.isEmpty()) return null
        val entries = runCatching { LyricsUtils.parseLyrics(lyricsText) }.getOrDefault(emptyList()).filter { it.text.isNotBlank() }
        if (entries.isEmpty()) return null
        var hits = 0
        val out = entries.map { e -> translations[norm(e.text)]?.also { hits++ }.orEmpty() }
        return out.takeIf { hits * 3 >= entries.size }
    }
}
