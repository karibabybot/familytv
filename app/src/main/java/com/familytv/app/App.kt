package com.familytv.app

// App.kt - the whole Fire Stick app in one file.
//
// Screens (each one is an "Activity"):
//   LoginActivity    -> type server address, username, password
//   ChannelsActivity -> list of channels you're allowed to watch
//   PlayerActivity   -> plays the channel you picked
//
// Plus two helpers:
//   Prefs -> remembers the server + login on the device
//   Api   -> talks to your TV server

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val BG = Color.parseColor("#101820")
private val MUTED = Color.parseColor("#9AA5B1")

private fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

// ---------------------------------------------------------------
// Prefs: small notebook the app keeps on the device
// ---------------------------------------------------------------
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("familytv", Context.MODE_PRIVATE)

    var server: String
        get() = sp.getString("server", "") ?: ""
        set(v) { sp.edit().putString("server", v).apply() }

    var user: String
        get() = sp.getString("user", "") ?: ""
        set(v) { sp.edit().putString("user", v).apply() }

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) { sp.edit().putString("token", v).apply() }

    fun logout() { sp.edit().remove("token").apply() }
}

// ---------------------------------------------------------------
// Api: asks the server things
// ---------------------------------------------------------------
class AuthException(message: String) : Exception(message)

data class Channel(val name: String, val category: String, val url: String)

object Api {
    // Cleans up what you typed:
    //   "familytv.onrender.com"  -> "https://familytv.onrender.com"
    //   "192.168.1.5:3000"       -> "http://192.168.1.5:3000"  (has a port number = home server)
    fun cleanServer(raw: String): String {
        var s = raw.trim().trimEnd('/')
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = if (Regex(":\\d+$").containsMatchIn(s)) "http://$s" else "https://$s"
        }
        return s
    }

    private fun call(method: String, url: String, body: String?, token: String?): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        // Free hosting can take about a minute to wake up, so be patient.
        c.connectTimeout = 90000
        c.readTimeout = 90000
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return Pair(code, text)
    }

    // Sends username + password, gets back a token (the "wristband")
    fun login(server: String, user: String, pass: String): String {
        val body = JSONObject().put("username", user).put("password", pass).toString()
        val (code, text) = call("POST", "$server/api/login", body, null)
        val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
        if (code == 200) return json.getString("token")
        throw AuthException(json.optString("error", "Login failed ($code)"))
    }

    // Shows the wristband, gets the channel list
    fun channels(server: String, token: String): List<Channel> {
        val (code, text) = call("GET", "$server/api/channels", null, token)
        if (code == 401) throw AuthException("Please log in again.")
        if (code != 200) throw Exception("Server error ($code)")
        val arr = JSONObject(text).getJSONArray("channels")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Channel(o.getString("name"), o.optString("category", ""), o.getString("url"))
        }
    }
}

// ---------------------------------------------------------------
// Screen 1: Login
// ---------------------------------------------------------------
class LoginActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)

        // Already logged in before? Skip straight to the channels.
        if (prefs.token.isNotEmpty() && prefs.server.isNotEmpty()) {
            startActivity(Intent(this, ChannelsActivity::class.java))
            finish()
            return
        }

        fun field(hint: String, type: Int, start: String): EditText = EditText(this).apply {
            setHint(hint)
            setText(start)
            inputType = type
            textSize = 22f
            setTextColor(Color.WHITE)
            setHintTextColor(MUTED)
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(dp(600), ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(12) }
        }

        val title = TextView(this).apply {
            text = "Family TV"
            textSize = 36f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(24))
        }
        val serverF = field("Server address (example: familytv.onrender.com)",InputType.TYPE_TEXT_VARIATION_URI, prefs.server)
        val userF = field("Username", InputType.TYPE_CLASS_TEXT, prefs.user)
        val passF = field("Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, "")
        val button = Button(this).apply {
            text = "Log in"
            textSize = 22f
            layoutParams = LinearLayout.LayoutParams(dp(600), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val status = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.parseColor("#FFB4A8"))
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }

        button.setOnClickListener {
            val raw = serverF.text.toString()
            val u = userF.text.toString().trim()
            val p = passF.text.toString()
            if (raw.isBlank() || u.isEmpty() || p.isEmpty()) {
                status.text = "Fill in all three boxes."
                return@setOnClickListener
            }
            val server = Api.cleanServer(raw)
            status.text = "Signing in... (if the server was asleep this can take a minute)"
            button.isEnabled = false
            Thread {
                try {
                    val token = Api.login(server, u, p)
                    runOnUiThread {
                        prefs.server = server
                        prefs.user = u
                        prefs.token = token
                        startActivity(Intent(this, ChannelsActivity::class.java))
                        finish()
                    }
                } catch (e: AuthException) {
                    runOnUiThread { status.text = e.message; button.isEnabled = true }
                } catch (e: Exception) {
                    runOnUiThread {
                        status.text = "Can't reach the server. Check the address."
                        button.isEnabled = true
                    }
                }
            }.start()
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(BG)
            addView(title)
            addView(serverF)
            addView(userF)
            addView(passF)
            addView(button)
            addView(status)
        }
        setContentView(root)
    }
}

// ---------------------------------------------------------------
// Screen 2: Channel list
// ---------------------------------------------------------------
class ChannelsActivity : Activity() {
    private var channels: List<Channel> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)

        val title = TextView(this).apply {
            text = "Channels  (${prefs.user})"
            textSize = 30f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val logout = Button(this).apply {
            text = "Log out"
            textSize = 18f
            setOnClickListener {
                prefs.logout()
                startActivity(Intent(this@ChannelsActivity, LoginActivity::class.java))
                finish()
            }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title)
            addView(logout)
        }
        val status = TextView(this).apply {
            text = "Loading channels... (can take a minute if the server was asleep)"
            textSize = 20f
            setTextColor(MUTED)
            setPadding(0, dp(12), 0, dp(12))
        }
        val list = ListView(this).apply {
            divider = ColorDrawable(Color.parseColor("#22FFFFFF"))
            dividerHeight = 1
            selector = ColorDrawable(Color.parseColor("#55FFFFFF"))
            isFocusable = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(48), dp(32), dp(48), dp(32))
            addView(header)
            addView(status)
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        list.setOnItemClickListener { _, _, position, _ ->
            startActivity(
                Intent(this, PlayerActivity::class.java)
                    .putStringArrayListExtra("names", ArrayList(channels.map { it.name }))
                    .putStringArrayListExtra("urls", ArrayList(channels.map { it.url }))
                    .putExtra("index", position)
            )
        }

        Thread {
            try {
                val result = Api.channels(prefs.server, prefs.token)
                    .sortedWith(compareBy({ it.category }, { it.name }))
                runOnUiThread {
                    channels = result
                    val labels = result.map { if (it.category.isBlank()) it.name else "${it.category}  -  ${it.name}" }
                    list.adapter = object : ArrayAdapter<String>(
                        this, android.R.layout.simple_list_item_1, labels
                    ) {
                        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                            val v = super.getView(position, convertView, parent) as TextView
                            v.textSize = 24f
                            v.setTextColor(Color.WHITE)
                            v.setPadding(dp(16), dp(14), dp(16), dp(14))
                            return v
                        }
                    }
                    status.text = if (result.isEmpty()) "No channels for this account." else ""
                    list.requestFocus()
                }
            } catch (e: AuthException) {
                runOnUiThread {
                    prefs.logout()
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Couldn't load channels. Check your connection and the server." }
            }
        }.start()
    }
}

// ---------------------------------------------------------------
// Screen 3: Player
//   UP / DOWN on the remote (when the on-screen controls are hidden)
//   switch channels. CENTER shows the controls. BACK returns to the list.
// ---------------------------------------------------------------
class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var label: TextView
    private var names: List<String> = emptyList()
    private var urls: List<String> = emptyList()
    private var index = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        names = intent.getStringArrayListExtra("names") ?: arrayListOf()
        urls = intent.getStringArrayListExtra("urls") ?: arrayListOf()
        index = intent.getIntExtra("index", 0)

        playerView = PlayerView(this)
        label = TextView(this).apply {
            textSize = 26f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        val frame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                playerView,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            addView(
                label,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START
                ).apply { setMargins(dp(32), dp(24), 0, 0) }
            )
        }
        setContentView(frame)
    }

    override fun onStart() {
        super.onStart()
        // Allow streams that jump between http and https
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@PlayerActivity, "Can't play this channel right now.", Toast.LENGTH_LONG).show()
            }
        })
        playerView.player = p
        player = p
        playChannel(index)
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }

    private fun playChannel(i: Int) {
        if (urls.isEmpty()) return
        index = (i + urls.size) % urls.size
        label.text = names[index]
        label.visibility = View.VISIBLE
        label.postDelayed({ label.visibility = View.GONE }, 3000)
        player?.setMediaItem(MediaItem.fromUri(urls[index]))
        player?.prepare()
        player?.playWhenReady = true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!playerView.isControllerFullyVisible) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { playChannel(index + 1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { playChannel(index - 1); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
