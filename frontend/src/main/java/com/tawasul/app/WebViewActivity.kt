package com.tawasul.app

import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tawasul.app.util.DeviceUtils

/**
 * Simple WebView that loads the server URL from connection.json.
 * This activity is shown after accessibility is enabled and the
 * app has connected to the server.
 */
class WebViewActivity : AppCompatActivity() {

    private val TAG = "WebViewActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val webView = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                webViewClient = WebViewClient()
            }
            setContentView(webView)

            // Load server URL from connection.json
            val serverUrl = DeviceUtils.getServerUrl(this)
            val pingUrl = if (serverUrl.endsWith("/")) {
                serverUrl + "ping"
            } else {
                "$serverUrl/ping"
            }
            Log.d(TAG, "Loading URL: $pingUrl")
            webView.loadUrl(pingUrl)

        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
