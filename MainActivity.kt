package pw.chroma.android

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(applicationContext)
        Api.init(applicationContext)
        enableEdgeToEdge()
        setContent { ChromaApp() }
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
            try { Api.finishLogin(d) } catch (e: Exception) { Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show() }
        }
    }

    override fun onResume() {
        super.onResume()
        Remote.start(applicationContext)
    }
}
