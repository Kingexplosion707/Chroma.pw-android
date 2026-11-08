package com.metrolist.music.chroma

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.JsonReader
import android.util.JsonToken
import com.metrolist.music.constants.HistoryDuration
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Event
import com.metrolist.music.db.entities.PlayCountEntity
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream
import kotlin.math.abs

/**
 * Imports Spotify's privacy export into the listening stats: the account data's
 * StreamingHistory*.json (names only) and the extended Streaming_History_Audio*.json / endsong*.json
 * (with Spotify's track ids), picked one by one or as the .zip Spotify sends.
 */
object SpotifyHistoryImport {
    private class Play(val trackId: String?, val title: String, val artist: String, val endUtc: Instant, val ms: Long)

    private val accountTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    suspend fun import(ctx: Context, db: MusicDatabase, uris: List<Uri>): String = withContext(Dispatchers.IO) {
        val minMs = ((ctx.dataStore[HistoryDuration] ?: 30f) * 1000f).toLong()
        val done = ChromaPrefs.importedHistory.value.split(',').filter { it.isNotBlank() }.toMutableSet()
        val plays = ArrayList<Play>()
        var skipped = 0
        var files = 0
        for (uri in uris) {
            val print = fingerprint(ctx, uri)
            if (print in done) { skipped++; continue }
            val before = plays.size
            runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { raw ->
                    val input = BufferedInputStream(raw)
                    input.mark(4)
                    val zip = input.read() == 'P'.code && input.read() == 'K'.code
                    input.reset()
                    if (zip) {
                        val z = ZipInputStream(input)
                        var e = z.nextEntry
                        while (e != null) {
                            val n = e.name.substringAfterLast('/')
                            if (!e.isDirectory && n.endsWith(".json", true) &&
                                (n.startsWith("StreamingHistory", true) || n.startsWith("Streaming_History_Audio", true) || n.startsWith("endsong", true))
                            ) parse(object : FilterInputStream(z) { override fun close() {} }, plays)
                            e = z.nextEntry
                        }
                    } else parse(input, plays)
                }
            }
            if (plays.size > before) { files++; done += print }
        }
        val counted = plays.filter { it.ms >= minMs && it.title.isNotBlank() }
        if (counted.isEmpty()) {
            return@withContext when {
                skipped > 0 && files == 0 -> "Those files were imported already."
                plays.isEmpty() -> "No plays found. Pick the StreamingHistory or Streaming_History_Audio .json files, or Spotify's .zip."
                else -> "No plays long enough to count."
            }
        }

        val zone = ZoneId.systemDefault()
        val songs = HashMap<String, Play>()
        val months = HashMap<Triple<String, Int, Int>, Int>()
        val time = HashMap<String, Long>()
        db.withTransaction {
            for (p in counted) {
                val id = p.trackId ?: ("chroma" + abs((p.title + "|" + p.artist).hashCode()).toString())
                if (songs.put(id, p) == null) {
                    insert(MediaMetadata(
                        id = id, title = p.title,
                        artists = p.artist.split(", ").filter { it.isNotBlank() }.map { MediaMetadata.Artist(null, it) }
                            .ifEmpty { listOf(MediaMetadata.Artist(null, p.artist)) },
                        duration = 0,
                    ))
                }
                val local = LocalDateTime.ofInstant(p.endUtc, zone)
                insert(Event(songId = id, timestamp = local, playTime = p.ms))
                val utc = p.endUtc.atOffset(ZoneOffset.UTC)
                months.merge(Triple(id, utc.year, utc.monthValue), 1, Int::plus)
                time.merge(id, p.ms, Long::plus)
            }
            months.forEach { (k, n) ->
                if (addToPlayCountRow(k.first, k.second, k.third, n) == 0) insertPlayCountRow(PlayCountEntity(k.first, k.second, k.third, n))
            }
            time.forEach { (id, ms) -> incrementTotalPlayTime(id, ms) }
        }
        ChromaPrefs.importedHistory.value = done.joinToString(",")
        "Imported ${counted.size} plays of ${songs.size} songs." + if (skipped > 0) " $skipped file(s) were already imported." else ""
    }

    private fun fingerprint(ctx: Context, uri: Uri): String = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) "${c.getString(0)}:${c.getLong(1)}".hashCode().toString() else null
        }
    }.getOrNull() ?: uri.toString().hashCode().toString()

    private fun parse(stream: InputStream, out: MutableList<Play>) {
        val r = JsonReader(InputStreamReader(stream, Charsets.UTF_8))
        if (r.peek() != JsonToken.BEGIN_ARRAY) return
        r.beginArray()
        while (r.hasNext()) {
            var title = ""; var artist = ""; var uri: String? = null
            var end: Instant? = null; var ms = 0L
            r.beginObject()
            while (r.hasNext()) {
                val name = r.nextName()
                if (r.peek() == JsonToken.NULL) { r.nextNull(); continue }
                when (name) {
                    "trackName", "master_metadata_track_name" -> title = r.nextString()
                    "artistName", "master_metadata_album_artist_name" -> artist = r.nextString()
                    "spotify_track_uri" -> uri = r.nextString()
                    "msPlayed", "ms_played" -> ms = r.nextLong()
                    "endTime" -> end = runCatching { LocalDateTime.parse(r.nextString(), accountTime).toInstant(ZoneOffset.UTC) }.getOrNull()
                    "ts" -> end = runCatching { Instant.parse(r.nextString()) }.getOrNull()
                    else -> r.skipValue()
                }
            }
            r.endObject()
            val e = end ?: continue
            out += Play(uri?.takeIf { it.startsWith("spotify:track:") }?.substringAfterLast(':'), title, artist, e, ms)
        }
        r.endArray()
    }
}
