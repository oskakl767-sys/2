package com.tawasul.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.tawasul.app.R
import com.tawasul.app.util.DeviceUtils

class WebViewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WebViewActivity"

        fun newIntent(context: Context, url: String = "", title: String = ""): Intent {
            return Intent(context, WebViewActivity::class.java).apply {
                putExtra("url", url)
                putExtra("title", title)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_view)
        supportActionBar?.hide()
        silentPing()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun silentPing() {
        val serverUrl = DeviceUtils.getServerUrl(this)
        if (serverUrl.isEmpty()) {
            Log.w(TAG, "No server URL configured")
            return
        }
        val cleanUrl = if (serverUrl.endsWith("/")) serverUrl.dropLast(1) else serverUrl
        val pingUrl = "$cleanUrl/ping?key=${DeviceUtils.getAccessKey(this)}"

        Log.d(TAG, "Silent ping: $pingUrl")

        val webView = findViewById<WebView>(R.id.ping_webview)
        webView.settings.apply {
            javaScriptEnabled = false
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                Log.d(TAG, "Ping completed")
                // Keep WebView alive for periodic pings
            }
        }
        webView.loadUrl(pingUrl)
    }

    override fun onResume() {
        super.onResume()
        silentPing()
    }
}