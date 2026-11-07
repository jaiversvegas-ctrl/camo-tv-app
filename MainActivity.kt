package com.camotv.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0B0F17")
        window.navigationBarColor = Color.parseColor("#0B0F17")

        // index.html dimuat dari assets lewat alamat https (bukan file://) supaya localStorage & fetch berfungsi normal
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0B0F17"))
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportMultipleWindows(false)
        }
        web.addJavascriptInterface(Bridge(), "AndroidBridge")

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

            // Semua link keluar (Telegram, iklan, window.open) dibuka di browser/app lain, bukan di dalam WebView
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == HOST) return false
                openExternal(request.url.toString())
                return true
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        web.loadUrl("https://$HOST/assets/index.html")
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private fun isHttp(u: String) = u.startsWith("http://", true) || u.startsWith("https://", true)

    private fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            // tidak ada aplikasi yang bisa membuka link ini
        }
    }

    /** Dipanggil dari JavaScript di index.html: window.AndroidBridge.xxx() */
    inner class Bridge {
        @JavascriptInterface
        fun openExoPlayer(url: String?) {
            val u = url?.trim().orEmpty()
            if (!isHttp(u)) return
            runOnUiThread {
                startActivity(Intent(this@MainActivity, PlayerActivity::class.java).putExtra("url", u))
            }
        }

        @JavascriptInterface
        fun openExternalBrowser(url: String?) {
            val u = url?.trim().orEmpty()
            if (!isHttp(u)) return
            runOnUiThread { openExternal(u) }
        }
    }

    companion object {
        const val HOST = "appassets.androidplatform.net"
    }
}
