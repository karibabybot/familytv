package com.asleep.car

import android.app.Activity
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.VideoView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

/**
 * Plays your car video full screen with the real rain, engine idle and thunder.
 * Everything is played by the TV itself (no web page). Files come from your site
 * the first time and are kept on the TV after that.
 * Back closes the app.
 */
class MainActivity : Activity() {

    private val SITE = "https://adorable-griffin-e7813f.netlify.app/"
    private val FILES = listOf("drive-loop.mp4", "audio/rain.wav", "audio/engine.wav", "audio/thunder1.wav", "audio/thunder2.wav")

    // Same balance as the page's default mix.
    private val RAIN_VOL = 1.0f
    private val ENGINE_VOL = 0.45f
    private val THUNDER_VOL = 0.9f

    private lateinit var video: VideoView
    private lateinit var status: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var rain: MediaPlayer? = null
    private var engine: MediaPlayer? = null
    private val thunders = mutableListOf<MediaPlayer>()
    private var failed = false
    private var ready = false
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#070B11"))
        video = VideoView(this)
        root.addView(video, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        status = TextView(this).apply {
            setTextColor(Color.parseColor("#DBE6EE"))
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(60, 60, 60, 60)
        }
        root.addView(status, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
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
                ui.post { ready = true; if (!isFinishing) start() }
            } catch (e: Exception) {
                ui.post { showError(e.message ?: "network error") }
            }
        }.start()
    }

    private fun showError(why: String) {
        failed = true
        status.visibility = View.VISIBLE
        status.text = "Could not load the rain ($why).\n\nCheck the TV's Wi-Fi, then press OK to try again.\nPress Back to close."
    }

    private fun start() {
        if (running) return
        running = true
        try {
            video.setOnPreparedListener { mp ->
                mp.isLooping = true
                mp.setVolume(0f, 0f)
                mp.start()
                status.visibility = View.GONE
            }
            video.setOnErrorListener { _, what, extra ->
                showError("video $what/$extra"); true
            }
            video.setVideoPath(local("drive-loop.mp4").absolutePath)

            rain = loop(local("audio/rain.wav"), RAIN_VOL)
            engine = loop(local("audio/engine.wav"), ENGINE_VOL)
            scheduleThunder(Random.nextLong(6000, 15000))
        } catch (e: Exception) {
            showError(e.message ?: "playback error")
        }
    }

    private fun loop(f: File, vol: Float): MediaPlayer = MediaPlayer().apply {
        setDataSource(f.absolutePath)
        isLooping = true
        setVolume(vol, vol)
        prepare()
        start()
    }

    private fun scheduleThunder(delay: Long) {
        ui.postDelayed(thunderTask, delay)
    }

    private val thunderTask = Runnable {
        if (!running) return@Runnable
        try {
            val f = local(if (Random.nextBoolean()) "audio/thunder1.wav" else "audio/thunder2.wav")
            val v = THUNDER_VOL * Random.nextDouble(0.55, 1.0).toFloat()
            val p = MediaPlayer()
            p.setDataSource(f.absolutePath)
            p.setVolume(v, v)
            p.setOnCompletionListener { thunders.remove(it); it.release() }
            p.prepare()
            p.start()
            thunders.add(p)
        } catch (_: Exception) { }
        scheduleThunder(Random.nextLong(18000, 50000))
    }

    private fun stopAll() {
        running = false
        ui.removeCallbacks(thunderTask)
        try { video.stopPlayback() } catch (_: Exception) { }
        rain?.release(); rain = null
        engine?.release(); engine = null
        thunders.forEach { try { it.release() } catch (_: Exception) { } }
        thunders.clear()
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
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Back closes the app")
    override fun onBackPressed() { finish() }
}
