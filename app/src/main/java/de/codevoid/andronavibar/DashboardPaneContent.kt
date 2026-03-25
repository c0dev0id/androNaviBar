package de.codevoid.andronavibar

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.gms.location.LocationServices
import de.codevoid.andronavibar.ui.FocusableButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dashboard pane — shown when the fixed Dashboard button at the top of the
 * column is activated. Displays the current time, date, and weather. A gear
 * icon in the top-right corner opens the global config pane.
 */
class DashboardPaneContent(
    private val context: Context
) : PaneContent {

    /** Invoked when the user activates the gear icon. */
    var onConfigRequested: (() -> Unit)? = null

    private var rootView: FrameLayout? = null
    private var gearButton: FocusableButton? = null
    private var clockView: TextView? = null
    private var weatherEmojiView: ImageView? = null
    private var weatherTempView: TextView? = null
    private var weatherCondView: TextView? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            clockView?.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            clockHandler.postDelayed(this, 30_000)
        }
    }

    private val weatherHandler = Handler(Looper.getMainLooper())
    private val weatherRunnable = object : Runnable {
        override fun run() {
            checkAndFetch()
            weatherHandler.postDelayed(this, WEATHER_CHECK_INTERVAL_MS)
        }
    }

    override fun load(onReady: () -> Unit) { onReady() }

    override fun show(container: ViewGroup) {
        val res = context.resources

        val root = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        rootView = root

        // ── Clock + date + weather group — optically centred at ~40% from top ──
        //
        // Weighted spacers (top:2 / bottom:3) lift the group to the optical centre.

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }

        // Top spacer — weight 2
        column.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 2f)
        })

        val clock = TextView(context).apply {
            textSize = 88f
            setTextColor(context.getColor(R.color.text_primary))
            gravity = Gravity.CENTER
            letterSpacing = -0.02f
        }
        clockView = clock
        column.addView(clock)

        // Orange accent rule beneath the time
        column.addView(View(context).apply {
            setBackgroundColor(context.getColor(R.color.colorPrimary))
            layoutParams = LinearLayout.LayoutParams(res.dpToPx(72), res.dpToPx(3)).apply {
                topMargin    = res.dpToPx(10)
                bottomMargin = res.dpToPx(10)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        })

        column.addView(TextView(context).apply {
            text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())
            textSize = 22f
            setTextColor(context.getColor(R.color.text_secondary))
            gravity = Gravity.CENTER
            letterSpacing = 0.04f
        })

        // ── Weather ───────────────────────────────────────────────────────────
        // Emoji is rendered via renderEmojiDrawable (bitmap path) to avoid the
        // text-presentation fallback that shows as a white square on some devices.
        // All three views start invisible; made visible when data arrives.

        val emojiSize = res.dpToPx(72)
        val weatherEmoji = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(emojiSize, emojiSize).apply {
                topMargin = res.dpToPx(20)
                gravity   = Gravity.CENTER_HORIZONTAL
            }
            if (lastPictocode >= 0) {
                setImageDrawable(context.renderEmojiDrawable(pictocodeEmoji(lastPictocode)))
            } else {
                visibility = View.INVISIBLE
            }
        }
        weatherEmojiView = weatherEmoji
        column.addView(weatherEmoji)

        val weatherTemp = TextView(context).apply {
            textSize = 48f
            setTextColor(context.getColor(R.color.text_primary))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = res.dpToPx(8)
            }
            if (lastTempText.isNotEmpty()) text = lastTempText else visibility = View.INVISIBLE
        }
        weatherTempView = weatherTemp
        column.addView(weatherTemp)

        val weatherCond = TextView(context).apply {
            textSize = 28f
            setTextColor(context.getColor(R.color.text_secondary))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = res.dpToPx(4)
            }
            if (lastCondText.isNotEmpty()) text = lastCondText else visibility = View.INVISIBLE
        }
        weatherCondView = weatherCond
        column.addView(weatherCond)

        // Bottom spacer — weight 3
        column.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 3f)
        })

        root.addView(column)

        // ── Gear icon — top-right corner ──────────────────────────────────────

        val gear = FocusableButton(context).apply {
            text = "⚙"
            textSize = 20f
            setTextColor(context.getColor(R.color.text_secondary))
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                Color.TRANSPARENT
            )
            val size = res.dpToPx(56)
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END).apply {
                topMargin = res.dpToPx(12)
                marginEnd  = res.dpToPx(12)
            }
            setOnClickListener { onConfigRequested?.invoke() }
        }
        gearButton = gear
        root.addView(gear)

        clockHandler.post(clockRunnable)
        weatherHandler.post(weatherRunnable)
        container.addView(root)
    }

    override fun unload() {
        clockHandler.removeCallbacks(clockRunnable)
        weatherHandler.removeCallbacks(weatherRunnable)
        scope.coroutineContext.cancelChildren()
        val root = rootView ?: return
        rootView = null
        gearButton = null
        clockView = null
        weatherEmojiView = null
        weatherTempView = null
        weatherCondView = null
        (root.parent as? ViewGroup)?.removeView(root)
    }

    /** CONFIRM activates the gear; all other keys fall through to MainActivity. */
    fun handleKey(keyCode: Int): Boolean = when (keyCode) {
        66 -> { onConfigRequested?.invoke(); true }
        else -> false
    }

    fun setInitialFocus() { gearButton?.isFocusedButton = true }
    fun clearFocus()       { gearButton?.isFocusedButton = false }

    // ── Weather ───────────────────────────────────────────────────────────────

    /**
     * Called every [WEATHER_CHECK_INTERVAL_MS]. Fetches from the API only when:
     * - at least [WEATHER_MIN_AGE_MS] has elapsed since the last fetch, OR
     * - the device has moved more than [WEATHER_MIN_DISTANCE_M] since the last fetch.
     */
    @SuppressLint("MissingPermission")
    private fun checkAndFetch() {
        if (BuildConfig.METEOBLUE_KEY.isEmpty()) return
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return

        LocationServices.getFusedLocationProviderClient(context)
            .lastLocation
            .addOnSuccessListener { location ->
                location ?: return@addOnSuccessListener

                val now = System.currentTimeMillis()
                val ageExpired = (now - lastFetchTime) >= WEATHER_MIN_AGE_MS
                val movedFar = lastFetchLoc?.let { prev ->
                    val results = FloatArray(1)
                    Location.distanceBetween(prev.latitude, prev.longitude,
                        location.latitude, location.longitude, results)
                    results[0] >= WEATHER_MIN_DISTANCE_M
                } ?: true

                if (!ageExpired && !movedFar) return@addOnSuccessListener

                lastFetchTime = now
                lastFetchLoc  = location

                scope.launch {
                    val data = fetchWeather(location.latitude, location.longitude)
                        ?: return@launch
                    withContext(Dispatchers.Main) { applyWeather(data) }
                }
            }
    }

    private fun applyWeather(data: WeatherData) {
        val tempText = "${data.tempC.toInt()}°C"
        val condText = "↑${data.maxC.toInt()}°  ↓${data.minC.toInt()}°"
        lastPictocode = data.pictocode
        lastTempText  = tempText
        lastCondText  = condText
        weatherEmojiView?.apply {
            setImageDrawable(context.renderEmojiDrawable(pictocodeEmoji(data.pictocode)))
            visibility = View.VISIBLE
        }
        weatherTempView?.apply { text = tempText; visibility = View.VISIBLE }
        weatherCondView?.apply { text = condText; visibility = View.VISIBLE }
    }

    private suspend fun fetchWeather(lat: Double, lon: Double): WeatherData? =
        withContext(Dispatchers.IO) {
            try {
                val url = "${BuildConfig.METEOBLUE_URI}?apikey=${BuildConfig.METEOBLUE_KEY}" +
                        "&lat=$lat&lon=$lon&format=json"
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout    = 15_000
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                parseWeather(body)
            } catch (_: Exception) { null }
        }

    private data class WeatherData(
        val tempC: Double,
        val pictocode: Int,
        val minC: Double,
        val maxC: Double
    )

    private fun parseWeather(json: String): WeatherData? = try {
        val root    = JSONObject(json)
        val current = root.getJSONObject("data_current")
        val temps1h = root.getJSONObject("data_1h").getJSONArray("temperature")
        val hourlyTemps = (0 until minOf(24, temps1h.length())).map { temps1h.getDouble(it) }
        WeatherData(
            tempC     = current.getDouble("temperature"),
            pictocode = current.getInt("pictocode"),
            minC      = hourlyTemps.minOrNull() ?: current.getDouble("temperature"),
            maxC      = hourlyTemps.maxOrNull() ?: current.getDouble("temperature")
        )
    } catch (_: Exception) { null }

    private fun pictocodeEmoji(code: Int): String = when (code) {
        1          -> "☀"
        2          -> "🌤"
        3          -> "⛅"
        4          -> "☁"
        5, 6       -> "🌫"
        7          -> "🌦"
        8, 9       -> "🌧"
        10, 11     -> "🌧"
        12, 13     -> "🌨"
        14, 15, 16 -> "❄"
        17         -> "🌧"
        else       -> "—"
    }

    companion object {
        private const val WEATHER_CHECK_INTERVAL_MS = 60_000L
        private const val WEATHER_MIN_AGE_MS        = 15 * 60 * 1000L
        private const val WEATHER_MIN_DISTANCE_M    = 500f

        /** Persists across pane re-creation within the same app session. */
        private var lastFetchTime: Long      = 0L
        private var lastFetchLoc:  Location? = null
        private var lastPictocode: Int       = -1
        private var lastTempText:  String    = ""
        private var lastCondText:  String    = ""
    }
}
