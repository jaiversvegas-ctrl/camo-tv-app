@file:Suppress("UnsafeOptInUsageError")

package com.camotv.app

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * Pemutar native (ExoPlayer). Memutar LINK ASLI langsung dari HP user:
 * tidak kena CORS, dan tidak lewat Worker/IP Cloudflare.
 */
class PlayerActivity : AppCompatActivity() {

    private var exo: ExoPlayer? = null
    private var url = ""
    private var triedProgressive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        url = intent.getStringExtra("url").orEmpty()
        if (url.isEmpty()) { finish(); return }

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)   // http -> https dan sebaliknya
            .setUserAgent(UA)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
        exo = player

        val view = PlayerView(this)
        view.setBackgroundColor(Color.BLACK)
        view.player = player
        setContentView(view)

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val code = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                // Dianggap HLS tapi isinya bukan playlist (mis. stream .ts mentah): coba sekali sebagai file biasa
                if (!triedProgressive && code == null && error.errorCode in 3001..3004) {
                    triedProgressive = true
                    start(false)
                    return
                }
                val why = if (code != null) "server membalas $code" else error.errorCodeName
                Toast.makeText(this@PlayerActivity, "Gagal memutar: $why", Toast.LENGTH_LONG).show()
            }
        })

        // Link berakhiran .mp4/.ts/dll diputar sebagai file; selain itu dianggap HLS (m3u8 / m3u / tanpa ekstensi)
        val path = url.substringBefore('?').lowercase()
        val isFile = Regex("\\.(mp4|mkv|webm|mp3|aac|m4a|ts)$").containsMatchIn(path)
        start(!isFile)
    }

    private fun start(asHls: Boolean) {
        val item = MediaItem.Builder().setUri(url).apply {
            if (asHls) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        exo?.apply {
            setMediaItem(item)
            prepare()
            playWhenReady = true
        }
    }

    override fun onStop() {
        exo?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        exo?.release()
        exo = null
        super.onDestroy()
    }

    companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
