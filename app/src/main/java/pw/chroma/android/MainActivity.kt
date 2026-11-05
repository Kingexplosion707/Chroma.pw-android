package pw.chroma.android

import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pw.chroma.android.ui.AppShell
import pw.chroma.android.ui.C
import pw.chroma.android.ui.ChromaTheme
import pw.chroma.android.ui.HomeCache
import kotlin.math.sqrt

class MainActivity : ComponentActivity(), SensorEventListener {
    private var lastShake = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ChromaTheme { Root() } }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        val d = i?.data ?: return
        if (d.scheme != "chroma" || d.host != "callback") return
        lifecycleScope.launch {
            try { Api.finishLogin(d); HomeCache.load(force = true) } catch (e: Exception) { Playback.toast(e.message) }
        }
    }

    override fun onResume() {
        super.onResume()
        Remote.start(applicationContext)
        Playback.startPolling()
        if (Settings.shakeRandom) {
            val sm = getSystemService(SENSOR_SERVICE) as SensorManager
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        }
    }

    override fun onPause() {
        super.onPause()
        Playback.stopPolling()
        (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(this)
    }

    // Shake for random: plays a random liked song.
    override fun onSensorChanged(e: SensorEvent) {
        val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / SensorManager.GRAVITY_EARTH
        val now = System.currentTimeMillis()
        if (g > 2.6f && now - lastShake > 2500) {
            lastShake = now
            Playback.launch {
                val liked = Api.liked(200)
                liked.randomOrNull()?.let { Api.play(uris = listOf(it.uri)) }
            }
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}

@Composable
private fun Root() {
    val ctx = LocalContext.current
    var access by remember { mutableStateOf(Remote.hasAccess(ctx)) }
    LaunchedEffect(Unit) { while (true) { access = Remote.hasAccess(ctx); if (access) Remote.start(ctx); delay(1500) } }
    Surface(Modifier.fillMaxSize(), color = C.bg) {
        if (!access) AccessScreen() else AppShell()
    }
}

@Composable
private fun AccessScreen() {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().padding(32.dp).systemBarsPadding(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.GraphicEq, null, Modifier.size(72.dp), tint = C.accent)
        Spacer(Modifier.height(16.dp))
        Text("Chroma", fontSize = 36.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(12.dp))
        Text("Chroma is a client for the Spotify app on this phone. Allow notification access so it can see and control what Spotify is playing. It only reads Spotify's media session.",
            textAlign = TextAlign.Center, color = Color.White.copy(alpha = .7f))
        Spacer(Modifier.height(24.dp))
        Button(onClick = { ctx.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            colors = ButtonDefaults.buttonColors(containerColor = C.accent, contentColor = Color.Black), shape = RoundedCornerShape(50)) {
            Text("Grant notification access", fontWeight = FontWeight.Bold)
        }
        if (!Remote.spotifyInstalled(ctx)) { Spacer(Modifier.height(16.dp)); Text("Spotify doesn't appear to be installed.", color = Color(0xFFFF8A80)) }
    }
}
