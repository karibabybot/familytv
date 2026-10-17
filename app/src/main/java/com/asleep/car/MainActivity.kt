package com.asleep.car

import android.app.Activity
import android.graphics.Color
import android.net.http.SslError
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Opens your Rainy Night Drive site full screen in TV mode and starts the rain.
 * Change SITE if your web address ever changes.
 */
class MainActivity : Activity() {

    private val SITE = "https://adorable-griffin-e7813f.netlify.app/?tv=1&auto=1&v=2"
    private lateinit var web: WebView
    private var failed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#070B11"))
        setContentView(web)
        hideBars()

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        web.webViewClient = object : WebViewClient() {
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                if (r.isForMainFrame) showError()
            }
            override fun onReceivedSslError(v: WebView, h: android.webkit.SslErrorHandler, e: SslError) {
                h.cancel(); showError()
            }
        }
        web.loadUrl(SITE)
    }

    private fun showError() {
        failed = true
        web.loadData(
            "<body style='background:#070b11;color:#dbe6ee;font:28px sans-serif;display:grid;place-items:center;height:100vh;margin:0;text-align:center'>" +
                "<div>Could not load the rain.<br><br>Check the TV's Wi-Fi, then press OK to try again.<br>Press Back to close.</div></body>",
            "text/html", "utf-8"
        )
    }

    private fun hideBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    // Catch the remote's buttons before the web page can swallow them, so Back always works.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP) finish()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    if (failed) { failed = false; web.loadUrl(SITE) }
                    else web.evaluateJavascript("document.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter'}))", null)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> { web.evaluateJavascript("document.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowLeft'}))", null); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { web.evaluateJavascript("document.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowRight'}))", null); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() { finish() }

    override fun onPause() { super.onPause(); web.onPause() }
    override fun onResume() { super.onResume(); web.onResume() }
    override fun onDestroy() { web.destroy(); super.onDestroy() }
}
