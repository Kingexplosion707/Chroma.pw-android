package pw.chroma.android

import android.service.notification.NotificationListenerService

/** Needed only so Android lets us read Spotify's active media session. */
class SpotifyListener : NotificationListenerService() {
    override fun onListenerConnected() {
        Remote.start(applicationContext)
    }
}
