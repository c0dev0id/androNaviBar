package de.codevoid.andronavibar

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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

    /** Holds the live views for each weather panel. */
    private data class PanelViews(
        val emoji: ImageView,
        val temp: TextView,
        val arrow: TextView,
        val speed: TextView,
        val precip: TextView
    )
    private var panels: Array<PanelViews>? = null

    // ── Compass ───────────────────────────────────────────────────────────────

    /** Last device azimuth in degrees (0=N, 90=E, 180=S, 270=W), updated by sensor. */
    private var deviceAzimuth = 0f
    private var sensorManager: SensorManager? = null

    private val rotMatrix  = FloatArray(9)
    private val orientation = FloatArray(3)

    private val compassListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
            SensorManager.getOrientation(rotMatrix, orientation)
            deviceAzimuth = ((Math.toDegrees(orientation[0].toDouble()).toFloat() + 360) % 360)
            updateArrowRotations()
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    private fun updateArrowRotations() {
        val data = lastWeather ?: return
        val panelData = listOf(data.now, data.plus3h, data.plus6h)
        panels?.forEachIndexed { i, pv ->
            pv.arrow.rotation = (panelData[i].windDir - deviceAzimuth + 360) % 360
        }
    }

    // ── Timers ────────────────────────────────────────────────────────────────

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

    // ── PaneContent ───────────────────────────────────────────────────────────

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

        // ── Weather — 3-panel row: Now / +3h / +6h ────────────────────────────
        //
        // Each panel: emoji + temp + wind arrow (compass-relative) + wind speed + label.
        // All data views start invisible; made visible when weather arrives.

        val emojiSize  = res.dpToPx(88)
        val arrowSize  = res.dpToPx(40)
        val panelLabels = listOf("Now", "+3h", "+6h")
        val cachedPanels = lastWeather?.let { listOf(it.now, it.plus3h, it.plus6h) }

        val weatherRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = res.dpToPx(24)
            }
        }

        val builtPanels = mutableListOf<PanelViews>()
        for (i in 0..2) {
            val p = cachedPanels?.getOrNull(i)

            val panel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }

            val emoji = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(emojiSize, emojiSize).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                if (p != null) setImageDrawable(context.loadWeatherSvg(pictocodeIcon(p.pictocode), emojiSize))
                else visibility = View.INVISIBLE
            }

            val temp = TextView(context).apply {
                textSize = 56f
                setTextColor(context.getColor(R.color.text_primary))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = res.dpToPx(8)
                }
                if (p != null) text = "${p.tempC.toInt()}°" else visibility = View.INVISIBLE
            }

            // Wind row: rotating arrow + speed label side by side
            val windRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = res.dpToPx(8)
                }
            }

            val arrow = TextView(context).apply {
                text = "↑"
                textSize = 26f
                includeFontPadding = false
                setTextColor(context.getColor(R.color.text_primary))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(arrowSize, arrowSize)
                if (p != null) rotation = (p.windDir - deviceAzimuth + 360) % 360
                else visibility = View.INVISIBLE
            }

            val speed = TextView(context).apply {
                textSize = 22f
                setTextColor(context.getColor(R.color.text_secondary))
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                    marginStart = res.dpToPx(6)
                }
                if (p != null) text = "${p.windSpeed.toInt()} km/h" else visibility = View.INVISIBLE
            }

            windRow.addView(arrow)
            windRow.addView(speed)

            val precip = TextView(context).apply {
                textSize = 22f
                setTextColor(context.getColor(R.color.text_secondary))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = res.dpToPx(4)
                }
                val p = cachedPanels?.getOrNull(i)
                if (p != null) text = "${p.precipProb}% rain" else visibility = View.INVISIBLE
            }

            panel.addView(emoji)
            panel.addView(temp)
            panel.addView(windRow)
            panel.addView(precip)
            panel.addView(TextView(context).apply {
                text = panelLabels[i]
                textSize = 22f
                setTextColor(context.getColor(R.color.text_secondary))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = res.dpToPx(6)
                }
            })

            weatherRow.addView(panel)
            builtPanels.add(PanelViews(emoji, temp, arrow, speed, precip))
        }

        panels = builtPanels.toTypedArray()
        column.addView(weatherRow)

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

        // ── Compass sensor ────────────────────────────────────────────────────

        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotSensor != null) {
            sensorManager = sm
            sm.registerListener(compassListener, rotSensor, SensorManager.SENSOR_DELAY_UI)
        }

        clockHandler.post(clockRunnable)
        weatherHandler.post(weatherRunnable)
        container.addView(root)
    }

    override fun unload() {
        sensorManager?.unregisterListener(compassListener)
        sensorManager = null
        clockHandler.removeCallbacks(clockRunnable)
        weatherHandler.removeCallbacks(weatherRunnable)
        scope.coroutineContext.cancelChildren()
        val root = rootView ?: return
        rootView = null
        gearButton = null
        clockView = null
        panels = null
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
        lastWeather = data
        val sizePx = context.resources.dpToPx(88)
        val panelData = listOf(data.now, data.plus3h, data.plus6h)
        panels?.forEachIndexed { i, pv ->
            val p = panelData[i]
            pv.emoji.setImageDrawable(context.loadWeatherSvg(pictocodeIcon(p.pictocode), sizePx))
            pv.emoji.visibility = View.VISIBLE
            pv.temp.text = "${p.tempC.toInt()}°"
            pv.temp.visibility = View.VISIBLE
            pv.arrow.rotation = (p.windDir - deviceAzimuth + 360) % 360
            pv.arrow.visibility = View.VISIBLE
            pv.speed.text = "${p.windSpeed.toInt()} km/h"
            pv.speed.visibility = View.VISIBLE
            pv.precip.text = "${p.precipProb}% rain"
            pv.precip.visibility = View.VISIBLE
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

    private data class WeatherPanel(
        val pictocode: Int,
        val tempC: Double,
        val windDir: Int,
        val windSpeed: Double,
        val precipProb: Int
    )

    private data class WeatherData(
        val now:    WeatherPanel,
        val plus3h: WeatherPanel,
        val plus6h: WeatherPanel
    )

    private fun parseWeather(json: String): WeatherData? = try {
        val root    = JSONObject(json)
        val current = root.getJSONObject("data_current")
        val data1h  = root.getJSONObject("data_1h")
        val temps   = data1h.getJSONArray("temperature")
        val pictos  = data1h.getJSONArray("pictocode")
        // Wind and precip arrays are optional — gracefully absent in some API response variants.
        val wdirs   = data1h.optJSONArray("winddirection")
        val wspds   = data1h.optJSONArray("windspeed")
        val pprobs  = data1h.optJSONArray("precipitationprobability")
        WeatherData(
            now    = WeatherPanel(
                pictocode = current.getInt("pictocode"),
                tempC     = current.getDouble("temperature"),
                windDir   = current.optInt("winddirection", 0),
                windSpeed = current.optDouble("windspeed", 0.0),
                precipProb = current.optInt("precipitationprobability", 0)
            ),
            plus3h = WeatherPanel(
                pictos.getInt(3), temps.getDouble(3),
                wdirs?.optInt(3, 0) ?: 0, wspds?.optDouble(3, 0.0) ?: 0.0,
                pprobs?.optInt(3, 0) ?: 0
            ),
            plus6h = WeatherPanel(
                pictos.getInt(6), temps.getDouble(6),
                wdirs?.optInt(6, 0) ?: 0, wspds?.optDouble(6, 0.0) ?: 0.0,
                pprobs?.optInt(6, 0) ?: 0
            )
        )
    } catch (_: Exception) { null }

    private fun pictocodeIcon(code: Int): String = when (code) {
        1          -> "sun-svgrepo-com.svg"
        2          -> "lightcloud-svgrepo-com.svg"
        3          -> "partlycloud-svgrepo-com.svg"
        4          -> "cloud-svgrepo-com.svg"
        5, 6       -> "fog-svgrepo-com.svg"
        7          -> "lightrainsun-svgrepo-com.svg"
        8, 9       -> "lightrain-svgrepo-com.svg"
        10, 11     -> "rain-svgrepo-com.svg"
        12, 13     -> "sleet-svgrepo-com.svg"
        14, 15, 16 -> "snow-svgrepo-com.svg"
        17         -> "rainthunder-svgrepo-com.svg"
        else       -> "sun-svgrepo-com.svg"
    }

    companion object {
        private const val WEATHER_CHECK_INTERVAL_MS = 60_000L
        private const val WEATHER_MIN_AGE_MS        = 15 * 60 * 1000L
        private const val WEATHER_MIN_DISTANCE_M    = 500f

        /** Persists across pane re-creation within the same app session. */
        private var lastFetchTime: Long      = 0L
        private var lastFetchLoc:  Location? = null
        private var lastWeather:   WeatherData? = null
    }
}
