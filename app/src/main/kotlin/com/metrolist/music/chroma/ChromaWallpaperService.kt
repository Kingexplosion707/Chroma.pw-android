package com.metrolist.music.chroma

import android.content.ComponentName
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.metrolist.music.playback.MusicService
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/**
 * Chroma lyrics live wallpaper for Metrolist: blurred cover background, big centred cover,
 * title and artist, then word-by-word lyrics. Reads playback from Metrolist's own media session.
 */
class ChromaWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = ChromaEngine()

    inner class ChromaEngine : Engine() {
        private val ui = Handler(Looper.getMainLooper())
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private var controller: MediaController? = null
        private var w = 0
        private var h = 0
        private var visible = false

        private var trackKey = ""
        private var title = ""
        private var artist = ""
        private var art: Bitmap? = null
        private var bg: Bitmap? = null
        private var lyrics: Lyrics? = null

        private val layouts = HashMap<Int, StaticLayout>()
        private var layoutW = 0
        private var scrollY = 0f
        private val blurFilters = Array(7) { if (it == 0) null else BlurMaskFilter(it * 2f, BlurMaskFilter.Blur.NORMAL) }
        private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        private val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); textAlign = Paint.Align.CENTER }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            ChromaPrefs.init(applicationContext)
            val token = SessionToken(applicationContext, ComponentName(applicationContext, MusicService::class.java))
            val future = MediaController.Builder(applicationContext, token).buildAsync()
            future.addListener({ controller = runCatching { future.get() }.getOrNull(); draw() }, ContextCompat.getMainExecutor(applicationContext))
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            w = width; h = height; layouts.clear(); layoutW = 0
            art?.let { bg = blurred(it) }
            draw()
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            if (v) ui.post(tick) else ui.removeCallbacks(tick)
        }

        override fun onDestroy() {
            ui.removeCallbacks(tick); scope.cancel()
            controller?.release(); controller = null
            super.onDestroy()
        }

        private val tick = object : Runnable {
            override fun run() { if (visible) { poll(); draw(); ui.postDelayed(this, 33) } }
        }

        private fun poll() {
            val c = controller ?: return
            val m = c.mediaMetadata
            val t = m.title?.toString().orEmpty()
            val a = m.artist?.toString().orEmpty()
            val key = "$t|$a"
            if (key == trackKey) return
            trackKey = key; title = t; artist = a
            lyrics = null; layouts.clear(); scrollY = 0f; art = null; bg = null
            if (t.isEmpty()) return
            val dur = c.duration.takeIf { it > 0 } ?: 0L
            val album = m.albumTitle?.toString().orEmpty()
            val artUri = m.artworkUri?.toString()
            val artData = m.artworkData
            scope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    runCatching {
                        if (artData != null) BitmapFactory.decodeByteArray(artData, 0, artData.size)
                        else if (artUri != null && artUri.startsWith("http")) {
                            val conn = URL(artUri).openConnection() as HttpURLConnection
                            conn.connectTimeout = 8000; conn.readTimeout = 8000
                            try { BitmapFactory.decodeStream(conn.inputStream) } finally { conn.disconnect() }
                        } else null
                    }.getOrNull()
                }
                if (trackKey == key) { art = bmp; bg = bmp?.let { blurred(it) } }
            }
            scope.launch {
                val l = LyricsRepo.load(t, a, album, dur)
                if (trackKey == key) { lyrics = l; layouts.clear() }
            }
        }

        private fun position(): Long = controller?.currentPosition ?: 0L

        // ---------- blur (≈6px on screen) ----------
        private fun blurred(src: Bitmap): Bitmap = boxBlur(Bitmap.createScaledBitmap(src, 120, 120, true), 2)

        private fun boxBlur(b: Bitmap, r: Int): Bitmap {
            val bw = b.width; val bh = b.height
            val a = IntArray(bw * bh).also { b.getPixels(it, 0, bw, 0, 0, bw, bh) }
            val t = IntArray(bw * bh)
            repeat(3) { pass(a, t, bw, bh, r, true); pass(t, a, bw, bh, r, false) }
            return Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also { it.setPixels(a, 0, bw, 0, 0, bw, bh) }
        }

        private fun pass(src: IntArray, dst: IntArray, bw: Int, bh: Int, r: Int, horiz: Boolean) {
            val outer = if (horiz) bh else bw; val inner = if (horiz) bw else bh
            for (o in 0 until outer) for (i in 0 until inner) {
                var sr = 0; var sg = 0; var sb = 0; var n = 0
                for (k in -r..r) {
                    val j = (i + k).coerceIn(0, inner - 1)
                    val px = if (horiz) src[o * bw + j] else src[j * bw + o]
                    sr += (px shr 16) and 255; sg += (px shr 8) and 255; sb += px and 255; n++
                }
                val v = (0xFF shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
                if (horiz) dst[o * bw + i] = v else dst[i * bw + o] = v
            }
        }

        // ---------- drawing ----------
        private fun draw() {
            if (w == 0) return
            val c = try { surfaceHolder.lockCanvas() } catch (_: Exception) { null } ?: return
            try { render(c) } finally { try { surfaceHolder.unlockCanvasAndPost(c) } catch (_: Exception) {} }
        }

        private fun render(c: Canvas) {
            c.drawColor(Color.BLACK)
            bg?.let { b ->
                val s = maxOf(w / b.width.toFloat(), h / b.height.toFloat())
                val dw = b.width * s; val dh = b.height * s
                c.drawBitmap(b, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), p)
            }
            c.drawColor(0x66000000)
            if (title.isEmpty()) return
            val pad = w * 0.08f
            val cs = w * 0.66f
            val cl = (w - cs) / 2; val ct = h * 0.09f
            art?.let { a ->
                val rect = RectF(cl, ct, cl + cs, ct + cs); val rad = cs * 0.045f
                p.setShadowLayer(40f, 0f, 16f, 0x99000000.toInt()); p.color = Color.BLACK
                c.drawRoundRect(rect, rad, rad, p); p.clearShadowLayer()
                c.save(); c.clipPath(Path().apply { addRoundRect(rect, rad, rad, Path.Direction.CW) }); c.drawBitmap(a, null, rect, p); c.restore()
            }
            titlePaint.textSize = w * 0.062f; artistPaint.textSize = w * 0.04f
            var y = ct + cs + w * 0.085f
            val maxW = w - pad * 2
            c.drawText(TextUtils.ellipsize(title, titlePaint, maxW, TextUtils.TruncateAt.END).toString(), w / 2f, y, titlePaint)
            y += w * 0.06f
            c.drawText(TextUtils.ellipsize(artist, artistPaint, maxW, TextUtils.TruncateAt.END).toString(), w / 2f, y, artistPaint)
            drawLyrics(c, pad, y + w * 0.07f, w - pad, h - h * 0.07f)
        }

        private fun drawLyrics(c: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
            val lyr = lyrics ?: return
            val ly = lyr.lines
            if (ly.isEmpty()) return
            val width = (right - left).toInt()
            if (layoutW != width) { layouts.clear(); layoutW = width }
            val align = when (ChromaPrefs.wallpaperAlign.intValue) { 1 -> Layout.Alignment.ALIGN_CENTER; 2 -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL }
            val pos = position()
            val idx = lyr.indexAt(pos).coerceAtLeast(0)
            val gap = w * 0.035f

            fun textOf(i: Int) = ly[i].words.joinToString("") { it.text }.trimEnd().ifBlank { "• • •" }
            fun layoutFor(i: Int): StaticLayout = layouts.getOrPut(i) {
                val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = w * 0.072f }
                val t = textOf(i)
                StaticLayout.Builder.obtain(t, 0, t.length, tp, width).setAlignment(align).setLineSpacing(0f, 1.05f).build()
            }
            var offset = 0f
            for (i in 0 until idx) offset += layoutFor(i).height + gap
            scrollY += (offset - scrollY) * 0.12f

            c.save(); c.clipRect(left, top, right, bottom)
            var yy = top - scrollY
            for (i in ly.indices) {
                val l = layoutFor(i)
                if (yy > bottom) break
                if (yy + l.height >= top) {
                    val d = abs(i - idx)
                    val tp = l.paint
                    c.save(); c.translate(left, yy)
                    if (d == 0) {
                        tp.maskFilter = null; tp.alpha = 95; l.draw(c)
                        val sung = sungChars(ly[i], pos)
                        tp.alpha = 255; tp.setShadowLayer(w * 0.02f, 0f, 0f, 0xB3FFFFFF.toInt())
                        for (k in 0 until l.lineCount) {
                            val s0 = l.getLineStart(k); val e0 = l.getLineEnd(k)
                            if (sung <= s0) break
                            val x = if (sung >= e0) l.getLineRight(k) else {
                                val ci = sung.toInt().coerceIn(s0, e0 - 1)
                                val x0 = l.getPrimaryHorizontal(ci)
                                val x1 = if (ci + 1 < e0) l.getPrimaryHorizontal(ci + 1) else l.getLineRight(k)
                                x0 + (x1 - x0) * (sung - ci)
                            }
                            c.save(); c.clipRect(l.getLineLeft(k), l.getLineTop(k).toFloat(), x, l.getLineBottom(k).toFloat()); l.draw(c); c.restore()
                        }
                        tp.clearShadowLayer()
                    } else {
                        tp.maskFilter = blurFilters[minOf(d, 6)]
                        tp.alpha = if (i < idx) 90 else maxOf(70, 190 - d * 30)
                        l.draw(c)
                    }
                    c.restore()
                }
                yy += l.height + gap
            }
            c.restore()
        }

        private fun sungChars(line: LyricLine, pos: Long): Float {
            var n = 0f
            for (wd in line.words) {
                val pr = wd.progress(pos)
                if (pr <= 0f) break
                n += wd.text.length * pr
                if (pr < 1f) break
            }
            return n
        }

        @Suppress("unused") private val keep = Player.STATE_READY
    }
}
