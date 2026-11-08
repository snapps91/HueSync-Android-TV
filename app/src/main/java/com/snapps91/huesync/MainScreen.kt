package com.snapps91.huesync

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import java.util.Locale

/** Native TV controls and rendering. All actions are delegated to the activity. */
internal class MainScreen(
    private val activity: Activity,
    options: SyncOptions,
    private val actions: Actions
) {
    interface Actions {
        fun onStartSync()
        fun onStopSync()
        fun onReturnToTv()
        fun onSearchNetwork()
        fun onPairBridge()
        fun onRefreshAreas()
        fun onAreaSelected(index: Int)
        fun onOpenTestPattern()
    }

    private val ink = activity.getColor(R.color.brand_text)
    private val muted = activity.getColor(R.color.brand_muted)
    private val cyan = activity.getColor(R.color.brand_cyan)
    private val pink = activity.getColor(R.color.brand_pink)
    private val violet = activity.getColor(R.color.brand_violet)
    private val cardColor = activity.getColor(R.color.brand_surface)
    private val raisedColor = activity.getColor(R.color.brand_surface_raised)
    private val focusedColor = activity.getColor(R.color.brand_surface_focused)

    private lateinit var ip: EditText
    private lateinit var bridgeLabel: TextView
    private lateinit var setupMessage: TextView
    private lateinit var status: TextView
    private lateinit var badge: TextView
    private lateinit var metrics: TextView
    private lateinit var hardware: TextView
    private lateinit var channelStrip: LinearLayout
    private lateinit var discovered: LinearLayout
    private lateinit var areaChoice: Spinner
    private lateinit var fpsChoice: Spinner
    private lateinit var invertHorizontal: Switch
    private lateinit var brightness: SeekBar
    private lateinit var smoothing: SeekBar
    private lateinit var saturation: SeekBar
    private lateinit var crop: SeekBar
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var reload: Button
    private val setupControls = mutableListOf<View>()

    val root: View = build(options)
    val bridgeIp: String get() = ip.text.toString().trim()

    fun focusStart() {
        start.requestFocus()
    }

    fun setBridgeIp(value: String) {
        ip.setText(value)
    }

    fun showBridge(bridge: Bridge) {
        bridgeLabel.text = English.bridge(bridge.name, bridge.ip)
    }

    fun showMessage(value: String?) {
        setupMessage.text = value.orEmpty()
    }

    fun clearDiscoveredBridges() {
        discovered.removeAllViews()
    }

    fun addDiscoveredBridge(bridge: Bridge) {
        discovered.addView(button(English.bridge(bridge.name, bridge.ip)) {
            setBridgeIp(bridge.ip)
            showMessage(English.BRIDGE_SELECTED_PRESS_ITS_PHYSICAL_BUTTON)
        })
    }

    fun showAreas(areas: List<HueArea>, selectedIndex: Int) {
        areaChoice.adapter = adapter(if (areas.isEmpty()) listOf(English.NO_AREAS_CREATE_ONE_IN_THE) else
            areas.map { English.area(it.name, it.channels.size, it.active) })
        areaChoice.setSelection(selectedIndex)
    }

    fun selectedOptions(): SyncOptions = SyncOptions(
        fps = STREAM_RATES[fpsChoice.selectedItemPosition],
        brightness = brightness.progress / 100f,
        smoothingMs = smoothing.progress,
        saturation = saturation.progress / 100f,
        crop = crop.progress / 100f,
        invertHorizontal = invertHorizontal.isChecked
    )

    fun render(snapshot: SyncSnapshot, busy: Boolean, hasCredentials: Boolean, hasArea: Boolean) {
        status.text = snapshot.message
        badge.text = if (snapshot.running) English.SYNCING else English.STANDBY
        metrics.text = if (snapshot.running) String.format(
            Locale.US, English.METRICS_FORMAT,
            snapshot.fps, snapshot.gpuMs, snapshot.channels
        ) else English.LOCAL_GPU_ENCRYPTED_STREAM_NO_CLOUD
        hardware.text = if (snapshot.running) buildString {
            append(English.packets(snapshot.renderer, snapshot.frames))
            if (!snapshot.renderer.startsWith("ImageReader")) append(String.format(
                Locale.US, English.GPU_METRICS_FORMAT,
                snapshot.acquisitionMs, snapshot.drawReadbackMs
            ))
        } else English.SURFACETEXTURE_SHADER_OPENGL_ES_HUE_ENTERTAINMENT

        start.isEnabled = !snapshot.running && !busy && hasCredentials && hasArea
        stop.isEnabled = snapshot.running
        setupControls.forEach { it.isEnabled = !snapshot.running && !busy }
        reload.isEnabled = hasCredentials && !snapshot.running && !busy
        renderSwatches(snapshot.colors)
    }

    private fun renderSwatches(colors: IntArray) {
        if (channelStrip.childCount != colors.size) {
            channelStrip.removeAllViews()
            repeat(colors.size) {
                channelStrip.addView(View(activity), LinearLayout.LayoutParams(0, dp(12), 1f).apply {
                    marginEnd = dp(5)
                })
            }
        }
        colors.forEachIndexed { i, color -> channelStrip.getChildAt(i).background = rounded(color, 4) }
    }

    private fun build(options: SyncOptions): View {
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            setBackgroundColor(activity.getColor(R.color.brand_background))
            clipToPadding = false
        }
        val content = column().apply { setPadding(dp(36), dp(24), dp(36), dp(28)) }
        scroll.addView(content)
        content.addView(buildHeader())
        content.addView(space(18))
        content.addView(buildDashboard())
        content.addView(space(14))
        content.addView(row().apply {
            gravity = Gravity.TOP
            addView(buildConnectionCard(), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
            addView(buildTuningCard(options), LinearLayout.LayoutParams(0, -2, 1f))
        })
        content.addView(space(14))
        content.addView(text(English.HOW_IT_WORKS, 11, violet, true))
        content.addView(text(
            English.ALLOW_FULL_SCREEN_SHARING_THEN_RETURN,
            13, muted
        ).apply { setPadding(0, dp(6), 0, dp(6)) })
        content.addView(text(
            English.DRM_PROTECTED_VIDEO_TV_TUNERS_AND,
            12, muted
        ))
        return scroll
    }

    private fun buildHeader(): View = row().apply {
        val title = column().apply {
            addView(text(English.HUE_SYNC_ANDROID_TV, 12, cyan, true))
            addView(text(English.YOUR_SCREEN_BECOMES_LIGHT, 30, ink, true).apply {
                setPadding(0, dp(5), 0, dp(5))
            })
            addView(text(English.REAL_TIME_COLORS_ALL_ON_YOUR, 14, muted))
        }
        addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        badge = text(English.READY, 12, pink, true).apply {
            background = rounded(activity.getColor(R.color.brand_badge))
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        addView(badge)
    }

    private fun buildDashboard(): View = card().apply {
        status = text(English.CONNECT_A_BRIDGE_TO_GET_STARTED, 20, ink, true)
        addView(status)
        addView(space(10))
        metrics = text(English.ANALYZED_FPS_MS_SAMPLING_CHANNELS, 15, cyan)
        addView(metrics)
        hardware = text(English.SURFACETEXTURE_SHADER_OPENGL_ES_HUE_ENTERTAINMENT, 12, muted)
        addView(hardware.apply { setPadding(0, dp(8), 0, 0) })
        channelStrip = row().apply { minimumHeight = dp(22); setPadding(0, dp(10), 0, 0) }
        addView(channelStrip)
        addView(row().apply {
            setPadding(0, dp(12), 0, 0)
            start = button(English.START_SYNC, primary = true, actions::onStartSync)
            stop = button(English.STOP, action = actions::onStopSync)
            addView(start)
            addView(stop)
            addView(button(English.RETURN_TO_TV, action = actions::onReturnToTv))
        })
    }

    private fun buildConnectionCard(): View = card().apply {
        addView(text(English.TEXT_01_CONNECT_BRIDGE, 12, cyan, true))
        bridgeLabel = text(English.HUE_BRIDGE_ON_YOUR_LOCAL_NETWORK, 19, ink, true).apply {
            setPadding(0, dp(10), 0, dp(6))
        }
        addView(bridgeLabel)
        ip = EditText(activity).apply {
            hint = English.BRIDGE_IP_ADDRESS
            setTextColor(ink)
            setHintTextColor(muted)
            textSize = 16f
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            contentDescription = English.HUE_BRIDGE_IP_ADDRESS
        }
        addView(ip, LinearLayout.LayoutParams(-1, dp(48)))
        val search = button(English.SEARCH_NETWORK, action = actions::onSearchNetwork)
        val pair = button(English.PAIR_BRIDGE, action = actions::onPairBridge)
        addView(row().apply { addView(search); addView(pair) })
        discovered = column()
        addView(discovered)
        setupMessage = text(English.PRESS_THE_PHYSICAL_BUTTON_ON_THE, 13, muted)
        addView(setupMessage.apply { setPadding(0, dp(8), 0, dp(10)) })
        addView(text(English.ENTERTAINMENT_AREA, 11, muted, true))
        areaChoice = spinner(listOf(English.PAIR_A_BRIDGE)).apply {
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long
                ) = actions.onAreaSelected(position)
            }
        }
        addView(areaChoice)
        reload = button(English.REFRESH_AREAS, action = actions::onRefreshAreas)
        addView(reload)
        addView(text(
            English.CREATE_A_TV_AREA_AND_ARRANGE,
            12, muted
        ))
        setupControls.addAll(listOf(ip, search, pair, areaChoice, reload))
    }

    private fun buildTuningCard(options: SyncOptions): View = card().apply {
        addView(text(English.TEXT_02_LIGHT_RESPONSE, 12, pink, true))
        addView(text(English.TUNE_YOUR_LIGHTS, 19, ink, true).apply { setPadding(0, dp(10), 0, dp(8)) })
        invertHorizontal = Switch(activity).apply {
            text = English.INVERT_LEFT_RIGHT
            textSize = 13f
            setTextColor(ink)
            isChecked = options.invertHorizontal
            minimumHeight = dp(44)
        }
        addView(invertHorizontal)
        addView(text(
            English.IF_GPU_CAPTURE_FAILS_THE_APP,
            12, muted
        ))
        addView(text(English.STREAM_RATE, 12, muted))
        fpsChoice = spinner(listOf(English.TEXT_20_FPS_EFFICIENT, English.TEXT_30_FPS_BALANCED, English.TEXT_50_FPS_RESPONSIVE))
        fpsChoice.setSelection(STREAM_RATES.indexOf(options.fps).coerceAtLeast(0))
        addView(fpsChoice)
        brightness = slider(this, English.BRIGHTNESS, 100, (options.brightness * 100).toInt()) { English.percent(it) }
        smoothing = slider(this, English.SMOOTHING, 300, options.smoothingMs) { English.milliseconds(it) }
        saturation = slider(this, English.SATURATION, 180, (options.saturation * 100).toInt()) { English.percent(it) }
        crop = slider(this, English.CROP_TOP_BOTTOM_BLACK_BARS, 20, (options.crop * 100).toInt()) { English.percent(it) }
        addView(text(
            English.CROP_HELP,
            12, muted
        ))
        addView(button(English.OPEN_TEST_PATTERN, action = actions::onOpenTestPattern))
        setupControls.addAll(listOf(invertHorizontal, fpsChoice, brightness, smoothing, saturation, crop))
    }

    private fun slider(
        parent: LinearLayout, name: String, maximum: Int, initial: Int, format: (Int) -> String
    ): SeekBar {
        val label = text(English.slider(name, format(initial)), 12, muted)
        parent.addView(label)
        return SeekBar(activity).apply {
            max = maximum
            progress = initial
            contentDescription = name
            parent.addView(this, LinearLayout.LayoutParams(-1, dp(36)))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    label.text = English.slider(name, format(value))
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
    }

    private fun spinner(items: List<String>) = Spinner(activity).apply {
        adapter = adapter(items)
        minimumHeight = dp(46)
    }

    private fun adapter(items: List<String>) =
        ArrayAdapter(activity, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(activity).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun button(value: String, primary: Boolean = false, action: () -> Unit) = Button(activity).apply {
        text = value
        textSize = 12f
        isAllCaps = false
        minimumHeight = dp(44)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setTextColor(if (primary) activity.getColor(R.color.brand_dark_text) else ink)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),
                rounded(if (primary) Color.WHITE else focusedColor).apply { setStroke(dp(2), pink) })
            addState(intArrayOf(), rounded(if (primary) cyan else raisedColor))
        }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
            marginEnd = dp(8)
            topMargin = dp(4)
            bottomMargin = dp(4)
        }
        setOnClickListener { action() }
        setOnFocusChangeListener { _, _ -> alpha = if (isEnabled) 1f else .4f }
    }

    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private fun card() = column().apply {
        background = rounded(cardColor)
        setPadding(dp(20), dp(18), dp(20), dp(18))
    }
    private fun rounded(color: Int, radius: Int = 12) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    private fun space(height: Int) = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    companion object {
        private val STREAM_RATES = listOf(20, 30, 50)
    }
}
