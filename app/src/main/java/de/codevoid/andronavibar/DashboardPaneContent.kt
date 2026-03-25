package de.codevoid.andronavibar

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
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
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieDrawable
import com.google.android.gms.location.LocationServices
import de.codevoid.andronavibar.ui.FocusableButton
import de.codevoid.andronavibar.ui.RainspotView
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
import java.util.TimeZone

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
        val icon: LottieAnimationView,
        val temp: TextView,
        val arrow: TextView,
        val speed: TextView,
        val precip: TextView,
        val rainspot: RainspotView
    )
    private var panels: Array<PanelViews>? = null
    private var panelLayouts: Array<LinearLayout>? = null
    private var focusedPanelIndex = -1   // -1 = no panel focused
    private var detailDialog: Dialog? = null

    /** True while a weather detail dialog is visible; used by MainActivity to bypass
     *  the isWindowFocused guard so remote keys still reach handleKey. */
    val hasModalDialog: Boolean get() = detailDialog != null

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
        val ps   = panels ?: return
        if (ps.size < 3) return
        ps[0].arrow.rotation = (data.now.windDir    - deviceAzimuth + 360) % 360
        ps[1].arrow.rotation = (data.plus3h.windDir - deviceAzimuth + 360) % 360
        ps[2].arrow.rotation = (data.plus6h.windDir - deviceAzimuth + 360) % 360
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

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }

        // Top spacer — equal weight with bottom spacer for symmetric margins
        column.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
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
        val cachedPanels = lastWeather?.let { listOf(it.now, it.plus3h, it.plus6h) }

        val weatherRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = res.dpToPx(24)
            }
        }

        val builtPanels   = mutableListOf<PanelViews>()
        val builtLayouts  = mutableListOf<LinearLayout>()
        for (i in 0..2) {
            val p = cachedPanels?.getOrNull(i)

            val panel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }

            val icon = LottieAnimationView(context).apply {
                layoutParams = LinearLayout.LayoutParams(emojiSize, emojiSize).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                repeatCount = LottieDrawable.INFINITE
                if (p != null) { setAnimation("lottie/${pictocodeAsset(p.pictocode)}"); playAnimation() }
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
                if (p != null) text = precipText(p.precipProb, p.precipMm) else visibility = View.INVISIBLE
            }

            val rainspotView = RainspotView(context).apply {
                val m = res.dpToPx(28)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin    = res.dpToPx(8)
                    marginStart  = m
                    marginEnd    = m
                }
                if (p != null) setData(p.rainspot) else visibility = View.INVISIBLE
            }

            panel.addView(icon)
            panel.addView(temp)
            panel.addView(windRow)
            panel.addView(precip)
            panel.addView(rainspotView)
            panel.addView(TextView(context).apply {
                text = PANEL_LABELS[i]
                textSize = 22f
                setTextColor(context.getColor(R.color.text_secondary))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = res.dpToPx(6)
                }
            })

            val panelIndex = i
            panel.isClickable = true
            panel.setOnClickListener {
                val data = lastWeather ?: return@setOnClickListener
                val wp = when (panelIndex) { 0 -> data.now; 1 -> data.plus3h; else -> data.plus6h }
                showWeatherDetail(wp, PANEL_LABELS[panelIndex])
            }

            weatherRow.addView(panel)
            builtPanels.add(PanelViews(icon, temp, arrow, speed, precip, rainspotView))
            builtLayouts.add(panel)
        }

        panels      = builtPanels.toTypedArray()
        panelLayouts = builtLayouts.toTypedArray()
        column.addView(weatherRow)

        // Bottom spacer — equal weight with top spacer for symmetric margins
        column.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
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
        detailDialog?.dismiss()
        detailDialog = null
        sensorManager?.unregisterListener(compassListener)
        sensorManager = null
        clockHandler.removeCallbacks(clockRunnable)
        weatherHandler.removeCallbacks(weatherRunnable)
        scope.coroutineContext.cancelChildren()
        val root = rootView ?: return
        rootView = null
        gearButton = null
        clockView = null
        panels?.forEach { it.icon.cancelAnimation() }
        panels = null
        panelLayouts = null
        focusedPanelIndex = -1
        (root.parent as? ViewGroup)?.removeView(root)
    }

    /**
     * Key routing while the dashboard pane owns focus.
     *
     * - Detail dialog is modal: swallows all keys; CONFIRM or 111 close it.
     * - With a panel focused: LEFT/RIGHT move between panels (RIGHT at edge returns
     *   false so MainActivity falls back to the button column); CONFIRM opens the popup.
     * - No panel focused: CONFIRM opens the config pane (gear).
     */
    fun handleKey(keyCode: Int): Boolean {
        detailDialog?.let {
            if (keyCode == 66 || keyCode == 111) it.dismiss()
            return true
        }

        if (focusedPanelIndex >= 0) {
            return when (keyCode) {
                21 -> { if (focusedPanelIndex > 0) movePanelFocus(focusedPanelIndex - 1); true }
                22 -> if (focusedPanelIndex < 2) { movePanelFocus(focusedPanelIndex + 1); true } else false
                66 -> {
                    val data = lastWeather ?: return false
                    val wp = when (focusedPanelIndex) { 0 -> data.now; 1 -> data.plus3h; else -> data.plus6h }
                    showWeatherDetail(wp, PANEL_LABELS[focusedPanelIndex])
                    true
                }
                else -> false
            }
        }

        if (keyCode == 66) { onConfigRequested?.invoke(); return true }
        return false
    }

    /** Called by MainActivity when LEFT is pressed on the button column to enter this pane. */
    fun setInitialFocus() {
        focusedPanelIndex = 2   // start at +6h (rightmost panel)
        updatePanelFocus()
        gearButton?.isFocusedButton = false
    }

    fun clearFocus() {
        focusedPanelIndex = -1
        updatePanelFocus()
        gearButton?.isFocusedButton = false
    }

    private fun movePanelFocus(idx: Int) {
        focusedPanelIndex = idx
        updatePanelFocus()
    }

    private fun updatePanelFocus() {
        val layouts  = panelLayouts ?: return
        val res      = context.resources
        val cornerR  = res.dpToPx(16).toFloat()
        val strokeW  = res.dpToPx(3)
        for (i in layouts.indices) {
            layouts[i].background = if (i == focusedPanelIndex) {
                GradientDrawable().apply {
                    setColor(Color.argb(20, 255, 255, 255))
                    setStroke(strokeW, context.getColor(R.color.focus_ring))
                    cornerRadius = cornerR
                }
            } else null
        }
    }

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
        val panelData = listOf(data.now, data.plus3h, data.plus6h)
        panels?.forEachIndexed { i, pv ->
            val p = panelData[i]
            pv.icon.setAnimation("lottie/${pictocodeAsset(p.pictocode)}")
            pv.icon.repeatCount = LottieDrawable.INFINITE
            pv.icon.playAnimation()
            pv.icon.visibility = View.VISIBLE
            pv.temp.text = "${p.tempC.toInt()}°"
            pv.temp.visibility = View.VISIBLE
            pv.arrow.rotation = (p.windDir - deviceAzimuth + 360) % 360
            pv.arrow.visibility = View.VISIBLE
            pv.speed.text = "${p.windSpeed.toInt()} km/h"
            pv.speed.visibility = View.VISIBLE
            pv.precip.text = precipText(p.precipProb, p.precipMm)
            pv.precip.visibility = View.VISIBLE
            pv.rainspot.setData(p.rainspot)
            pv.rainspot.visibility = View.VISIBLE
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
        val precipProb: Int,
        val feltTempC: Double,
        val humidity: Int,
        val uvIndex: Int,
        val precipMm: Double,
        val pressureHpa: Double,
        val rainspot: String
    )

    private data class WeatherData(
        val now:    WeatherPanel,
        val plus3h: WeatherPanel,
        val plus6h: WeatherPanel
    )

    private fun parseWeather(json: String): WeatherData? {
        lastLocationName = null
        return try {
            val root   = JSONObject(json)
            val meta   = root.optJSONObject("metadata")
            val data1h = root.getJSONObject("data_1h")
            val times  = data1h.getJSONArray("time")

            // The time[] entries are local times; utc_timeoffset converts them to UTC.
            // Parse time[0] as if it were UTC then subtract the offset to get the true
            // UTC start, then find which index corresponds to the current hour.
            val utcOffsetMs = ((meta?.optDouble("utc_timeoffset", 0.0) ?: 0.0) * 3_600_000L).toLong()
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val startUtcMs = (sdf.parse(times.getString(0))?.time ?: return null) - utcOffsetMs
            val nowIdx = ((System.currentTimeMillis() - startUtcMs) / 3_600_000L)
                .toInt().coerceIn(0, times.length() - 7)

            val temps  = data1h.getJSONArray("temperature")
            val pictos = data1h.getJSONArray("pictocode")
            // All sub-arrays are optional — gracefully absent in some API response variants.
            val wdirs  = data1h.optJSONArray("winddirection")
            val wspds  = data1h.optJSONArray("windspeed")
            val pprobs = data1h.optJSONArray("precipitation_probability")
            val felts  = data1h.optJSONArray("felttemperature")
            val humids = data1h.optJSONArray("relativehumidity")
            val uvs    = data1h.optJSONArray("uvindex")
            val precips   = data1h.optJSONArray("precipitation")
            val press     = data1h.optJSONArray("sealevelpressure")
            val rainspots = data1h.optJSONArray("rainspot")

            fun makePanel(idx: Int) = WeatherPanel(
                pictocode   = pictos.getInt(idx),
                tempC       = temps.getDouble(idx),
                windDir     = wdirs?.optInt(idx, 0) ?: 0,
                windSpeed   = (wspds?.optDouble(idx, 0.0) ?: 0.0) * 3.6,  // API: m/s → km/h
                precipProb  = pprobs?.optInt(idx, 0) ?: 0,
                feltTempC   = felts?.optDouble(idx, temps.getDouble(idx)) ?: temps.getDouble(idx),
                humidity    = humids?.optInt(idx, 0) ?: 0,
                uvIndex     = uvs?.optInt(idx, 0) ?: 0,
                precipMm    = precips?.optDouble(idx, 0.0) ?: 0.0,
                pressureHpa = press?.optDouble(idx, 0.0) ?: 0.0,
                rainspot    = rainspots?.optString(idx, "") ?: ""
            )

            lastLocationName = meta?.optString("name")?.takeIf { it.isNotBlank() }
            WeatherData(now = makePanel(nowIdx), plus3h = makePanel(nowIdx + 3), plus6h = makePanel(nowIdx + 6))
        } catch (_: Exception) { null }
    }

    private fun precipText(prob: Int, mm: Double) =
        "$prob%  ·  ${"%.1f".format(mm)} mm"

    private fun windCardinal(deg: Int): String = when ((deg + 22) / 45 % 8) {
        0 -> "N"; 1 -> "NE"; 2 -> "E"; 3 -> "SE"
        4 -> "S"; 5 -> "SW"; 6 -> "W"
        else -> "NW"
    }

    private fun showWeatherDetail(panel: WeatherPanel, label: String) {
        detailDialog?.dismiss()

        val res    = context.resources
        val p      = res.dpToPx(24)
        val iconSz = res.dpToPx(100)
        val arrSz  = res.dpToPx(52)

        val dialog = Dialog(context)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val content = LinearLayout(context).apply {
            orientation  = LinearLayout.VERTICAL
            gravity      = Gravity.CENTER_HORIZONTAL
            minimumWidth = res.dpToPx(600)
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.surface_card))
                cornerRadius = res.dpToPx(20).toFloat()
            }
        }

        // Icon
        content.addView(LottieAnimationView(context).apply {
            layoutParams = LinearLayout.LayoutParams(iconSz, iconSz).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = res.dpToPx(8)
            }
            repeatCount = LottieDrawable.INFINITE
            setAnimation("lottie/${pictocodeAsset(panel.pictocode)}")
            playAnimation()
        })

        // Time label
        content.addView(TextView(context).apply {
            text = label
            textSize = 28f
            setTextColor(context.getColor(R.color.text_secondary))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        })

        // Temperature
        content.addView(TextView(context).apply {
            text = "${panel.tempC.toInt()}°C"
            textSize = 70f
            setTextColor(context.getColor(R.color.text_primary))
            gravity = Gravity.CENTER
            letterSpacing = -0.02f
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        })

        // Wind row: rotating arrow + speed + cardinal
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = res.dpToPx(8)
            }
            addView(TextView(context).apply {
                text = "↑"
                textSize = 40f
                includeFontPadding = false
                setTextColor(context.getColor(R.color.text_primary))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(arrSz, arrSz)
                rotation = (panel.windDir - deviceAzimuth + 360) % 360
            })
            addView(TextView(context).apply {
                text = "${panel.windSpeed.toInt()} km/h  ${windCardinal(panel.windDir)}"
                textSize = 36f
                setTextColor(context.getColor(R.color.text_primary))
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                    marginStart = res.dpToPx(8)
                }
            })
        })

        fun detailRow(text: String, topGap: Int = 8) {
            content.addView(TextView(context).apply {
                this.text = text
                textSize = 28f
                setTextColor(context.getColor(R.color.text_secondary))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = res.dpToPx(topGap)
                }
            })
        }

        // Precipitation probability + actual mm
        detailRow("${panel.precipProb}% rain  ·  ${panel.precipMm} mm", topGap = 8)

        // Feels like + humidity
        detailRow("Feels ${panel.feltTempC.toInt()}°  ·  Humidity ${panel.humidity}%", topGap = 4)

        // UV index + pressure
        detailRow("UV ${panel.uvIndex}  ·  ${panel.pressureHpa.toInt()} hPa", topGap = 4)

        // Location name + last refresh time
        val footerParts = buildList {
            val loc = lastLocationName
                ?: lastFetchLoc?.let { "%.4f, %.4f".format(it.latitude, it.longitude) }
            loc?.let { add(it) }
            if (lastFetchTime > 0L)
                add("Updated ${SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(lastFetchTime))}")
        }
        if (footerParts.isNotEmpty()) {
            content.addView(TextView(context).apply {
                text = footerParts.joinToString("  ·  ")
                textSize = 22f
                setTextColor(context.getColor(R.color.text_secondary))
                gravity = Gravity.CENTER
                alpha = 0.6f
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = res.dpToPx(12)
                }
            })
        }

        // Close button
        content.addView(FocusableButton(context).apply {
            text = "Close"
            textSize = 28f
            isFocusedButton = true
            layoutParams = LinearLayout.LayoutParams(MATCH, res.dpToPx(80)).apply {
                topMargin = res.dpToPx(24)
            }
            setOnClickListener { dialog.dismiss() }
        })

        // Wrap in a FrameLayout so the content card centers vertically inside the
        // bounded window height, keeping it clear of the screen edges.
        val wrapper = FrameLayout(context)
        wrapper.addView(content, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_VERTICAL))
        dialog.setContentView(wrapper)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val edgeMargin = res.dpToPx(40)
            setLayout(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                context.resources.displayMetrics.heightPixels - edgeMargin * 2
            )
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.7f)
        }
        dialog.setOnDismissListener { detailDialog = null }
        dialog.show()
        detailDialog = dialog
    }

    private fun pictocodeAsset(code: Int): String = when (code) {
        1, 2       -> "clear-day.json"
        3          -> "partly-cloudy-day.json"
        4          -> "overcast-day.json"
        5, 30      -> "fog-day.json"
        6, 31      -> "fog.json"
        7, 21      -> "partly-cloudy-day-rain.json"
        8, 22      -> "overcast-day-rain.json"
        9          -> "rain.json"
        10, 33     -> "overcast-rain.json"
        11, 12     -> "sleet.json"
        13, 25     -> "partly-cloudy-day-snow.json"
        14, 26     -> "overcast-day-snow.json"
        15         -> "snow.json"
        16         -> "extreme-snow.json"
        17         -> "thunderstorms-rain.json"
        18, 29     -> "thunderstorms-overcast-rain.json"
        19         -> "thunderstorms-snow.json"
        20, 34     -> "extreme-rain.json"
        23         -> "extreme-day-rain.json"
        24         -> "partly-cloudy-day-sleet.json"
        27         -> "overcast-snow.json"
        28         -> "thunderstorms-day.json"
        32         -> "overcast-day-drizzle.json"
        35         -> "extreme.json"
        else       -> "clear-day.json"
    }

    companion object {
        private val PANEL_LABELS = arrayOf("Now", "+3h", "+6h")

        private const val WEATHER_CHECK_INTERVAL_MS = 60_000L
        private const val WEATHER_MIN_AGE_MS        = 15 * 60 * 1000L
        private const val WEATHER_MIN_DISTANCE_M    = 500f

        /** Persists across pane re-creation within the same app session. */
        private var lastFetchTime: Long      = 0L
        private var lastFetchLoc:  Location? = null
        private var lastWeather:   WeatherData? = null
        private var lastLocationName: String? = null
    }
}
