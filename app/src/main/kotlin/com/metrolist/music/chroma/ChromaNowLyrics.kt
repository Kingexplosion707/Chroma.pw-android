package com.metrolist.music.chroma

import com.metrolist.music.lyrics.LyricsEntry

/**
 * The lyrics Chroma found for what Spotify is playing, shared with the live wallpaper so it shows the
 * same word-timed lines as the player instead of asking the sources again.
 */
object ChromaNowLyrics {
    @Volatile private var key = ""
    @Volatile private var lyrics: Lyrics? = null

    fun forKey(k: String): Lyrics? = if (k == key) lyrics else null

    fun publish(k: String, entries: List<LyricsEntry>) {
        lyrics = toLyrics(entries)
        key = k
    }

    fun toLyrics(entries: List<LyricsEntry>): Lyrics? {
        val main = entries.filter { !it.isBackground && it.text.isNotBlank() }.sortedBy { it.time }
        if (main.isEmpty()) return null
        var timed = true
        val lines = main.mapIndexed { i, e ->
            val next = main.getOrNull(i + 1)?.time
            val ws = e.words.orEmpty().filter { it.text.isNotEmpty() }
            val lastWordEnd = ws.lastOrNull()?.let { (it.endTime * 1000).toLong() }
            val end = next ?: lastWordEnd ?: (e.time + 4000)
            val words = if (ws.isNotEmpty()) {
                ws.mapIndexed { j, w ->
                    val text = w.text + if (w.hasTrailingSpace && j < ws.lastIndex && !w.text.endsWith(" ")) " " else ""
                    Word(text, (w.startTime * 1000).toLong(), (w.endTime * 1000).toLong().coerceAtLeast((w.startTime * 1000).toLong() + 1))
                }
            } else {
                timed = false
                val tokens = e.text.trim().split(Regex("\\s+"))
                val span = (end - e.time).coerceIn(500L, 8000L)
                val total = tokens.sumOf { it.length + 1 }.coerceAtLeast(1)
                var t = e.time
                tokens.mapIndexed { j, tok ->
                    val d = span * (tok.length + 1) / total
                    Word(if (j < tokens.lastIndex) "$tok " else tok, t, t + d).also { t += d }
                }
            }
            LyricLine(e.time, end, words, estimated = ws.isEmpty())
        }
        return Lyrics(lines = lines, wordTimed = timed)
    }
}
