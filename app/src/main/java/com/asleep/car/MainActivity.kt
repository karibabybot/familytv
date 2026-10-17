package com.asleep.car

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Process
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * "you fell asleep in my car" for the TV.
 * The TV plays the video, the rain, engine and thunder, and your Apple Music songs.
 * Your phone (the website) is the remote: it talks to the TV through a small relay.
 *
 * Remote: Back closes (or leaves sign-in). Up/Down tilts the view. OK shows the phone code.
 */
class MainActivity : Activity() {

    private val SITE = "https://adorable-griffin-e7813f.netlify.app/"
    private val RELAY = "https://asleep-relay.onrender.com"
    private val FILES = listOf("drive-loop.mp4", "audio/rain.wav", "audio/engine.wav", "audio/thunder1.wav", "audio/thunder2.wav")
    private val RATE = 44100
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val ui = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor()
    private lateinit var root: FrameLayout
    private lateinit var tex: TextureView
    private lateinit var status: TextView
    private lateinit var codeView: TextView
    private lateinit var npView: TextView
    private lateinit var card: FrameLayout
    private lateinit var mw: WebView
    private var popup: WebView? = null
    private var asl = ""

    private lateinit var code: String
    @Volatile private var alive = true
    private var relayUp = false
    private var phoneSeen = false

    // video
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var vw = 0
    private var vh = 0
    private var focus = 0.48f
    private var failed = false
    private var ready = false
    private var running = false

    // sound (mix values 0..2, like the website sliders)
    @Volatile private var mRain = 0.8f
    @Volatile private var mEngine = 0.5f
    @Volatile private var mThunder = 0.6f
    @Volatile private var mVol = 1.0f
    @Volatile private var fade = 1.0f
    @Volatile private var soundOn = true
    private var rain = ShortArray(0)
    private var engine = ShortArray(0)
    private var thunder1 = ShortArray(0)
    private var thunder2 = ShortArray(0)
    private class Hit(val data: ShortArray, val vol: Float) { var pos = 0 }
    private val hits = ArrayList<Hit>()
    @Volatile private var mixing = false
    private var mixer: Thread? = null
    private var sleepAt = 0L

    // music
    private data class Song(val url: String, val name: String)
    private var queue = listOf<Song>()
    private var idx = -1
    private enum class MS { IDLE, LOADING, STARTING, PLAYING, PAUSED, FAILED }
    private var ms = MS.IDLE
    private var msSince = 0L
    private var tries = 0
    private var musicVol = 0.9f
    private var musicMsg = ""
    private var auth: Boolean? = null
    private var signingIn = false
    private var pageReady = false
    private var lastT = 0.0
    private var lastD = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC
        val prefs = getPreferences(MODE_PRIVATE)
        focus = prefs.getFloat("focus", 0.48f)
        code = prefs.getString("code", null) ?: newCode().also { prefs.edit().putString("code", it).apply() }
        asl = assets.open("asl.js").bufferedReader().use { it.readText() }

        root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#070B11"))
        tex = TextureView(this)
        root.addView(tex, FrameLayout.LayoutParams(-1, -1))

        card = FrameLayout(this)
        card.setBackgroundColor(Color.BLACK)
        mw = WebView(this)
        card.addView(mw, FrameLayout.LayoutParams(-1, -1))
        root.addView(card, smallCard())
        card.alpha = 0f

        npView = label(18f, Gravity.START or Gravity.BOTTOM)
        root.addView(npView, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.BOTTOM).apply { setMargins(dp(28), 0, 0, dp(24)) })
        codeView = label(18f, Gravity.START)
        codeView.setBackgroundColor(Color.parseColor("#B3070B11"))
        codeView.setPadding(dp(14), dp(10), dp(14), dp(10))
        root.addView(codeView, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.TOP).apply { setMargins(dp(24), dp(20), 0, 0) })
        status = label(24f, Gravity.CENTER)
        status.setPadding(60, 60, 60, 60)
        root.addView(status, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        tex.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                surface = Surface(st); if (ready && running) startVideo()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { fit() }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                releaseVideo(); surface?.release(); surface = null; return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        setupWeb(mw)
        hideBars()
        showCode()
        load()
        listen()
        ui.post(ticker)
        ui.postDelayed(heartbeat, 1000)
    }

    // ---------- small helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(size: Float, g: Int) = TextView(this).apply {
        setTextColor(Color.parseColor("#DBE6EE")); textSize = size; gravity = g
        setShadowLayer(8f, 0f, 2f, Color.BLACK)
    }
    private fun newCode(): String {
        val a = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        return (1..5).map { a[Random.nextInt(a.length)] }.joinToString("")
    }
    private fun smallCard() = FrameLayout.LayoutParams(dp(440), dp(180), Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, dp(28), dp(24)) }
    private fun bigCard() = FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.8).toInt(), (resources.displayMetrics.heightPixels * 0.8).toInt(), Gravity.CENTER)

    private fun showCode() {
        val link = if (relayUp) "" else "\n(connecting…)"
        codeView.text = "Phone code: $code\nOn your phone, open the website and tap Connect to TV.$link"
        codeView.visibility = View.VISIBLE
    }
    private fun hideCodeSoon() { ui.removeCallbacks(hideCode); ui.postDelayed(hideCode, 8000) }
    private val hideCode = Runnable { if (phoneSeen) codeView.visibility = View.GONE }

    private fun local(name: String) = File(filesDir, name.replace('/', '_'))

    // ---------- loading files ----------
    private fun load() {
        failed = false
        status.visibility = View.VISIBLE
        status.text = "Getting the rain ready…"
        Thread {
            try {
                for (name in FILES) {
                    val f = local(name)
                    if (f.exists() && f.length() > 1000) continue
                    val tmp = File(filesDir, f.name + ".part")
                    val c = URL(SITE + name).openConnection() as HttpURLConnection
                    c.connectTimeout = 20000
                    c.readTimeout = 30000
                    if (c.responseCode != 200) throw Exception("$name: ${c.responseCode}")
                    c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                    c.disconnect()
                    if (!tmp.renameTo(f)) throw Exception("could not save $name")
                }
                rain = readWav(local("audio/rain.wav"))
                engine = readWav(local("audio/engine.wav"))
                thunder1 = readWav(local("audio/thunder1.wav"))
                thunder2 = readWav(local("audio/thunder2.wav"))
                ui.post { ready = true; if (!isFinishing) start() }
            } catch (e: Exception) {
                ui.post { showError(e.message ?: "network error") }
            }
        }.start()
    }

    /** Reads a 16-bit PCM WAV, mixes to mono and converts to 44.1 kHz. */
    private fun readWav(f: File): ShortArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var p = 12
        var ch = 1; var rate = RATE; var bits = 16
        var data: ShortArray? = null
        while (p + 8 <= b.limit()) {
            val id = String(ByteArray(4) { b.get(p + it) }, Charsets.US_ASCII)
            val size = b.getInt(p + 4)
            val body = p + 8
            if (id == "fmt ") {
                ch = b.getShort(body + 2).toInt()
                rate = b.getInt(body + 4)
                bits = b.getShort(body + 14).toInt()
            } else if (id == "data") {
                if (bits != 16) throw Exception("${f.name}: not 16-bit")
                val len = minOf(size, b.limit() - body) / 2 / ch
                data = ShortArray(len) { i ->
                    var s = 0
                    for (c in 0 until ch) s += b.getShort(body + (i * ch + c) * 2)
                    (s / ch).toShort()
                }
                break
            }
            p = body + size + (size and 1)
        }
        val d = data ?: throw Exception("${f.name}: no audio")
        if (rate == RATE) return d
        val n = (d.size.toLong() * RATE / rate).toInt()
        return ShortArray(n) { i ->
            val x = i.toDouble() * rate / RATE
            val i0 = x.toInt().coerceAtMost(d.size - 1)
            val i1 = (i0 + 1) % d.size
            val t = x - i0
            (d[i0] * (1 - t) + d[i1] * t).toInt().toShort()
        }
    }

    private fun showError(why: String) {
        failed = true
        status.visibility = View.VISIBLE
        status.text = "Could not load the rain ($why).\n\nCheck the TV's Wi-Fi, then press OK to try again.\nPress Back to close."
    }

    private fun start() {
        if (running) return
        running = true
        if (soundOn) startSound()
        if (surface != null) startVideo()
        ui.removeCallbacks(thunderTask)
        ui.postDelayed(thunderTask, Random.nextLong(6000, 15000))
        sendStatus()
    }

    // ---------- video ----------
    private fun startVideo() {
        if (player != null) return
        try {
            val mp = MediaPlayer()
            mp.setDataSource(local("drive-loop.mp4").absolutePath)
            mp.setSurface(surface)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnVideoSizeChangedListener { _, w, h -> vw = w; vh = h; fit() }
            mp.setOnPreparedListener {
                vw = it.videoWidth; vh = it.videoHeight; fit()
                it.start()
                status.visibility = View.GONE
            }
            mp.setOnErrorListener { _, what, extra -> showError("video $what/$extra"); true }
            mp.prepareAsync()
            player = mp
        } catch (e: Exception) {
            showError(e.message ?: "video error")
        }
    }

    /** Fill the whole TV with no bars, then pick which band of the tall video is shown. */
    private fun fit() {
        val w = tex.width.toFloat(); val h = tex.height.toFloat()
        if (w <= 0 || h <= 0 || vw <= 0 || vh <= 0) return
        val s = maxOf(w / vw, h / vh)
        val cw = vw * s; val ch = vh * s
        val m = Matrix()
        m.setScale(cw / w, ch / h)
        var ty = h / 2f - focus * ch
        ty = ty.coerceIn(h - ch, 0f)
        m.postTranslate((w - cw) / 2f, ty)
        tex.setTransform(m)
    }

    private fun tilt(d: Float) {
        focus = (focus + d).coerceIn(0.15f, 0.85f)
        getPreferences(MODE_PRIVATE).edit().putFloat("focus", focus).apply()
        fit()
    }

    private fun releaseVideo() {
        try { player?.release() } catch (_: Exception) { }
        player = null
    }

    // ---------- sound: one steady mixer, so loops never hiccup ----------
    private fun startSound() {
        if (mixing || rain.isEmpty()) return
        mixing = true
        mixer = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufBytes = maxOf(min * 4, RATE)
            @Suppress("DEPRECATION")
            val track = AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufBytes, AudioTrack.MODE_STREAM)
            val chunk = ShortArray(2048)
            var rp = 0; var ep = 0
            track.play()
            while (mixing) {
                val master = mVol * fade
                val rg = mRain * 1.25f * master
                val eg = mEngine * 0.9f * master
                val tg = mThunder * 1.5f * master
                synchronized(hits) {
                    for (i in chunk.indices) {
                        var v = rain[rp] * rg; rp++; if (rp >= rain.size) rp = 0
                        if (engine.isNotEmpty()) { v += engine[ep] * eg; ep++; if (ep >= engine.size) ep = 0 }
                        for (hit in hits) if (hit.pos < hit.data.size) { v += hit.data[hit.pos] * hit.vol * tg; hit.pos++ }
                        val x = v / 32768f
                        val y = if (x > 0.8f) 0.8f + (x - 0.8f) / (1f + (x - 0.8f) * 5f)
                                else if (x < -0.8f) -0.8f + (x + 0.8f) / (1f - (x + 0.8f) * 5f) else x
                        chunk[i] = (y.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    }
                    hits.removeAll { it.pos >= it.data.size }
                }
                track.write(chunk, 0, chunk.size)
            }
            try { track.stop() } catch (_: Exception) { }
            track.release()
        }.also { it.start() }
    }

    private fun stopSound() {
        mixing = false
        try { mixer?.join(1000) } catch (_: Exception) { }
        mixer = null
        synchronized(hits) { hits.clear() }
    }

    private fun setRain(on: Boolean) {
        soundOn = on
        fade = 1f
        if (on && running) startSound() else stopSound()
        if (!on) sleepAt = 0L
        sendStatus()
    }

    private val thunderTask = object : Runnable {
        override fun run() {
            if (!running) return
            if (mixing) {
                val d = if (Random.nextBoolean()) thunder1 else thunder2
                if (d.isNotEmpty()) synchronized(hits) { hits.add(Hit(d, Random.nextDouble(0.55, 1.0).toFloat())) }
            }
            ui.postDelayed(this, Random.nextLong(22000, 62000))
        }
    }

    // ---------- the Apple Music player ----------
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb(w: WebView) {
        with(w.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            userAgentString = DESKTOP_UA
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.setBackgroundColor(Color.BLACK)
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (view === mw) {
                    view.evaluateJavascript(asl, null)
                    pageReady = true
                    if (signingIn) ui.postDelayed({ clickSignIn() }, 2500)
                }
            }
        }
        w.webChromeClient = chrome
    }

    private val chrome: WebChromeClient = object : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            val ok = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
            if (ok.isNotEmpty()) request.grant(ok.toTypedArray()) else request.deny()
        }
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            closePopup()
            val p = WebView(this@MainActivity)
            setupWeb(p)
            p.webChromeClient = object : WebChromeClient() {
                override fun onCloseWindow(window: WebView) { closePopup(); afterSignIn() }
                override fun onPermissionRequest(request: PermissionRequest) { chrome.onPermissionRequest(request) }
            }
            root.addView(p, FrameLayout.LayoutParams(-1, -1))
            popup = p
            p.requestFocus()
            (resultMsg.obj as WebView.WebViewTransport).webView = p
            resultMsg.sendToTarget()
            return true
        }
        override fun onCloseWindow(window: WebView) { if (window === popup) { closePopup(); afterSignIn() } }
    }

    private fun closePopup() {
        popup?.let { root.removeView(it); try { it.destroy() } catch (_: Exception) { } }
        popup = null
    }

    private fun js(call: String, cb: ((String) -> Unit)? = null) {
        mw.evaluateJavascript("(function(){try{return window.__asl?window.__asl.$call:'noasl'}catch(e){return 'err:'+e}})()") { r ->
            if (cb != null) {
                val s = try { JSONTokener(r ?: "null").nextValue() as? String ?: "" } catch (_: Exception) { "" }
                cb(s)
            }
        }
    }

    private fun showCard(ms: Long) {
        card.animate().cancel(); card.alpha = 1f
        ui.removeCallbacks(fadeCard)
        if (ms > 0) ui.postDelayed(fadeCard, ms)
    }
    private val fadeCard = Runnable { if (!signingIn && this.ms != MS.FAILED) card.animate().alpha(0f).setDuration(1200).start() }

    private fun playIndex(i: Int) {
        if (queue.isEmpty()) return
        idx = ((i % queue.size) + queue.size) % queue.size
        val s = queue[idx]
        ms = MS.LOADING; msSince = SystemClock.elapsedRealtime(); tries = 0
        pageReady = false; lastT = 0.0; lastD = 0.0
        musicMsg = "Loading ${s.name}…"
        mw.loadUrl(s.url)
        showCard(0)
        npView.text = "♪ ${s.name}"
        sendStatus()
    }

    private fun touchAt(rectJson: String) {
        try {
            val o = JSONObject(rectJson)
            val sc = resources.displayMetrics.density
            val x = (o.getDouble("x") * sc).toFloat(); val y = (o.getDouble("y") * sc).toFloat()
            val t = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(t, t + 80, MotionEvent.ACTION_UP, x, y, 0)
            mw.dispatchTouchEvent(down); mw.dispatchTouchEvent(up)
            down.recycle(); up.recycle()
        } catch (_: Exception) { }
    }

    private fun musicTick(st: JSONObject) {
        val now = SystemClock.elapsedRealtime()
        val since = now - msSince
        val playing = st.optBoolean("playing")
        if (st.has("auth") && !st.isNull("auth")) auth = st.optBoolean("auth")
        val t = st.optDouble("t", 0.0); val d = st.optDouble("d", 0.0)
        when (ms) {
            MS.LOADING -> if (pageReady && (st.optBoolean("pb") || st.optBoolean("mk") || since > 15000)) {
                js("play()") { how -> if (how == "none") musicMsg = "Looking for the play button…" }
                ms = MS.STARTING; msSince = now
            }
            MS.STARTING -> when {
                playing -> {
                    ms = MS.PLAYING; msSince = now; musicMsg = ""
                    js("vol($musicVol)")
                    showCard(10000)
                    sendStatus()
                }
                since > 6000 && tries < 3 -> {
                    tries++; msSince = now
                    // Try a real tap on the play button, then a plain click.
                    js("playRect()") { r -> if (r.startsWith("{")) touchAt(r) else js("play()") }
                }
                since > 6000 -> {
                    ms = MS.FAILED; msSince = now
                    musicMsg = if (st.optBoolean("sign")) "The song didn't start. Try Sign in to Apple Music on TV."
                               else "The song didn't start on the TV."
                    showCard(0)
                    sendStatus()
                }
            }
            MS.PLAYING -> {
                val ended = st.optBoolean("ended") ||
                    (!playing && lastD > 1 && lastT >= lastD - 1.5)
                if (ended) { playIndex(idx + 1); return }
                if (!playing && since > 3000) {
                    // paused by itself (for example a preview stopped): count as ended after a moment
                    if (lastT > 0 && lastD > 0 && lastT >= lastD - 3) { playIndex(idx + 1); return }
                }
                if (playing) msSince = now
                if (t > 0) lastT = t
                if (d > 0) lastD = d
            }
            else -> {}
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (alive) {
                if (ms == MS.LOADING || ms == MS.STARTING || ms == MS.PLAYING) {
                    js("status()") { s -> if (s.startsWith("{")) try { musicTick(JSONObject(s)) } catch (_: Exception) { } else musicTick(JSONObject()) }
                }
                // sleep timer
                if (sleepAt > 0 && System.currentTimeMillis() >= sleepAt) {
                    fade = (fade - 0.05f).coerceAtLeast(0f)
                    if (ms == MS.PLAYING) js("vol(${musicVol * fade})")
                    if (fade <= 0f) {
                        sleepAt = 0L
                        setRain(false)
                        if (ms == MS.PLAYING) { js("pause()"); ms = MS.PAUSED }
                        fade = 1f
                        sendStatus()
                    }
                }
                ui.postDelayed(this, 1000)
            }
        }
    }

    private fun musicCmd(a: String) {
        when (a) {
            "pause" -> if (ms == MS.PLAYING || ms == MS.STARTING) { js("pause()"); ms = MS.PAUSED }
            "resume" -> if (ms == MS.PAUSED || ms == MS.FAILED) {
                js("resume()"); ms = MS.STARTING; msSince = SystemClock.elapsedRealtime(); tries = 0
            } else if (ms == MS.IDLE && queue.isNotEmpty()) playIndex(maxOf(idx, 0))
            "next" -> playIndex(idx + 1)
            "prev" -> playIndex(idx - 1)
            "stop" -> { js("pause()"); ms = MS.IDLE; mw.loadUrl("about:blank"); card.alpha = 0f; npView.text = "" }
            "show" -> showCard(10000)
        }
        sendStatus()
    }

    // ---------- signing in to Apple Music on the TV ----------
    private fun startSignIn(url: String?) {
        signingIn = true
        musicMsg = "Signing in on the TV…"
        card.layoutParams = bigCard()
        card.alpha = 1f
        mw.isFocusable = true
        mw.requestFocus()
        val u = url ?: queue.getOrNull(idx)?.url
        if (u != null && (mw.url == null || mw.url == "about:blank" || !pageReady)) mw.loadUrl(u)
        else if (pageReady) clickSignIn()
        sendStatus()
    }

    private fun clickSignIn() {
        if (!signingIn || popup != null) return
        js("signin()") { how ->
            if (how == "none") js("signRect()") { r -> if (r.startsWith("{")) touchAt(r) }
            ui.postDelayed({
                if (signingIn && popup == null) musicMsg = "Use the remote to choose Sign In in the player."
                sendStatus()
            }, 6000)
        }
    }

    private fun afterSignIn() {
        ui.postDelayed({
            val u = queue.getOrNull(idx)?.url
            if (u != null) mw.loadUrl(u) else mw.reload()
            ui.postDelayed({ js("status()") { s -> try { val o = JSONObject(s); if (!o.isNull("auth")) auth = o.optBoolean("auth") } catch (_: Exception) { }; endSignIn() } }, 5000)
        }, 800)
    }

    private fun endSignIn() {
        signingIn = false
        closePopup()
        card.layoutParams = smallCard()
        mw.clearFocus()
        root.requestFocus()
        musicMsg = when (auth) { true -> "Signed in. Full songs will play."; false -> "Not signed in yet. Songs play as previews."; else -> "" }
        showCard(6000)
        if (queue.isNotEmpty()) playIndex(maxOf(idx, 0))
        sendStatus()
    }

    // ---------- talking to the phone ----------
    private fun listen() {
        Thread {
            while (alive) {
                var c: HttpURLConnection? = null
                try {
                    c = URL("$RELAY/e/tv-${code.lowercase()}").openConnection() as HttpURLConnection
                    c.connectTimeout = 30000
                    c.readTimeout = 90000
                    c.setRequestProperty("Accept", "text/event-stream")
                    c.inputStream.bufferedReader().use { r ->
                        ui.post { relayUp = true; if (codeView.visibility == View.VISIBLE) showCode(); sendStatus() }
                        var event = ""
                        val data = StringBuilder()
                        while (alive) {
                            val line = r.readLine() ?: break
                            if (line.isEmpty()) {
                                if (data.isNotEmpty()) {
                                    val d = data.toString(); val replay = event == "last"
                                    ui.post { onMsg(d, replay) }
                                }
                                data.setLength(0); event = ""
                            } else if (line.startsWith("data:")) data.append(line.substring(5).trim())
                            else if (line.startsWith("event:")) event = line.substring(6).trim()
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    try { c?.disconnect() } catch (_: Exception) { }
                }
                ui.post { relayUp = false }
                if (alive) try { Thread.sleep(3000) } catch (_: Exception) { }
            }
        }.start()
    }

    private fun post(room: String, body: String) {
        net.execute {
            try {
                val c = URL("$RELAY/p/$room").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true
                c.connectTimeout = 30000; c.readTimeout = 60000
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
                c.responseCode; c.disconnect()
            } catch (_: Exception) { }
        }
    }

    private var statusQueued = false
    private fun sendStatus() {
        if (statusQueued) return
        statusQueued = true
        ui.postDelayed({
            statusQueued = false
            val o = JSONObject()
            o.put("keep", true); o.put("type", "status"); o.put("ver", 3)
            o.put("rain", soundOn && running)
            o.put("song", queue.getOrNull(idx)?.name ?: "")
            o.put("idx", idx)
            o.put("music", ms.name.lowercase())
            o.put("msg", musicMsg)
            o.put("auth", auth ?: JSONObject.NULL)
            o.put("signin", signingIn)
            o.put("sleep", sleepAt)
            o.put("mix", JSONObject().put("rain", mRain.toDouble()).put("engine", mEngine.toDouble()).put("thunder", mThunder.toDouble()).put("vol", mVol.toDouble()).put("music", musicVol.toDouble()))
            post("ph-${code.lowercase()}", o.toString())
        }, 300)
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (!alive) return
            sendStatus()                       // phone sees the TV is on
            net.execute { try { (URL("$RELAY/").openConnection() as HttpURLConnection).apply { connectTimeout = 30000; readTimeout = 60000; responseCode; disconnect() } } catch (_: Exception) { } }
            ui.postDelayed(this, 60000)
        }
    }

    private fun onMsg(raw: String, replay: Boolean) {
        val o = try { JSONObject(raw) } catch (_: Exception) { return }
        val type = o.optString("type")
        if (replay && type != "state") return
        if (!replay) {
            phoneSeen = true
            if (codeView.visibility == View.VISIBLE) hideCodeSoon()
        }
        when (type) {
            "state" -> {
                o.optJSONObject("mix")?.let { m ->
                    mRain = m.optDouble("rain", mRain.toDouble()).toFloat().coerceIn(0f, 2f)
                    mEngine = m.optDouble("engine", mEngine.toDouble()).toFloat().coerceIn(0f, 2f)
                    mThunder = m.optDouble("thunder", mThunder.toDouble()).toFloat().coerceIn(0f, 2f)
                    mVol = m.optDouble("vol", mVol.toDouble()).toFloat().coerceIn(0f, 2f)
                    if (m.has("music")) {
                        musicVol = m.optDouble("music", musicVol.toDouble()).toFloat().coerceIn(0f, 1f)
                        if (ms == MS.PLAYING) js("vol($musicVol)")
                    }
                }
                if (!replay && o.has("rainOn")) {
                    val on = o.optBoolean("rainOn")
                    if (on != soundOn) setRain(on)
                }
            }
            "rain" -> setRain(o.optBoolean("on", true))
            "play" -> {
                val arr: JSONArray = o.optJSONArray("list") ?: JSONArray()
                val list = ArrayList<Song>()
                for (i in 0 until arr.length()) {
                    val it = arr.optJSONObject(i) ?: continue
                    val u = it.optString("e")
                    if (u.startsWith("https://embed.music.apple.com/")) list.add(Song(u, it.optString("n", "Apple Music")))
                }
                if (list.isNotEmpty()) { queue = list; playIndex(o.optInt("index", 0)) }
            }
            "music" -> musicCmd(o.optString("action"))
            "signin" -> startSignIn(o.optString("url").takeIf { it.startsWith("https://embed.music.apple.com/") })
            "signin_done" -> if (signingIn) endSignIn()
            "tilt" -> tilt(if (o.optInt("d") < 0) -0.04f else 0.04f)
            "sleep" -> {
                val min = o.optInt("min")
                sleepAt = if (min > 0) System.currentTimeMillis() + min * 60000L else 0L
                fade = 1f
                sendStatus()
            }
            "code" -> { showCode(); hideCodeSoon() }
            "hello" -> sendStatus()
        }
    }

    // ---------- lifecycle ----------
    private fun stopAll() {
        running = false
        ui.removeCallbacks(thunderTask)
        releaseVideo()
        stopSound()
        if (ms == MS.PLAYING || ms == MS.STARTING) { js("pause()"); ms = MS.PAUSED }
    }

    override fun onStop() {
        super.onStop()
        stopAll()
        sendStatus()
    }

    override fun onStart() {
        super.onStart()
        if (ready && !running) start()
    }

    override fun onDestroy() {
        alive = false
        stopAll()
        try { mw.destroy() } catch (_: Exception) { }
        net.shutdown()
        super.onDestroy()
    }

    private fun hideBars() {
        @Suppress("DEPRECATION")
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val upEv = event.action == KeyEvent.ACTION_UP
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (upEv) {
                val p = popup
                when {
                    p != null && p.canGoBack() -> p.goBack()
                    p != null -> { closePopup(); endSignIn() }
                    signingIn -> endSignIn()
                    else -> finish()
                }
            }
            return true
        }
        // While signing in, the remote drives the Apple sign-in page.
        if (signingIn) return super.dispatchKeyEvent(event)
        if (failed && upEv && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
            load(); return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> { tilt(-0.03f); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { tilt(0.03f); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (codeView.visibility == View.VISIBLE) codeView.visibility = View.GONE else showCode()
                if (queue.isNotEmpty()) showCard(8000)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { musicCmd(if (ms == MS.PLAYING) "pause" else "resume"); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_NEXT -> { musicCmd("next"); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { musicCmd("prev"); return true }
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
            code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
            code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) return true
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Back is handled in dispatchKeyEvent")
    override fun onBackPressed() { }
}
