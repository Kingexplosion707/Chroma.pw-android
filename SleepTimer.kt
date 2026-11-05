package pw.chroma.android

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import androidx.compose.runtime.mutableLongStateOf
import kotlinx.coroutines.*

/** Pauses Spotify after N minutes, optionally fading the music volume out over the last 15 seconds. */
object SleepTimer {
    /** elapsedRealtime at which playback stops, or 0 when idle. */
    val endAt = mutableLongStateOf(0L)
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun start(ctx: Context, minutes: Int) {
        cancel()
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val original = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val end = SystemClock.elapsedRealtime() + minutes * 60_000L
        endAt.longValue = end
        job = scope.launch {
            val fadeMs = if (Settings.sleepFade) minOf(15_000L, minutes * 60_000L) else 0L
            delay(maxOf(0L, end - fadeMs - SystemClock.elapsedRealtime()))
            if (fadeMs > 0) {
                val steps = original.coerceAtLeast(1)
                for (i in steps - 1 downTo 0) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, i, 0)
                    delay(fadeMs / steps)
                }
            }
            Remote.pause()
            delay(600)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0)
            endAt.longValue = 0L
        }
    }

    fun cancel() { job?.cancel(); job = null; endAt.longValue = 0L }
}
