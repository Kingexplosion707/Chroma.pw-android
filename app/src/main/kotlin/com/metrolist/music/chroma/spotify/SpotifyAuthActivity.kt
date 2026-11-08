package com.metrolist.music.chroma.spotify

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.metrolist.music.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Receives chroma://callback after the Spotify login page and finishes the sign-in. */
class SpotifyAuthActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SpotifyBridge.init(this)
        val data = intent?.data
        if (data == null || data.scheme != "chroma" || data.host != "callback") { finish(); return }
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try {
                SpotifyApi.finishLogin(data)
                Toast.makeText(applicationContext, "Signed in to Spotify as ${SpotifyApi.userName.value}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(applicationContext, e.message ?: "Spotify sign-in failed", Toast.LENGTH_LONG).show()
            }
            startActivity(Intent(this@SpotifyAuthActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }
    }
}
