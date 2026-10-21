package com.example.spotifylockplayer

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService

class MediaNotificationListener : NotificationListenerService() {

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON) {
                // 画面がオンになったとき、Spotifyが再生中（または一時停止中）ならロック画面を起動
                if (getSpotifyController() != null) {
                    val lockIntent = Intent(context, LockScreenActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    startActivity(lockIntent)
                }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // 画面オンを検知するレシーバーを登録
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
        registerReceiver(screenOnReceiver, filter)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        unregisterReceiver(screenOnReceiver)
    }

    private fun getSpotifyController(): MediaController? {
        val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(this, MediaNotificationListener::class.java)
        val controllers = manager.getActiveSessions(component)
        
        // Spotify（com.spotify.music）のセッションを探す
        return controllers.find { 
            it.packageName == "com.spotify.music" && 
            (it.playbackState?.state == PlaybackState.STATE_PLAYING || 
             it.playbackState?.state == PlaybackState.STATE_PAUSED)
        }
    }
}
