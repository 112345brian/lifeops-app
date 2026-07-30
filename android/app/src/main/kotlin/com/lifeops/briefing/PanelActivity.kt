package com.lifeops.briefing

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Hosts the full LifeOps control panel -- the same server-rendered web UI
 * reachable from any browser -- inside an in-app WebView, instead of the
 * external-browser-tab Intent.ACTION_VIEW that OpenPanelAction used to fire.
 * Rebuilding the panel's settings/history/canvas-approval/spend screens as
 * native Compose UI would mean duplicating a REST API that doesn't exist yet
 * (the web UI currently IS the API+UI combined via server-rendered
 * Jinja+forms) -- an in-app WebView gets the "feels like one app" result
 * without any of that duplication. The panel has no external links
 * (confirmed against every template under lifeops/templates), so every
 * in-panel navigation can safely stay inside this WebView with no
 * external-browser fallback needed.
 */
class PanelActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_URL = "url"

        fun intent(context: Context, url: String): Intent =
            Intent(context, PanelActivity::class.java).putExtra(EXTRA_URL, url)
    }

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
        if (url == null) {
            finish()
            return
        }

        // The panel's WEB_TOKEN auth sets a cookie after the first ?token=
        // request (see lifeops/web.py) so subsequent in-panel navigation
        // stays authenticated -- the WebView's cookie jar must accept and
        // persist that the same way a normal browser tab already did.
        CookieManager.getInstance().setAcceptCookie(true)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webView
                if (wv != null && wv.canGoBack()) {
                    wv.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        setContent {
            var loading by remember { mutableStateOf(true) }
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView, finishedUrl: String) {
                                            loading = false
                                        }
                                    }
                                    webView = this
                                    loadUrl(url)
                                }
                            },
                        )
                        if (loading) {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
