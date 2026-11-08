package com.metrolist.music.chroma.player

import android.graphics.Matrix
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.PowerManager
import android.view.TextureView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.metrolist.music.chroma.ChromaPrefs
import kotlinx.coroutines.isActive

/**
 * Chroma's player background: the song's Spotify Canvas when it has one, otherwise fluid artwork
 * (a slowly warping, blurred take on the cover, like Kawarp), otherwise Metrolist's blur.
 * Turning either off in Chroma Settings falls through to the next.
 */
@Composable
fun ChromaBackdrop(trackId: String?, thumbnailUrl: String?, alpha: Float, fallback: @Composable () -> Unit) {
    val ctx = LocalContext.current
    var canvasUrl by remember(trackId) { mutableStateOf<String?>(null) }
    LaunchedEffect(trackId, ChromaPrefs.canvas.value, ChromaPrefs.spotifyMode.value) {
        canvasUrl = null
        if (!ChromaPrefs.canvas.value || !ChromaPrefs.spotifyMode.value) return@LaunchedEffect
        val id = trackId?.takeIf { it.length == 22 } ?: return@LaunchedEffect
        canvasUrl = CanvasProvider.find("spotify:track:$id")
    }
    val power = ctx.getSystemService(PowerManager::class.java)
    val lowPower = ChromaPrefs.lowPowerPausesAnimations.value && power?.isPowerSaveMode == true
    val url = canvasUrl
    when {
        url != null -> Box(Modifier.fillMaxSize().alpha(alpha)) {
            CanvasVideo(url, paused = lowPower)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        }
        ChromaPrefs.fluidArtwork.value && Build.VERSION.SDK_INT >= 33 && thumbnailUrl != null -> Box(Modifier.fillMaxSize().alpha(alpha)) {
            AnimatedContent(targetState = thumbnailUrl, transitionSpec = { fadeIn(tween(900)).togetherWith(fadeOut(tween(900))) }, label = "fluid") { u ->
                FluidArtwork(u, paused = lowPower)
            }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.22f)))
        }
        else -> fallback()
    }
}

/** A looping, muted Canvas clip that fills the screen (centre-cropped). */
@Composable
fun CanvasVideo(url: String, paused: Boolean = false, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(ctx).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(url))
            prepare()
        }
    }
    LaunchedEffect(player, paused) { player.playWhenReady = !paused }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { c ->
            TextureView(c).also { tv ->
                player.setVideoTextureView(tv)
                player.addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize.width == 0 || videoSize.height == 0) return
                        tv.post {
                            val vw = tv.width.toFloat(); val vh = tv.height.toFloat()
                            if (vw == 0f || vh == 0f) return@post
                            val videoRatio = videoSize.width.toFloat() / videoSize.height
                            val viewRatio = vw / vh
                            val m = Matrix()
                            if (videoRatio > viewRatio) m.setScale(videoRatio / viewRatio, 1f, vw / 2, vh / 2)
                            else m.setScale(1f, viewRatio / videoRatio, vw / 2, vh / 2)
                            tv.setTransform(m)
                        }
                    }
                })
            }
        },
        modifier = modifier.fillMaxSize(),
    )
}

private const val FLUID_AGSL = """
uniform shader image;
uniform float2 size;
uniform float time;

float2 rot(float2 p, float a) {
    float c = cos(a);
    float s = sin(a);
    return float2(p.x * c - p.y * s, p.x * s + p.y * c);
}

half4 main(float2 fc) {
    float2 uv = fc / size;
    float t = time * 0.07;
    float2 c = rot(uv - 0.5, t * 0.8 + 0.6 * sin(t * 0.5));
    float2 p = c + 0.5;
    p.x += 0.10 * sin(p.y * 3.1 + t * 2.3) + 0.06 * sin(p.y * 6.7 - t * 1.6);
    p.y += 0.10 * cos(p.x * 2.7 - t * 1.9) + 0.06 * cos(p.x * 5.3 + t * 1.2);
    float zoom = 0.82 + 0.06 * sin(t * 0.9);
    p = (p - 0.5) * zoom + 0.5;
    p = clamp(p, 0.001, 0.999);
    half4 a = image.eval(p * size);
    half4 b = image.eval(clamp(float2(1.0) - p.yx, 0.001, 0.999) * size);
    half4 col = mix(a, b, 0.35);
    half l = dot(col.rgb, half3(0.299, 0.587, 0.114));
    col.rgb = mix(half3(l), col.rgb, 1.25) * 0.8;
    return half4(col.rgb, 1.0);
}
"""

/** Fluid artwork: the cover warped and blurred by a shader, drifting slowly (Android 13+). */
@Composable
fun FluidArtwork(url: String, paused: Boolean = false, modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT < 33) return
    val ctx = LocalContext.current
    // A device whose shader compiler refuses the effect gets the plain blurred cover instead of a crash.
    val shader = remember { runCatching { RuntimeShader(FLUID_AGSL) }.getOrNull() }
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(paused) {
        if (paused) return@LaunchedEffect
        val start = withFrameNanos { it } - (time * 1e9f).toLong()
        while (isActive) withFrameNanos { time = (it - start) / 1e9f }
    }
    Box(
        modifier.fillMaxSize().graphicsLayer {
            val blur = RenderEffect.createBlurEffect(56f, 56f, Shader.TileMode.CLAMP)
            renderEffect = if (shader == null) blur.asComposeRenderEffect() else {
                shader.setFloatUniform("size", size.width.coerceAtLeast(1f), size.height.coerceAtLeast(1f))
                shader.setFloatUniform("time", time)
                RenderEffect.createChainEffect(blur, RenderEffect.createRuntimeShaderEffect(shader, "image")).asComposeRenderEffect()
            }
        },
    ) {
        AsyncImage(
            model = ImageRequest.Builder(ctx).data(url).size(256, 256).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
