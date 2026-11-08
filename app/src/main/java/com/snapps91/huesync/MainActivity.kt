package com.snapps91.huesync

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.concurrent.Executors

/** Coordinates Bridge setup and Android capture consent for the native TV screen. */
class MainActivity : Activity(), MainScreen.Actions {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var settings: SyncSettings
    private lateinit var screen: MainScreen
    private var credentials: Credentials? = null
    private var areas = emptyList<HueArea>()
    private var discovery: BridgeDiscovery? = null
    private var busy = false

    private val refresh = object : Runnable {
        override fun run() {
            renderStatus()
            main.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        settings = SyncSettings(this)
        val pairing = runCatching { settings.credentials() }
        credentials = pairing.getOrNull()
        screen = MainScreen(this, settings.options(), this)
        setContentView(screen.root)
        screen.focusStart()
        screen.setBridgeIp(savedInstanceState?.getString(STATE_IP) ?: credentials?.bridge?.ip.orEmpty())

        pairing.exceptionOrNull()?.let {
            screen.showMessage(English.CREDENTIALS_COULD_NOT_BE_READ_PAIR)
        } ?: credentials?.let {
            screen.showBridge(it.bridge)
            onRefreshAreas()
        }
        renderStatus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_IP, screen.bridgeIp)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        main.post(refresh)
    }

    override fun onPause() {
        main.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        discovery?.close()
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onSearchNetwork() {
        discovery?.close()
        screen.clearDiscoveredBridges()
        screen.showMessage(English.SEARCHING_THE_LOCAL_NETWORK_WITH_MDNS)
        val seen = mutableSetOf<String>()
        lateinit var search: BridgeDiscovery
        search = BridgeDiscovery(this, { bridge ->
            runOnUiThread {
                if (!isDestroyed && discovery === search && seen.add(bridge.ip)) screen.addDiscoveredBridge(bridge)
            }
        }, { message ->
            runOnUiThread { if (!isDestroyed && discovery === search) screen.showMessage(message) }
        })
        discovery = search
        search.start()
        main.postDelayed({
            search.close()
            if (!isDestroyed && discovery === search && seen.isEmpty()) {
                screen.showMessage(English.NO_BRIDGE_FOUND_WITH_MDNS_ENTER)
            }
        }, DISCOVERY_TIMEOUT_MS)
    }

    override fun onPairBridge() {
        if (busy || SyncStatus.value.running) return
        val address = screen.bridgeIp
        try {
            HueApi.validateLocalIp(address)
        } catch (e: Exception) {
            screen.showMessage(e.message)
            return
        }
        runAsync(English.CHECKING_BRIDGE_AND_PAIRING, {
            val bridge = HueApi(this, Bridge(address, "", English.HUE_BRIDGE)).identify()
            HueApi(this, bridge).pair().also { settings.saveCredentials(it) }
        }) { paired ->
            credentials = paired
            screen.showBridge(paired.bridge)
            screen.showMessage(English.BRIDGE_PAIRED_SELECT_AN_ENTERTAINMENT_AREA)
            onRefreshAreas()
        }
    }

    override fun onRefreshAreas() {
        val paired = credentials ?: run {
            screen.showMessage(English.PAIR_THE_BRIDGE_FIRST)
            return
        }
        if (busy) return
        runAsync(English.LOADING_ENTERTAINMENT_AREAS, {
            HueApi(this, paired.bridge, paired.applicationKey).areas()
        }) { loaded ->
            areas = loaded
            val index = loaded.indexOfFirst { it.id == settings.area }.coerceAtLeast(0)
            screen.showAreas(loaded, index)
            settings.area = loaded.getOrNull(index)?.id.orEmpty()
            screen.showMessage(if (loaded.isEmpty())
                English.NO_ENTERTAINMENT_AREAS_FOUND_CREATE_ONE
            else English.BRIDGE_READY_LIGHT_POSITIONS_COME_FROM)
        }
    }

    override fun onAreaSelected(index: Int) {
        areas.getOrNull(index)?.let {
            settings.area = it.id
            renderStatus()
        }
    }

    override fun onStartSync() {
        if (credentials == null || settings.area.isEmpty() || SyncStatus.value.running || busy) return
        if (areas.firstOrNull { it.id == settings.area }?.active == true) {
            screen.showMessage(English.THIS_AREA_IS_IN_USE_STOP)
            return
        }
        settings.saveOptions(screen.selectedOptions())
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        } else {
            requestCaptureConsent()
        }
    }

    override fun onStopSync() {
        startService(Intent(this, SyncService::class.java).setAction(SyncService.ACTION_STOP))
    }

    override fun onReturnToTv() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
    }

    override fun onOpenTestPattern() {
        startActivity(Intent(this, PatternActivity::class.java))
    }

    private fun requestCaptureConsent() {
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = if (Build.VERSION.SDK_INT >= 34)
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else manager.createScreenCaptureIntent()
            startActivityForResult(intent, REQUEST_CAPTURE)
        } catch (e: Exception) {
            screen.showMessage(English.firmwareCaptureUnavailable(UserMessage.from(e)))
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) requestCaptureConsent()
    }

    @Deprecated("Activity result API supports the native Activity on Android TV 10")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            screen.showMessage(English.SCREEN_SHARING_WAS_NOT_AUTHORIZED_SYNCHRONIZATION)
            return
        }
        try {
            ContextCompat.startForegroundService(this,
                Intent(this, SyncService::class.java).putExtra(SyncService.EXTRA_CONSENT, data))
        } catch (e: Exception) {
            screen.showMessage(English.serviceStartFailed(UserMessage.from(e)))
        }
    }

    private fun <T> runAsync(message: String, task: () -> T, onSuccess: (T) -> Unit) {
        busy = true
        screen.showMessage(message)
        renderStatus()
        io.execute {
            val result = runCatching(task)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy = false
                result.fold(onSuccess) { error ->
                    screen.showMessage(UserMessage.from(error))
                }
                renderStatus()
            }
        }
    }

    private fun renderStatus() {
        if (::screen.isInitialized) {
            screen.render(SyncStatus.value, busy, credentials != null, settings.area.isNotEmpty())
        }
    }

    companion object {
        private const val STATE_IP = "ip"
        private const val REQUEST_NOTIFICATIONS = 11
        private const val REQUEST_CAPTURE = 12
        private const val DISCOVERY_TIMEOUT_MS = 10_000L
    }
}
