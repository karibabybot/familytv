package com.asleep.car

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
import android.os.Process
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Plays your car video full screen with the real rain, engine idle and thunder.
 * Arrows up/down: tilt the view. Back: close.
 */
class MainActivity : Activity() {

    private val SITE = "https://adorable-griffin-e7813f.netlify.app/"
    private val FILES = listOf("drive-loop.mp4", "audio/rain.wav", "audio/engine.wav", "audio/thunder1.wav", "audio/thunder2.wav")

    // Same balance as the page's default mix.
    private val RAIN_VOL = 1.0f
    private val ENGINE_VOL = 0.45f
    private val THUNDER_VOL = 0.9f
    private val RATE = 44100

    private lateinit var tex: TextureView
    private lateinit var status: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var vw = 0
    private var vh = 0
    private var focus = 0.48f          // which part of the tall video sits in the middle of the TV
    private var failed = false
    private var ready = false
    private var running = false

    // sound
    private var rain = ShortArray(0)
    private var engine = ShortArray(0)
    private var thunder1 = ShortArray(0)
    private var thunder2 = ShortArray(0)
    private class Hit(val data: ShortArray, val vol: Float) { var pos = 0 }
    private val hits = ArrayList<Hit>()
    @Volatile private var mixing = false
    private var mixer: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC
        focus = getPreferences(MODE_PRIVATE).getFloat("focus", 0.48f)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#070B11"))
        tex = TextureView(this)
        root.addView(tex, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        status = TextView(this).apply {
            setTextColor(Color.parseColor("#DBE6EE"))
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(60, 60, 60, 60)
        }
        root.addView(status, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
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
        hideBars()
        load()
    }

    private fun local(name: String) = File(filesDir, name.replace('/', '_'))

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
        startSound()
        if (surface != null) startVideo()
        ui.postDelayed(thunderTask, Random.nextLong(6000, 15000))
    }

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

    /** Fill the whole TV with no bars: zoom so the video is as wide as the screen, then pick the band shown. */
    private fun fit() {
        val w = tex.width.toFloat(); val h = tex.height.toFloat()
        if (w <= 0 || h <= 0 || vw <= 0 || vh <= 0) return
        val m = Matrix()
        val scaleW = w / vw; val scaleH = h / vh
        val s = maxOf(scaleW, scaleH)                // cover the screen
        val cw = vw * s; val ch = vh * s              // content size on screen
        // TextureView stretches the video to the view; undo that, then scale to cover.
        m.setScale(cw / w, ch / h)
        val tx = (w - cw) / 2f
        var ty = h / 2f - focus * ch
        ty = ty.coerceIn(h - ch, 0f)
        m.postTranslate(tx, ty)
        tex.setTransform(m)
    }

    private fun releaseVideo() {
        try { player?.release() } catch (_: Exception) { }
        player = null
    }

    // ---- sound: one steady mixer, so loops never hiccup ----

    private fun startSound() {
        if (mixing) return
        mixing = true
        mixer = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufBytes = maxOf(min * 4, RATE)  // about half a second of cushion
            @Suppress("DEPRECATION")
            val track = AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufBytes, AudioTrack.MODE_STREAM)
            val chunk = ShortArray(2048)
            var rp = 0; var ep = 0
            track.play()
            while (mixing) {
                synchronized(hits) {
                    for (i in chunk.indices) {
                        var v = 0f
                        if (rain.isNotEmpty()) { v += rain[rp] * RAIN_VOL; rp++; if (rp >= rain.size) rp = 0 }
                        if (engine.isNotEmpty()) { v += engine[ep] * ENGINE_VOL; ep++; if (ep >= engine.size) ep = 0 }
                        for (hit in hits) if (hit.pos < hit.data.size) { v += hit.data[hit.pos] * hit.vol; hit.pos++ }
                        // soft limit instead of hard clipping
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

    private val thunderTask = object : Runnable {
        override fun run() {
            if (!running) return
            val d = if (Random.nextBoolean()) thunder1 else thunder2
            if (d.isNotEmpty()) synchronized(hits) { hits.add(Hit(d, THUNDER_VOL * Random.nextDouble(0.55, 1.0).toFloat())) }
            ui.postDelayed(this, Random.nextLong(18000, 50000))
        }
    }

    private fun stopAll() {
        running = false
        ui.removeCallbacks(thunderTask)
        releaseVideo()
        mixing = false
        try { mixer?.join(1000) } catch (_: Exception) { }
        mixer = null
        synchronized(hits) { hits.clear() }
    }

    override fun onStop() {
        super.onStop()
        stopAll()
    }

    override fun onStart() {
        super.onStart()
        if (ready && !running) start()
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP) finish()
            return true
        }
        if (failed && event.action == KeyEvent.ACTION_UP &&
            (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
            load()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN &&
            (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)) {
            focus = (focus + if (code == KeyEvent.KEYCODE_DPAD_UP) -0.03f else 0.03f).coerceIn(0.15f, 0.85f)
            getPreferences(MODE_PRIVATE).edit().putFloat("focus", focus).apply()
            fit()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Back closes the app")
    override fun onBackPressed() { finish() }
}
