package pw.chroma.android

import android.graphics.*
import android.media.AudioAttributes
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.SurfaceHolder
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/** Live wallpaper: blurred canvas video / cover background, big centred cover, title, Spicy-style lyrics. */
class ChromaWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = ChromaEngine()

    inner class ChromaEngine : Engine() {
        private val ui = Handler(Looper.getMainLooper())
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private val frameThread = HandlerThread("chroma-frames").apply { start() }
        private val frameHandler = Handler(frameThread.looper)

        private var w = 0
        private var h = 0
        private var visible = false
        private var np = NowPlaying()
        private var lyrics: Lyrics? = null
        private var trackKey = ""

        private var bgArt: Bitmap? = null          // blurred cover fallback
        @Volatile private var videoFrame: Bitmap? = null
        private var player: MediaPlayer? = null
        private var reader: ImageReader? = null
        private var videoJob: Job? = null

        private val layouts = HashMap<Int, StaticLayout>()
        private var layoutW = 0
        private var scrollY = 0f
        private val blurFilters = Array(7) { if (it == 0) null else BlurMaskFilter(it * 2f, BlurMaskFilter.Blur.NORMAL) }
        private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        private val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); textAlign = Paint.Align.CENTER }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            val app = applicationContext
            Settings.init(app)
            Remote.start(app)
            scope.launch { Remote.state.collect { onTrack(it) } }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            w = width; h = height; layouts.clear(); layoutW = 0
            bgArt = np.art?.let { blurred(it) }
            draw()
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            if (v) { try { player?.start() } catch (_: Exception) {}; ui.post(tick) }
            else { ui.removeCallbacks(tick); try { player?.pause() } catch (_: Exception) {} }
        }

        override fun onDestroy() {
            ui.removeCallbacks(tick); scope.cancel(); releaseVideo(); frameThread.quitSafely()
            super.onDestroy()
        }

        private val tick = object : Runnable {
            override fun run() { if (visible) { draw(); ui.postDelayed(this, 33) } }
        }

        // ---------- data ----------
        private fun onTrack(n: NowPlaying) {
            np = n
            val key = "${n.title}|${n.artist}"
            if (key == trackKey) return
            trackKey = key
            lyrics = null; layouts.clear(); scrollY = 0f
            bgArt = n.art?.let { if (w > 0) blurred(it) else null }
            releaseVideo()
            if (n.title.isEmpty()) return
            scope.launch { lyrics = LyricsRepo.load(n.title, n.artist, n.album, n.durationMs); layouts.clear() }
            if (Settings.wallpaperVideo) videoJob = scope.launch { findAndPlayCanvas(n) }
        }

        // ---------- canvas video ----------
        private suspend fun findAndPlayCanvas(n: NowPlaying) {
            if (n.mediaId.isEmpty()) return
            if (Settings.spDc.isNotBlank()) {
                CanvasProvider.find(n.mediaId)?.let { startVideo(it); return }
            }
            val tpl = Settings.canvasUrl.trim()
            if (tpl.isEmpty()) return
            val id = n.mediaId.substringAfterLast(':')
            val url = withContext(Dispatchers.IO) {
                try {
                    val u = tpl.replace("{uri}", n.mediaId).replace("{id}", id)
                    val c = URL(u).openConnection() as HttpURLConnection
                    c.connectTimeout = 8000; c.readTimeout = 8000
                    val type = c.contentType.orEmpty()
                    if (c.responseCode != 200) null
                    else if (type.startsWith("video")) { c.disconnect(); u }
                    else {
                        val o = JSONObject(c.inputStream.bufferedReader().readText())
                        listOf("canvas_url", "canvasUrl", "url").firstNotNullOfOrNull { o.optString(it).ifBlank { null } }
                    }
                } catch (_: Exception) { null }
            } ?: return
            startVideo(url)
        }

        private suspend fun startVideo(url: String) {
            val size = withContext(Dispatchers.IO) {
                try {
                    val r = MediaMetadataRetriever(); r.setDataSource(url, HashMap())
                    val vw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
                    val vh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
                    r.release(); vw to vh
                } catch (_: Exception) { 0 to 0 }
            }
            if (size.first <= 0) return
            try {
                val rd = ImageReader.newInstance(size.first, size.second, PixelFormat.RGBA_8888, 2)
                var n = 0
                rd.setOnImageAvailableListener({ r ->
                    val img = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
                    try {
                        if (n++ % 2 == 0) {
                            val pl = img.planes[0]
                            val full = Bitmap.createBitmap(pl.rowStride / pl.pixelStride, img.height, Bitmap.Config.ARGB_8888)
                            full.copyPixelsFromBuffer(pl.buffer)
                            val small = Bitmap.createScaledBitmap(full, img.width / 4, img.height / 4, true)
                            full.recycle()
                            videoFrame = boxBlur(small, 1)   // 1px at 1/4 scale ≈ 6px on screen
                        }
                    } finally { img.close() }
                }, frameHandler)
                reader = rd
                player = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                    setVolume(0f, 0f)
                    isLooping = true
                    setDataSource(url)
                    setSurface(rd.surface)
                    setOnPreparedListener { if (visible) start() }
                    setOnErrorListener { _, _, _ -> releaseVideo(); true }
                    prepareAsync()
                }
            } catch (_: Exception) { releaseVideo() }
        }

        private fun releaseVideo() {
            videoJob?.cancel()
            try { player?.release() } catch (_: Exception) {}
            try { reader?.close() } catch (_: Exception) {}
            player = null; reader = null; videoFrame = null
        }

        // ---------- blur ----------
        /** 6px-equivalent blur: shrink 3x, box-blur radius 2 (3 passes), draw scaled up. */
        private fun blurred(src: Bitmap): Bitmap {
            val small = Bitmap.createScaledBitmap(src, 120, 120, true)
            return boxBlur(small, 2)
        }

        private fun boxBlur(b: Bitmap, r: Int): Bitmap {
            val w = b.width; val hh = b.height
            var a = IntArray(w * hh).also { b.getPixels(it, 0, w, 0, 0, w, hh) }
            var t = IntArray(w * hh)
            repeat(3) {
                blurPass(a, t, w, hh, r, true); blurPass(t, a, w, hh, r, false)
            }
            val out = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888)
            out.setPixels(a, 0, w, 0, 0, w, hh)
            return out
        }

        private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horiz: Boolean) {
            val outer = if (horiz) h else w; val inner = if (horiz) w else h
            for (o in 0 until outer) for (i in 0 until inner) {
                var sr = 0; var sg = 0; var sb = 0; var cnt = 0
                for (k in -r..r) {
                    val j = (i + k).coerceIn(0, inner - 1)
                    val px = if (horiz) src[o * w + j] else src[j * w + o]
                    sr += (px shr 16) and 255; sg += (px shr 8) and 255; sb += px and 255; cnt++
                }
                val v = (0xFF shl 24) or ((sr / cnt) shl 16) or ((sg / cnt) shl 8) or (sb / cnt)
                if (horiz) dst[o * w + i] = v else dst[i * w + o] = v
            }
        }

        // ---------- drawing ----------
        private fun draw() {
            if (w == 0) return
            val holder = surfaceHolder
            val c = try { holder.lockCanvas() } catch (_: Exception) { null } ?: return
            try { render(c) } finally { try { holder.unlockCanvasAndPost(c) } catch (_: Exception) {} }
        }

        private fun render(c: Canvas) {
            c.drawColor(Color.BLACK)
            // background: canvas video frame, else blurred cover
            val bg = videoFrame ?: bgArt
            if (bg != null) {
                val scale = maxOf(w / bg.width.toFloat(), h / bg.height.toFloat())
                val dw = bg.width * scale; val dh = bg.height * scale
                c.drawBitmap(bg, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), p)
            } else c.drawColor(np.artColor ?: 0xFF1B1B1B.toInt())
            c.drawColor(0x66000000) // readability scrim

            val pad = w * 0.08f
            // cover - big and centred
            val cs = w * 0.66f
            val cl = (w - cs) / 2; val ct = h * 0.09f
            np.art?.let { art ->
                val path = Path().apply { addRoundRect(RectF(cl, ct, cl + cs, ct + cs), cs * 0.045f, cs * 0.045f, Path.Direction.CW) }
                // soft shadow
                p.setShadowLayer(40f, 0f, 16f, 0x99000000.toInt()); p.color = Color.BLACK
                c.drawRoundRect(RectF(cl, ct, cl + cs, ct + cs), cs * 0.045f, cs * 0.045f, p); p.clearShadowLayer()
                c.save(); c.clipPath(path); c.drawBitmap(art, null, RectF(cl, ct, cl + cs, ct + cs), p); c.restore()
            }
            // title + artist under cover
            titlePaint.textSize = w * 0.062f; artistPaint.textSize = w * 0.04f
            var y = ct + cs + w * 0.085f
            val maxW = w - pad * 2
            c.drawText(TextUtils.ellipsize(np.title, titlePaint, maxW, TextUtils.TruncateAt.END).toString(), w / 2f, y, titlePaint)
            y += w * 0.06f
            c.drawText(TextUtils.ellipsize(np.artist, artistPaint, maxW, TextUtils.TruncateAt.END).toString(), w / 2f, y, artistPaint)

            // lyrics below
            val top = y + w * 0.07f
            val bottom = h - h * 0.07f
            drawLyrics(c, pad, top, w - pad, bottom)
        }

        private fun drawLyrics(c: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
            val lyr = lyrics ?: return
            val ly = lyr.lines
            if (ly.isEmpty()) return
            val width = (right - left).toInt()
            if (layoutW != width) { layouts.clear(); layoutW = width }
            val align = when (Settings.lyricsAlign) { 1 -> Layout.Alignment.ALIGN_CENTER; 2 -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL }
            val pos = np.livePosition()
            val idx = lyr.indexAt(pos).coerceAtLeast(0)
            val gap = w * 0.035f

            fun textOf(i: Int) = ly[i].words.joinToString("") { it.text }.trimEnd().ifBlank { "\u2022 \u2022 \u2022" }
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
                        // word-by-word: dim base, then the sung part bright and glowing
                        tp.maskFilter = null; tp.alpha = 95; l.draw(c)
                        val sung = if (Settings.wordSync) sungChars(ly[i], pos) else l.text.length.toFloat()
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
                            c.save()
                            c.clipRect(l.getLineLeft(k), l.getLineTop(k).toFloat(), x, l.getLineBottom(k).toFloat())
                            l.draw(c)
                            c.restore()
                        }
                        tp.clearShadowLayer()
                    } else {
                        tp.maskFilter = blurFilters[minOf(d, 6)]
                        tp.alpha = when { i < idx -> 90; else -> maxOf(70, 190 - d * 30) }
                        l.draw(c)
                    }
                    c.restore()
                }
                yy += l.height + gap
            }
            c.restore()
        }

        /** Characters of [line] sung by [pos], including the partly sung word. */
        private fun sungChars(line: LyricLine, pos: Long): Float {
            var n = 0f
            for (wd in line.words) {
                val p = wd.progress(pos)
                if (p <= 0f) break
                n += wd.text.length * p
                if (p < 1f) break
            }
            return n
        }
    }
}
