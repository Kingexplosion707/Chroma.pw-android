package com.metrolist.music.chroma

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import androidx.compose.runtime.mutableStateOf
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Background music haptics: captures playback audio (AudioPlaybackCapture via MediaProjection),
 * detects bass energy and beats, and drives the vibration motor.
 */
class HapticService : Service() {
    companion object {
        const val ACTION_STOP = "com.metrolist.music.chroma.STOP_HAPTICS"
        val running = mutableStateOf(false)
        private const val CHANNEL = "chroma_haptics"
    }

    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    @Volatile private var alive = false
    private var worker: Thread? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (alive) return START_NOT_STICKY
        ChromaPrefs.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Music haptics", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, HapticService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Chroma music haptics")
            .setContentText("Vibrating to the music")
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true).build()
        startForeground(7417, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION") val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(code, data)
        mp.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, Handler(Looper.getMainLooper()))
        projection = mp
        try {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            record = AudioRecord.Builder().setAudioPlaybackCaptureConfig(cfg).setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(min, 8192) * 2).build()
        } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        alive = true; running.value = true
        worker = Thread(::loop, "chroma-haptics").also { it.start() }
        return START_NOT_STICKY
    }

    private val RATE = 44100

    private fun loop() {
        val rec = record ?: return
        val vib = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        val buf = ShortArray(1024)          // 512 stereo frames ≈ 11.6 ms
        val alpha = 1f - exp(-2f * Math.PI.toFloat() * 150f / RATE) // ~150 Hz low-pass
        var lp = 0f
        var avg = 0.02f
        var prev = 0f
        var lastBeat = 0L
        var lastPulse = 0L
        rec.startRecording()
        while (alive) {
            val n = rec.read(buf, 0, buf.size)
            if (n <= 0) continue
            var e = 0f
            var i = 0
            while (i + 1 < n) {
                val m = (buf[i] + buf[i + 1]) / 65536f
                lp += alpha * (m - lp)
                e += lp * lp
                i += 2
            }
            val bass = sqrt(e / (n / 2))
            val now = SystemClock.elapsedRealtime()
            val k = ChromaPrefs.hapticIntensity.floatValue
            val mode = ChromaPrefs.hapticMode.intValue
            val beat = bass > avg * 1.45f + 0.004f && bass > prev && now - lastBeat > 170
            if (beat && mode != 0) {
                lastBeat = now; lastPulse = now
                val strength = ((bass / (avg + 1e-4f)) / 3f).coerceIn(0.4f, 1f)
                pulse(vib, attrs, 38, (255 * strength * k).toInt())
            } else if (mode != 1 && bass > avg * 1.05f && bass > 0.01f && now - lastPulse > 45) {
                lastPulse = now
                val level = (bass / (avg * 3f + 1e-4f)).coerceIn(0f, 1f)
                pulse(vib, attrs, 18, (200 * level * k).toInt())
            }
            avg = avg * 0.985f + bass * 0.015f
            prev = bass
        }
    }

    private fun pulse(v: Vibrator, a: AudioAttributes, ms: Long, amp: Int) {
        val amplitude = amp.coerceIn(1, 255)
        val fx = if (v.hasAmplitudeControl()) VibrationEffect.createOneShot(ms, amplitude)
                 else VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
        @Suppress("DEPRECATION") v.vibrate(fx, a)
    }

    override fun onDestroy() {
        alive = false; running.value = false
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        super.onDestroy()
    }
}
