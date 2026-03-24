package de.codevoid.andronavibar

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
            requestWeather()
            weatherHandler.postDelayed(this, 30 * 60_000L)
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
            letterSpacing = -0.02f   // tighten the wide numerals slightly
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
            letterSpacing = 0.04f   // slightly open tracking for the date label
        })

        // Weather — temperature (large) + condition/high-low (small)
        // Both start invisible; made visible once data arrives.

        val weatherTemp = TextView(context).apply {
            textSize = 36f
            setTextColor(context.getColor(R.color.text_primary))
            gravity = Gravity.CENTER
            visibility = View.INVISIBLE
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = res.dpToPx(16)
            }
        }
        weatherTempView = weatherTemp
        column.addView(weatherTemp)

        val weatherCond = TextView(context).apply {
            textSize = 16f
            setTextColor(context.getColor(R.color.text_secondary))
            gravity = Gravity.CENTER
            visibility = View.INVISIBLE
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = res.dpToPx(4)
            }
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

    @SuppressLint("MissingPermission")
    private fun requestWeather() {
        if (BuildConfig.METEOBLUE_KEY.isEmpty()) return
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return

        LocationServices.getFusedLocationProviderClient(context)
            .lastLocation
            .addOnSuccessListener { location ->
                location ?: return@addOnSuccessListener
                scope.launch {
                    val data = fetchWeather(location.latitude, location.longitude)
                        ?: return@launch
                    withContext(Dispatchers.Main) { applyWeather(data) }
                }
            }
    }

    private fun applyWeather(data: WeatherData) {
        weatherTempView?.apply {
            text = "${data.tempC.toInt()}°C"
            visibility = View.VISIBLE
        }
        weatherCondView?.apply {
            text = "${pictocodeEmoji(data.pictocode)}  ↑${data.maxC.toInt()}°  ↓${data.minC.toInt()}°"
            visibility = View.VISIBLE
        }
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
        val day     = root.getJSONObject("data_day")
        WeatherData(
            tempC     = current.getDouble("temperature"),
            pictocode = current.getInt("pictocode"),
            minC      = day.getJSONArray("temperature_min").getDouble(0),
            maxC      = day.getJSONArray("temperature_max").getDouble(0)
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
}
