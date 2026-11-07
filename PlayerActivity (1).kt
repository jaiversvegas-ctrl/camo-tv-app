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
 * Pemutar native (ExoPlayer). Memutar LINK ASLI langsung dari HP user:
 * tidak kena CORS, dan tidak lewat Worker/IP Cloudflare.
 *
 * Link boleh pakai akhiran gaya Kodi untuk header khusus (hanya dibaca di APK):
 *   https://situs.com/a.m3u8|Referer=https://situs.com/|User-Agent=Mozilla/5.0
 *   https://situs.com/a.m3u8|Referer=none      (kirim TANPA Referer/Origin)
 *
 * Tanpa akhiran itu: Referer + Origin otomatis diisi dari alamat stream-nya sendiri.
 * Kalau sumber membalas 401/403, otomatis dicoba sekali lagi TANPA Referer/Origin.
 */
class PlayerActivity : AppCompatActivity() {

    private var exo: ExoPlayer? = null
    private lateinit var http: DefaultHttpDataSource.Factory

    private var streamUrl = ""
    private var referer: String? = null
    private var userAgent = UA
    private var useReferer = true
    private var isFile = false

    private var triedProgressive = false
    private var triedNoReferer = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = intent.getStringExtra("url").orEmpty()
        if (raw.isEmpty()) { finish(); return }
        parseLink(raw)
        if (streamUrl.isEmpty()) { finish(); return }

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)   // http -> https dan sebaliknya
            .setUserAgent(userAgent)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
        applyHeaders()

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
                val code = findHttpError(error)?.responseCode

                // Ditolak (401/403): coba sekali lagi tanpa Referer/Origin
                if ((code == 401 || code == 403) && useReferer && !triedNoReferer) {
                    triedNoReferer = true
                    useReferer = false
                    applyHeaders()
                    start(!isFile)
                    return
                }
                // Dianggap HLS tapi isinya bukan playlist (mis. stream .ts mentah): coba sekali sebagai file biasa
                if (!triedProgressive && code == null && error.errorCode in 3001..3004) {
                    triedProgressive = true
                    start(false)
                    return
                }

                val why = when {
                    code != null -> "server membalas $code"
                    error.errorCode == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ->
                        "link http diblokir (cleartext)"
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                        "tidak bisa konek ke sumber"
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        "sumber tidak merespons (timeout)"
                    else -> error.errorCodeName
                }
                Toast.makeText(this@PlayerActivity, "Gagal memutar: $why", Toast.LENGTH_LONG).show()
            }
        })

        start(!isFile)
    }

    /** Pisahkan link dari akhiran |Referer=...|User-Agent=... */
    private fun parseLink(raw: String) {
        val cut = raw.indexOf('|')
        streamUrl = (if (cut >= 0) raw.substring(0, cut) else raw).trim()
        if (cut >= 0) {
            raw.substring(cut + 1).split('|', '&').forEach { part ->
                val i = part.indexOf('=')
                if (i > 0) {
                    val k = part.substring(0, i).trim().lowercase()
                    val v = Uri.decode(part.substring(i + 1).trim())
                    when (k) {
                        "referer", "referrer" ->
                            if (v.equals("none", ignoreCase = true)) useReferer = false
                            else if (v.isNotEmpty()) referer = v
                        "user-agent", "useragent" -> if (v.isNotEmpty()) userAgent = v
                    }
                }
            }
        }
        if (referer == null) referer = originOf(streamUrl)?.let { "$it/" }

        // Link berakhiran .mp4/.ts/dll diputar sebagai file; selain itu dianggap HLS (m3u8 / m3u / tanpa ekstensi)
        val path = streamUrl.substringBefore('?').lowercase()
        isFile = Regex("\\.(mp4|mkv|webm|mp3|aac|m4a|ts)$").containsMatchIn(path)
    }

    private fun originOf(u: String): String? = try {
        val p = Uri.parse(u)
        val scheme = p.scheme
        val host = p.host
        if (scheme == null || host == null) null
        else scheme + "://" + host + (if (p.port != -1) ":" + p.port else "")
    } catch (e: Exception) { null }

    private fun applyHeaders() {
        val h = HashMap<String, String>()
        val ref = referer
        if (useReferer && ref != null) {
            h["Referer"] = ref
            originOf(ref)?.let { h["Origin"] = it }
        }
        http.setDefaultRequestProperties(h)
    }

    private fun findHttpError(t: Throwable?): HttpDataSource.InvalidResponseCodeException? {
        var c = t
        while (c != null) {
            if (c is HttpDataSource.InvalidResponseCodeException) return c
            c = c.cause
        }
        return null
    }

    private fun start(asHls: Boolean) {
        val item = MediaItem.Builder().setUri(streamUrl).apply {
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
