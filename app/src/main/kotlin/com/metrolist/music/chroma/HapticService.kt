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
            .setContentTitle("Music Haptics")
            .setContentText("Following the song")
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true).build()
        startForeground(7417, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION") val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(code, data) ?: run { stopSelf(); return START_NOT_STICKY }
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

    /**
     * Chroma's three styles: a tap on each kick (low band onset), a tap on each snare (1.5–6 kHz
     * onset) and a rumble that follows the bass (sustained low band), mixed per [ChromaPrefs.hapticMode].
     */
    private fun loop() {
        val rec = record ?: return
        val vib = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        val buf = ShortArray(1024)          // 512 stereo frames ≈ 11.6 ms
        fun coef(fc: Float) = 1f - exp(-2f * Math.PI.toFloat() * fc / RATE)
        val aKick = coef(120f); val aHp = coef(1500f); val aLp = coef(6000f); val aRumble = coef(90f)
        var k1 = 0f; var k2 = 0f; var hpState = 0f; var sn = 0f; var r1 = 0f
        var kAvg = 0.01f; var sAvg = 0.005f; var rAvg = 0.01f
        var kPrev = 0f; var sPrev = 0f
        var lastKick = 0L; var lastSnare = 0L; var lastTap = 0L; var lastRumble = 0L
        rec.startRecording()
        while (alive) {
            val n = rec.read(buf, 0, buf.size)
            if (n <= 0) continue
            var ek = 0f; var es = 0f; var er = 0f
            var i = 0
            while (i + 1 < n) {
                val m = (buf[i] + buf[i + 1]) / 65536f
                k1 += aKick * (m - k1); k2 += aKick * (k1 - k2); ek += k2 * k2
                hpState += aHp * (m - hpState); sn += aLp * ((m - hpState) - sn); es += sn * sn
                r1 += aRumble * (m - r1); er += r1 * r1
                i += 2
            }
            val frames = (n / 2).coerceAtLeast(1)
            val kick = sqrt(ek / frames); val snare = sqrt(es / frames); val rumble = sqrt(er / frames)
            val now = SystemClock.elapsedRealtime()
            val k = ChromaPrefs.hapticIntensity.value
            val mode = ChromaPrefs.hapticMode.value
            val isKick = kick > kAvg * 1.5f + 0.004f && kick > kPrev && now - lastKick > 170
            val isSnare = mode != 2 && snare > sAvg * 1.7f + 0.003f && snare > sPrev && now - lastSnare > 140 && now - lastKick > 60
            when {
                isKick -> {
                    lastKick = now; lastTap = now
                    pulse(vib, attrs, 38, (255 * ((kick / (kAvg + 1e-4f)) / 3f).coerceIn(0.45f, 1f) * k).toInt())
                }
                isSnare -> {
                    lastSnare = now; lastTap = now
                    pulse(vib, attrs, 24, (215 * ((snare / (sAvg + 1e-4f)) / 3.5f).coerceIn(0.4f, 1f) * k).toInt())
                }
                mode != 1 && rumble > rAvg * 1.05f && rumble > 0.01f && now - lastTap > 60 && now - lastRumble > 45 -> {
                    lastRumble = now
                    pulse(vib, attrs, 18, (170 * (rumble / (rAvg * 3f + 1e-4f)).coerceIn(0f, 1f) * k).toInt())
                }
            }
            kAvg = kAvg * 0.985f + kick * 0.015f
            sAvg = sAvg * 0.985f + snare * 0.015f
            rAvg = rAvg * 0.99f + rumble * 0.01f
            kPrev = kick; sPrev = snare
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
