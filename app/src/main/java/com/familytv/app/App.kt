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
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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
import java.util.concurrent.Executors

private val BG = Color.parseColor("#070C18")
private val PANEL = Color.parseColor("#0F1830")
private val CHIP = Color.parseColor("#1C2A48")
private val ACCENT = Color.parseColor("#2F6BFF")
private val STAR = Color.parseColor("#FFB020")
private val MUTED = Color.parseColor("#8FA0BC")
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

private fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

// A rounded box, optionally with an outline (used for tiles and menu items)
private fun Context.rounded(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable {
    val r = dp(radiusDp).toFloat()
    val w = dp(strokeDp)
    val d = GradientDrawable()
    d.setColor(color)
    d.cornerRadius = r
    if (w > 0) d.setStroke(w, strokeColor)
    return d
}

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

    // Favorites are remembered on this device, by channel address.
    fun favorites(): Set<String> = sp.getStringSet("favs", emptySet())?.toSet() ?: emptySet()

    // Returns true if the channel is now a favorite, false if it was removed.
    fun toggleFavorite(url: String): Boolean {
        val set = favorites().toMutableSet()
        val added = if (set.contains(url)) { set.remove(url); false } else { set.add(url); true }
        sp.edit().putStringSet("favs", set).apply()
        return added
    }
}

// ---------------------------------------------------------------
// Api: asks the server things
// ---------------------------------------------------------------
class AuthException(message: String) : Exception(message)

data class Channel(val name: String, val category: String, val url: String, val logo: String = "") {
    // Playlists often pack several categories into one label ("Kids;Sports").
    val tags: List<String>
        get() = category.split(";").map { it.trim() }.filter { it.isNotEmpty() }
}

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
            Channel(o.getString("name"), o.optString("category", ""), o.getString("url"), o.optString("logo", ""))
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
// Logos: downloads small pictures in the background and remembers them
// ---------------------------------------------------------------
object Images {
    private val cache = object : LruCache<String, Bitmap>(((Runtime.getRuntime().maxMemory() / 1024) / 10).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val pool = Executors.newFixedThreadPool(3)

    // Calls onResult(true) once the picture is showing, or onResult(false) if it can't be loaded.
    fun load(url: String, view: ImageView, onResult: (Boolean) -> Unit) {
        view.tag = url
        val hit = cache.get(url)
        if (hit != null) {
            view.setImageBitmap(hit)
            onResult(true)
            return
        }
        pool.execute {
            val bmp: Bitmap? = try { fetch(url) } catch (e: Exception) { null }
            view.post {
                if (view.tag == url) {
                    if (bmp != null) {
                        cache.put(url, bmp)
                        view.setImageBitmap(bmp)
                        onResult(true)
                    } else {
                        onResult(false)
                    }
                }
            }
        }
    }

    private fun fetch(url: String): Bitmap? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.instanceFollowRedirects = true
        try {
            if (c.responseCode != 200) return null
            val bytes = c.inputStream.use { it.readBytes() }
            if (bytes.size > 3000000) return null
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 600 || bounds.outHeight / sample > 600) sample *= 2
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } finally {
            c.disconnect()
        }
    }
}

// ---------------------------------------------------------------
// Screen 2: Home (left menu, search, rows of channel tiles)
//   OK = watch.   Hold OK, or press the menu (three lines) button = add/remove favorite.
// ---------------------------------------------------------------
class TileHolder(val channel: Channel, val star: TextView)

class ChannelsActivity : Activity() {
    private lateinit var prefs: Prefs
    private var all: List<Channel> = emptyList()
    private var categories: List<String> = emptyList()
    private var favSet: Set<String> = emptySet()
    private var section = "home"
    private var query = ""
    private var firstTile: View? = null

    private lateinit var navList: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var heading: TextView
    private lateinit var subtitle: TextView
    private lateinit var status: TextView
    private val navViews = ArrayList<Pair<String, TextView>>()

    private var cols = 4
    private var tileW = 0

    private val tilePalette = listOf(
        "#1F3A8A", "#0F766E", "#7C2D12", "#4C1D95", "#334155", "#14532D", "#831843", "#1E3A5F"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        // Work out how many tiles fit across this TV screen.
        val sidebarPx = dp(190)
        val contentW = resources.displayMetrics.widthPixels - sidebarPx - dp(56)
        cols = if (contentW > dp(800)) 5 else 4
        tileW = (contentW - dp(14) * (cols - 1)) / cols

        // ----- Left menu -----
        val brandIcon = TextView(this).apply {
            text = "▶"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(ACCENT, 10)
        }
        val brandName = TextView(this).apply {
            text = "Family TV"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(10), 0, 0, 0)
        }
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, 0, dp(18))
            addView(brandIcon, LinearLayout.LayoutParams(dp(32), dp(32)))
            addView(brandName)
        }
        navList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val navScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(navList)
        }
        val who = TextView(this).apply {
            text = prefs.user
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(8), 0, 0)
        }
        val logout = TextView(this).apply {
            text = "Log out"
            textSize = 15f
            setTextColor(MUTED)
            isFocusable = true
            isClickable = true
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, 0, 0)
            background = rounded(Color.TRANSPARENT, 12)
        }
        logout.setOnFocusChangeListener { v, f ->
            v.background = rounded(if (f) ACCENT else Color.TRANSPARENT, 12)
        }
        logout.setOnClickListener {
            prefs.logout()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
        val sidebar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PANEL)
            setPadding(dp(14), dp(24), dp(14), dp(14))
            addView(brand)
            addView(navScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(who)
            addView(logout, LinearLayout.LayoutParams(MATCH, dp(40)))
        }

        // ----- Main area -----
        heading = TextView(this).apply {
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        subtitle = TextView(this).apply {
            textSize = 14f
            setTextColor(MUTED)
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(heading)
            addView(subtitle)
        }
        val searchPill = TextView(this).apply {
            text = "Search channels"
            textSize = 16f
            setTextColor(MUTED)
            isFocusable = true
            isClickable = true
            gravity = Gravity.CENTER
            setPadding(dp(22), 0, dp(22), 0)
            background = rounded(CHIP, 24)
        }
        searchPill.setOnFocusChangeListener { v, f ->
            v.background = if (f) rounded(CHIP, 24, ACCENT, 3) else rounded(CHIP, 24)
        }
        searchPill.setOnClickListener { askSearch() }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(22), dp(28), dp(10))
            addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(searchPill, LinearLayout.LayoutParams(WRAP, dp(44)))
        }
        status = TextView(this).apply {
            text = "Loading channels... (can take a minute if the server was asleep)"
            textSize = 16f
            setTextColor(MUTED)
            setPadding(dp(28), dp(8), dp(28), dp(8))
        }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val contentScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(28), dp(8), dp(28), dp(28))
            addView(content)
        }
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(status)
            addView(contentScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BG)
            addView(sidebar, LinearLayout.LayoutParams(sidebarPx, MATCH))
            addView(main, LinearLayout.LayoutParams(0, MATCH, 1f))
        }
        setContentView(root)

        loadChannels()
    }

    private fun loadChannels() {
        Thread {
            try {
                val result = Api.channels(prefs.server, prefs.token).sortedBy { it.name.lowercase() }
                runOnUiThread {
                    all = result
                    val counts = HashMap<String, Int>()
                    for (ch in result) for (t in ch.tags) counts[t] = (counts[t] ?: 0) + 1
                    categories = counts.toList()
                        .sortedWith(compareBy<Pair<String, Int>>({ -it.second }, { it.first }))
                        .map { it.first }
                    buildNav()
                    status.text = if (result.isEmpty()) "No channels for this account yet." else ""
                    showSection()
                    val first = firstTile
                    if (first != null) first.requestFocus() else navViews.firstOrNull()?.second?.requestFocus()
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

    // ---------- Left menu items ----------
    private fun buildNav() {
        navList.removeAllViews()
        navViews.clear()
        addNav("Home", "home")
        addNav("Favorites", "favs")
        addNav("All channels", "all")
        val label = TextView(this).apply {
            text = "CATEGORIES"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(MUTED)
            setPadding(dp(14), dp(16), 0, dp(6))
        }
        navList.addView(label)
        for (cat in categories.take(15)) addNav(cat, "cat:$cat")
    }

    private fun addNav(label: String, key: String) {
        val v = TextView(this).apply {
            text = label
            textSize = 16f
            setTextColor(Color.WHITE)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            isFocusable = true
            isClickable = true
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(10), 0)
        }
        v.setOnFocusChangeListener { _, f -> styleNav(v, key, f) }
        v.setOnClickListener {
            section = key
            query = ""
            refreshNav()
            showSection()
        }
        navViews.add(Pair(key, v))
        styleNav(v, key, false)
        navList.addView(v, LinearLayout.LayoutParams(MATCH, dp(42)).apply { bottomMargin = dp(4) })
    }

    private fun styleNav(v: TextView, key: String, focused: Boolean) {
        val color = when {
            focused -> ACCENT
            key == section && query.isEmpty() -> CHIP
            else -> Color.TRANSPARENT
        }
        v.background = rounded(color, 12)
    }

    private fun refreshNav() {
        for (p in navViews) styleNav(p.second, p.first, p.second.isFocused)
    }

    // ---------- Search ----------
    private fun askSearch() {
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(query)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("Search channels")
            .setView(input)
            .setPositiveButton("Search") { _, _ ->
                query = input.text.toString()
                refreshNav()
                showSection()
            }
            .setNegativeButton("Clear") { _, _ ->
                query = ""
                refreshNav()
                showSection()
            }
            .show()
    }

    // ---------- What's on screen ----------
    private fun channelsIn(cat: String): List<Channel> = all.filter { it.tags.contains(cat) }

    private fun showSection() {
        content.removeAllViews()
        firstTile = null
        favSet = prefs.favorites()
        val q = query.trim().lowercase()
        val favList = all.filter { favSet.contains(it.url) }

        if (q.isNotEmpty()) {
            heading.text = "Search"
            subtitle.text = "Results for \"${query.trim()}\""
            val found = all.filter { it.name.lowercase().contains(q) || it.category.lowercase().contains(q) }
            if (found.isEmpty()) addMessage("No channels match that.") else addGrid(found)
            return
        }

        when {
            section == "home" -> {
                heading.text = "Home"
                subtitle.text = "OK to watch. Hold OK, or press the menu button, to add a favorite."
                if (all.isEmpty()) {
                    addMessage("No channels yet. Add some from the Family TV page on your phone.")
                    return
                }
                if (favList.isNotEmpty()) addRow("Favorites", favList)
                for (cat in categories.take(8)) addRow(cat, channelsIn(cat).take(30))
                if (categories.isEmpty()) addGrid(all)
            }
            section == "favs" -> {
                heading.text = "Favorites"
                subtitle.text = "Hold OK, or press the menu button, on a channel to remove it."
                if (favList.isEmpty()) addMessage("No favorites yet. Hold OK on any channel to add it.") else addGrid(favList)
            }
            section == "all" -> {
                heading.text = "All channels"
                subtitle.text = "${all.size} channels"
                addGrid(all)
            }
            else -> {
                val cat = section.removePrefix("cat:")
                val list = channelsIn(cat)
                heading.text = cat
                subtitle.text = "${list.size} channels"
                addGrid(list)
            }
        }
    }

    private fun addMessage(text: String) {
        content.addView(TextView(this).apply {
            this.text = text
            textSize = 18f
            setTextColor(MUTED)
            setPadding(0, dp(24), 0, 0)
        })
    }

    private fun addRow(title: String, list: List<Channel>) {
        if (list.isEmpty()) return
        content.addView(TextView(this).apply {
            text = title
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(4), dp(8), 0, dp(10))
        })
        val inner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        list.forEachIndexed { i, ch ->
            inner.addView(makeTile(ch, list, i), LinearLayout.LayoutParams(tileW, WRAP).apply { rightMargin = dp(14) })
        }
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(inner)
        }
        content.addView(scroller, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(18) })
    }

    private fun addGrid(list: List<Channel>) {
        val shown = list.take(300)
        shown.chunked(cols).forEachIndexed { r, chunk ->
            val rowLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEachIndexed { c, ch ->
                rowLayout.addView(
                    makeTile(ch, list, r * cols + c),
                    LinearLayout.LayoutParams(tileW, WRAP).apply { rightMargin = dp(14) }
                )
            }
            content.addView(rowLayout, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(14) })
        }
        if (list.size > shown.size) {
            addMessage("Showing the first ${shown.size} of ${list.size}. Use Search to find a channel.")
        }
    }

    private fun initialsOf(name: String): String {
        val letters = name.split(" ").filter { it.isNotBlank() }.take(2).map { it.first().uppercaseChar() }
        return if (letters.isEmpty()) "TV" else letters.joinToString("")
    }

    private fun makeTile(ch: Channel, list: List<Channel>, index: Int): View {
        val color = Color.parseColor(tilePalette[(ch.name.hashCode() and 0x7fffffff) % tilePalette.size])

        val logoBox = FrameLayout(this).apply { background = rounded(color, 14) }
        val initials = TextView(this).apply {
            text = initialsOf(ch.name)
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        logoBox.addView(initials, FrameLayout.LayoutParams(MATCH, MATCH))
        if (ch.logo.startsWith("http")) {
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }
            logoBox.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
            Images.load(ch.logo, img) { ok -> initials.visibility = if (ok) View.GONE else View.VISIBLE }
        }
        val star = TextView(this).apply {
            text = "★"
            textSize = 22f
            setTextColor(STAR)
            visibility = if (favSet.contains(ch.url)) View.VISIBLE else View.GONE
        }
        logoBox.addView(
            star,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(4), dp(10), 0) }
        )

        val nameView = TextView(this).apply {
            text = ch.name
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(4), 0, dp(4), 0)
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            tag = TileHolder(ch, star)
            background = rounded(PANEL, 16)
            setPadding(dp(6), dp(6), dp(6), dp(10))
            addView(logoBox, LinearLayout.LayoutParams(MATCH, (tileW * 0.55f).toInt()))
            addView(nameView, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        }
        box.setOnFocusChangeListener { v, f ->
            v.background = if (f) rounded(CHIP, 16, ACCENT, 3) else rounded(PANEL, 16)
        }
        box.setOnClickListener { play(list, index) }
        box.setOnLongClickListener { v ->
            toggleFavorite(v)
            true
        }
        if (firstTile == null) firstTile = box
        return box
    }

    private fun toggleFavorite(v: View) {
        val holder = v.tag as? TileHolder ?: return
        val on = prefs.toggleFavorite(holder.channel.url)
        holder.star.visibility = if (on) View.VISIBLE else View.GONE
        Toast.makeText(this, if (on) "Added to Favorites" else "Removed from Favorites", Toast.LENGTH_SHORT).show()
        if (!on && section == "favs" && query.isEmpty()) {
            showSection()
            firstTile?.requestFocus()
        }
    }

    private fun play(list: List<Channel>, index: Int) {
        // Send the player a window of channels around the one you picked.
        val from = (index - 200).coerceAtLeast(0)
        val to = (from + 400).coerceAtMost(list.size)
        val window = list.subList(from, to)
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putStringArrayListExtra("names", ArrayList(window.map { it.name }))
                .putStringArrayListExtra("urls", ArrayList(window.map { it.url }))
                .putExtra("index", index - from)
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val f = currentFocus
            if (f != null && f.tag is TileHolder) {
                toggleFavorite(f)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // Back goes to Home first, then leaves the app.
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (section != "home" || query.isNotEmpty()) {
            section = "home"
            query = ""
            refreshNav()
            showSection()
            firstTile?.requestFocus()
        } else {
            super.onBackPressed()
        }
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
