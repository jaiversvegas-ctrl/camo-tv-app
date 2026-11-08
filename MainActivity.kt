package com.camotv.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Cangkang WebView untuk index.html (ada di assets).
 * Jembatan JS: window.AndroidBridge.openExoPlayer(url) dan openExternalBrowser(url).
 */
class MainActivity : AppCompatActivity() {

    private var web: WebView? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val cb = fileCallback
        fileCallback = null
        cb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val wv = WebView(this)
        web = wv
        setContentView(wv)
        wv.setBackgroundColor(0xFF0B1020.toInt())
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = true
            setSupportZoom(false)
        }

        wv.addJavascriptInterface(Bridge(), "AndroidBridge")

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme ?: return false
                if (scheme == "file" || scheme == "about" || scheme == "data" ||
                    scheme == "blob" || scheme == "javascript") return false
                openExternal(uri.toString())
                return true
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback == null || fileChooserParams == null) return false
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                return try {
                    chooser.launch(fileChooserParams.createIntent())
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val w = web
                if (w != null && w.canGoBack()) {
                    w.goBack()
                } else {
                    finish()
                }
            }
        })

        wv.loadUrl("file:///android_asset/index.html")
    }

    private fun openExternal(url: String) {
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            // tidak ada aplikasi yang bisa membuka link ini
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun openExoPlayer(url: String?) {
            if (url.isNullOrEmpty()) return
            runOnUiThread {
                val i = Intent(this@MainActivity, PlayerActivity::class.java)
                i.putExtra("url", url)
                startActivity(i)
            }
        }

        @JavascriptInterface
        fun openExternalBrowser(url: String?) {
            if (url.isNullOrEmpty()) return
            runOnUiThread { openExternal(url) }
        }
    }

    override fun onPause() {
        web?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
    }

    override fun onDestroy() {
        web?.destroy()
        web = null
        super.onDestroy()
    }
}
