package com.example.spotifylockplayer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.audiofx.AudioEffect
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.palette.graphics.Palette
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.concurrent.thread

class LockScreenActivity : AppCompatActivity() {

    private var spotifyController: MediaController? = null

    private lateinit var rootLayout: LinearLayout
    private lateinit var albumArt: ImageView
    private lateinit var lyricsScrollView: ScrollView
    private lateinit var lyricsText: TextView
    private lateinit var titleText: TextView
    private lateinit var artistText: TextView
    private lateinit var albumText: TextView
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var playPauseBtn: ImageButton
    private lateinit var btnLyrics: ImageButton
    private lateinit var btnRepeat: ImageButton
    private lateinit var btnShuffle: ImageButton

    private val handler = Handler(Looper.getMainLooper())
    private var isUserSeeking = false
    private var isLyricsVisible = false
    private var lastFetchedTrack = ""

    private val updateSeekBarRunnable = object : Runnable {
        override fun run() {
            spotifyController?.playbackState?.let { state ->
                if (!isUserSeeking) {
                    var position = state.position
                    if (state.state == PlaybackState.STATE_PLAYING) {
                        val timeDiff = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
                        position += (timeDiff * state.playbackSpeed).toLong()
                    }
                    seekBar.progress = position.toInt()
                    currentTimeText.text = formatTime(position)
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        setContentView(R.layout.activity_lock_screen)
        checkPermissions()

        rootLayout = findViewById(R.id.rootLayout)
        albumArt = findViewById(R.id.albumArt)
        lyricsScrollView = findViewById(R.id.lyricsScrollView)
        lyricsText = findViewById(R.id.lyricsText)
        titleText = findViewById(R.id.titleText)
        artistText = findViewById(R.id.artistText)
        albumText = findViewById(R.id.albumText)
        currentTimeText = findViewById(R.id.currentTimeText)
        totalTimeText = findViewById(R.id.totalTimeText)
        seekBar = findViewById(R.id.seekBar)
        playPauseBtn = findViewById(R.id.playPauseBtn)
        btnLyrics = findViewById(R.id.btnLyrics)
        btnRepeat = findViewById(R.id.btnRepeat)
        btnShuffle = findViewById(R.id.btnShuffle)

        titleText.isSelected = true
        artistText.isSelected = true
        albumText.isSelected = true

        // 1. 歌詞ボタン：ジャケット画像の上に歌詞オーバーレイを表示/非表示
        btnLyrics.setOnClickListener {
            isLyricsVisible = !isLyricsVisible
            if (isLyricsVisible) {
                lyricsScrollView.visibility = View.VISIBLE
                btnLyrics.setColorFilter(Color.parseColor("#1DB954")) // ON時は緑色にハイライト
            } else {
                lyricsScrollView.visibility = View.GONE
                btnLyrics.setColorFilter(Color.parseColor("#E0E0E0"))
            }
        }

        // 2. リピート切り替えボタン（Spotifyのカスタムアクションを呼び出す）
        btnRepeat.setOnClickListener {
            triggerSpotifyCustomAction("REPEAT")
        }

        // 3. シャッフル切り替えボタン（Spotifyのカスタムアクションを呼び出す）
        btnShuffle.setOnClickListener {
            triggerSpotifyCustomAction("SHUFFLE")
        }

        // メイン再生操作
        playPauseBtn.setOnClickListener {
            val state = spotifyController?.playbackState?.state
            if (state == PlaybackState.STATE_PLAYING) {
                spotifyController?.transportControls?.pause()
            } else {
                spotifyController?.transportControls?.play()
            }
        }
        findViewById<ImageButton>(R.id.prevBtn).setOnClickListener {
            spotifyController?.transportControls?.skipToPrevious()
        }
        findViewById<ImageButton>(R.id.nextBtn).setOnClickListener {
            spotifyController?.transportControls?.skipToNext()
        }

        // 音量・イコライザー・Spotify起動・閉じるボタン
        findViewById<ImageButton>(R.id.btnVolume).setOnClickListener {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
        }
        findViewById<ImageButton>(R.id.btnEq).setOnClickListener {
            try {
                val eqIntent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                eqIntent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, "com.spotify.music")
                startActivityForResult(eqIntent, 0)
            } catch (e: Exception) {}
        }
        findViewById<ImageButton>(R.id.btnOpenSpotify).setOnClickListener {
            packageManager.getLaunchIntentForPackage("com.spotify.music")?.let { startActivity(it) }
        }
        findViewById<ImageButton>(R.id.btnCloseLock).setOnClickListener {
            finish()
        }

        // シークバー操作
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) currentTimeText.text = formatTime(progress.toLong())
            }
            override fun onStartTrackingTouch(bar: SeekBar?) { isUserSeeking = true }
            override fun onStopTrackingTouch(bar: SeekBar?) {
                isUserSeeking = false
                bar?.let { spotifyController?.transportControls?.seekTo(it.progress.toLong()) }
            }
        })
    }

    // Spotifyが通知内に持っている「シャッフル」「リピート」の内部コマンドを探して実行する
    private fun triggerSpotifyCustomAction(keyword: String) {
        val actions = spotifyController?.playbackState?.customActions ?: return
        val targetAction = actions.find {
            it.action.uppercase().contains(keyword) || it.name.toString().uppercase().contains(keyword)
        }
        if (targetAction != null) {
            spotifyController?.transportControls?.sendCustomAction(targetAction.action, targetAction.extras)
            Toast.makeText(this, "${targetAction.name}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "現在の再生モードでは切り替えられません", Toast.LENGTH_SHORT).show()
        }
    }

    // 曲名とアーティスト名から歌詞を自動検索して取得する（LRCLIB API使用）
    private fun fetchLyrics(title: String, artist: String) {
        val trackKey = "$title-$artist"
        if (trackKey == lastFetchedTrack || title == "Unknown Title") return
        lastFetchedTrack = trackKey

        lyricsText.text = "「$title」の歌詞を検索中..."

        thread {
            try {
                // 検索精度を上げるため、アーティスト名に括弧（声優名など）が含まれる場合はメイン名義も抽出
                val cleanArtist = artist.substringBefore("(").substringBefore("（").trim()
                val query = URLEncoder.encode("$title $cleanArtist", "UTF-8")
                val url = URL("https://lrclib.net/api/search?q=$query")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                val response = conn.inputStream.bufferedReader().readText()
                val jsonArray = JSONArray(response)

                if (jsonArray.length() > 0) {
                    val firstHit = jsonArray.getJSONObject(0)
                    val plainLyrics = firstHit.optString("plainLyrics", "")
                    runOnUiThread {
                        lyricsText.text = if (plainLyrics.isNotBlank()) plainLyrics else "歌詞テキストが登録されていません。"
                        lyricsScrollView.scrollTo(0, 0)
                    }
                } else {
                    runOnUiThread {
                        lyricsText.text = "歌詞が見つかりませんでした。\n\n($title / $artist)"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    lyricsText.text = "歌詞の取得に失敗しました（通信エラー）"
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        connectToSpotify()
        handler.post(updateSeekBarRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateSeekBarRunnable)
        spotifyController?.unregisterCallback(mediaCallback)
    }

    private fun connectToSpotify() {
        try {
            val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(this, MediaNotificationListener::class.java)
            val controllers = manager.getActiveSessions(component)

            spotifyController = controllers.find { it.packageName == "com.spotify.music" }
            spotifyController?.let { controller ->
                updateUI(controller.metadata, controller.playbackState)
                controller.registerCallback(mediaCallback)
            }
        } catch (e: SecurityException) {}
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateUI(metadata, spotifyController?.playbackState)
        }
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updateUI(spotifyController?.metadata, state)
        }
    }

    private fun updateUI(metadata: MediaMetadata?, state: PlaybackState?) {
        metadata?.let {
            val title = it.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown Title"
            val artist = it.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown Artist"
            val album = it.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""

            titleText.text = title
            artistText.text = artist
            albumText.text = album

            // 曲が変わったら自動で裏で歌詞を取得しておく
            fetchLyrics(title, artist)

            val duration = it.getLong(MediaMetadata.METADATA_KEY_DURATION)
            if (duration > 0) {
                seekBar.max = duration.toInt()
                totalTimeText.text = formatTime(duration)
            }

            val bitmap = it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
            if (bitmap != null) {
                albumArt.setImageBitmap(bitmap)
                applyDynamicBackground(bitmap)
            }
        }

        state?.let {
            val isPlaying = it.state == PlaybackState.STATE_PLAYING
            playPauseBtn.setImageResource(
                if (isPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )
        }
    }

    private fun applyDynamicBackground(bitmap: Bitmap) {
        Palette.from(bitmap).generate { palette ->
            val defaultColor = Color.parseColor("#3E2723")
            val dominantColor = palette?.getMutedColor(defaultColor)
                ?: palette?.getDominantColor(defaultColor)
                ?: defaultColor
            val darkColor = palette?.getDarkMutedColor(Color.parseColor("#1B1210"))
                ?: Color.parseColor("#1B1210")

            val gradient = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(dominantColor, darkColor)
            )
            rootLayout.background = gradient
        }
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    private fun checkPermissions() {
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (enabledListeners == null || !enabledListeners.contains(packageName)) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }
}
