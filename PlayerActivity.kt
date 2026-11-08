@file:Suppress("UnsafeOptInUsageError")

package com.camotv.app

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
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
 * Pemutar native (ExoPlayer). Memutar LINK ASLI langsung dari HP user.
 *
 * Header khusus (opsional, gaya Kodi, hanya dibaca di APK):
 *   https://situs.com/a.m3u8|Referer=https://situs.com/|User-Agent=Mozilla/5.0
 *   https://situs.com/a.m3u8|Referer=none
 *
 * Tanpa akhiran itu: Referer + Origin otomatis dari alamat stream-nya sendiri.
 * Kalau sumber membalas 401/403, otomatis dicoba sekali lagi TANPA Referer/Origin.
 */
class PlayerActivity : AppCompatActivity() {

    private var exo: ExoPlayer? = null
    private var http: DefaultHttpDataSource.Factory? = null

    private var streamUrl: String = ""
    private var referer: String? = null
    private var userAgent: String = UA
    private var useReferer: Boolean = true
    private var isFile: Boolean = false

    private var triedProgressive: Boolean = false
    private var triedNoReferer: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = intent.getStringExtra("url").orEmpty()
        if (raw.isEmpty()) {
            finish()
            return
        }
        parseLink(raw)
        if (streamUrl.isEmpty()) {
            finish()
            return
        }

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val factory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(userAgent)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
        http = factory
        applyHeaders()

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .build()
        exo = player

        val view = PlayerView(this)
        view.setBackgroundColor(Color.BLACK)
        view.player = player
        setContentView(view)

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val httpErr = findHttpError(error)
                val code: Int? = httpErr?.responseCode

                // Ditolak (401/403): coba sekali lagi tanpa Referer/Origin
                if ((code == 401 || code == 403) && useReferer && !triedNoReferer) {
                    triedNoReferer = true
                    useReferer = false
                    applyHeaders()
                    start(!isFile)
                    return
                }
                // Dianggap HLS tapi isinya bukan playlist: coba sekali sebagai file biasa
                if (!triedProgressive && code == null && error.errorCode in 3001..3004) {
                    triedProgressive = true
                    start(false)
                    return
                }

                val why: String = if (code != null) {
                    "server membalas $code"
                } else if (error.errorCode == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED) {
                    "link http diblokir (cleartext)"
                } else if (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED) {
                    "tidak bisa konek ke sumber"
                } else if (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) {
                    "sumber tidak merespons (timeout)"
                } else {
                    error.errorCodeName
                }
                Toast.makeText(this@PlayerActivity, "Gagal memutar: $why", Toast.LENGTH_LONG).show()
            }
        })

        start(!isFile)
    }

    /** Pisahkan link dari akhiran |Referer=...|User-Agent=... */
    private fun parseLink(raw: String) {
        val cut = raw.indexOf('|')
        if (cut >= 0) {
            streamUrl = raw.substring(0, cut).trim()
            val parts = raw.substring(cut + 1).split('|', '&')
            for (part in parts) {
                val i = part.indexOf('=')
                if (i <= 0) continue
                val k = part.substring(0, i).trim().lowercase()
                val v = Uri.decode(part.substring(i + 1).trim())
                if (k == "referer" || k == "referrer") {
                    if (v.equals("none", ignoreCase = true)) {
                        useReferer = false
                    } else if (v.isNotEmpty()) {
                        referer = v
                    }
                } else if (k == "user-agent" || k == "useragent") {
                    if (v.isNotEmpty()) userAgent = v
                }
            }
        } else {
            streamUrl = raw.trim()
        }

        if (referer == null) {
            val o = originOf(streamUrl)
            if (o != null) referer = "$o/"
        }

        // .mp4/.ts/dll diputar sebagai file; selain itu dianggap HLS
        val path = streamUrl.substringBefore('?').lowercase()
        isFile = Regex("\\.(mp4|mkv|webm|mp3|aac|m4a|ts)\$").containsMatchIn(path)
    }

    private fun originOf(u: String): String? {
        return try {
            val p = Uri.parse(u)
            val scheme = p.scheme
            val host = p.host
            if (scheme == null || host == null) {
                null
            } else {
                val port = if (p.port != -1) ":" + p.port else ""
                scheme + "://" + host + port
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun applyHeaders() {
        val h = HashMap<String, String>()
        val ref = referer
        if (useReferer && ref != null) {
            h["Referer"] = ref
            val o = originOf(ref)
            if (o != null) h["Origin"] = o
        }
        http?.setDefaultRequestProperties(h)
    }

    private fun findHttpError(t: Throwable?): HttpDataSource.InvalidResponseCodeException? {
        var c: Throwable? = t
        while (c != null) {
            if (c is HttpDataSource.InvalidResponseCodeException) return c
            c = c.cause
        }
        return null
    }

    private fun start(asHls: Boolean) {
        val b = MediaItem.Builder().setUri(streamUrl)
        if (asHls) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        val item = b.build()
        val p = exo ?: return
        p.setMediaItem(item)
        p.prepare()
        p.playWhenReady = true
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
