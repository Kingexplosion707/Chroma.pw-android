package pw.chroma.android

import android.app.Application

class ChromaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        Api.init(this)
        Playback.init(this)
    }
}
