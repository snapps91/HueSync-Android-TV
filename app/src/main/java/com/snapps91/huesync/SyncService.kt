package com.snapps91.huesync

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Foreground lifecycle is explicit: projection tokens are never saved or reused after stop. */
class SyncService : Service() {
    private val alive = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private val gpuThread = HandlerThread("HueSync-GPU", android.os.Process.THREAD_PRIORITY_DEFAULT)
    private lateinit var gpu: Handler
    private val network = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "HueSync-DTLS") }
    private var projection: MediaProjection? = null
    @Volatile private var capture: CaptureCoordinator? = null
    private val stream = HueStream()
    private var api: HueApi? = null
    private var areaId = ""
    private var applicationId = ""
    private var startedArea = false
    @Volatile private var latest = FloatArray(0)
    @Volatile private var lastFrameAt = 0L
    @Volatile private var frameCount = 0L
    @Volatile private var sampleMs = 0f
    @Volatile private var renderer = English.GPU_OPENGL_ES
    @Volatile private var contentVisible = true
    private var lastStatsAt = 0L
    private var lastStatsFrames = 0L
    private var packets = 0L
    private val ownership = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "HueSync-Ownership") }
    private var encodedColors: FloatArray? = null
    private var encodedVisible = true
    private var hiddenColors = FloatArray(0)
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() = finish(English.SCREEN_SHARING_ENDED)
        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            contentVisible = isVisible
        }
    }

    override fun onCreate() {
        super.onCreate()
        gpuThread.start()
        gpu = Handler(gpuThread.looper)
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("sync", English.HUE_SYNCHRONIZATION, NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish(English.SYNCHRONIZATION_STOPPED)
            return START_NOT_STICKY
        }
        if (alive.get()) return START_NOT_STICKY
        val consent = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_CONSENT, Intent::class.java) }
        if (consent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, 1, notification(English.CONNECTING_TO_BRIDGE),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
            val settings = SyncSettings(this)
            val credentials = settings.credentials() ?: error(English.PAIR_THE_BRIDGE_FIRST_2)
            val options = settings.options()
            areaId = settings.area.also { require(it.isNotEmpty()) { English.SELECT_AN_ENTERTAINMENT_AREA } }
            alive.set(true)
            SyncStatus.value = SyncSnapshot(true, English.CONNECTING_TO_BRIDGE)
            val activeProjection = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
                .getMediaProjection(Activity.RESULT_OK, consent)
                ?: error(English.SCREEN_CAPTURE_UNAVAILABLE)
            projection = activeProjection
            activeProjection.registerCallback(callback, main)
            network.execute { connectAndStart(credentials, options) }
        } catch (e: Exception) {
            finish(English.startFailed(UserMessage.from(e)))
        }
        return START_NOT_STICKY
    }

    private fun connectAndStart(credentials: Credentials, options: SyncOptions) {
        try {
            val client = HueApi(this, credentials.bridge, credentials.applicationKey).also { api = it }
            val area = client.areas().firstOrNull { it.id == areaId }
                ?: error(English.ENTERTAINMENT_AREA_IS_NO_LONGER_AVAILABLE)
            require(area.channels.isNotEmpty() && area.channels.size <= HuePacket.MAX_CHANNELS) {
                English.noCompatibleChannels(HuePacket.MAX_CHANNELS)
            }
            require(!area.active) { English.AREA_IS_ALREADY_IN_USE_STOP }
            applicationId = client.applicationId()
            if (!alive.get()) return

            client.action(areaId, true)
            startedArea = true
            if (!alive.get()) return

            // DTLS uses the pairing username; /auth/v1 ID identifies the stream owner.
            stream.connect(credentials.bridge.ip, credentials.applicationKey, credentials.clientKey)
            if (!alive.get()) return
            check(client.ownedBy(areaId, applicationId)) { OWNERSHIP_LOST }
            scheduleOwnershipChecks(credentials)

            val packet = HuePacket(area.id, area.channels)
            latest = FloatArray(area.channels.size * 3)
            hiddenColors = FloatArray(latest.size)
            gpu.post { if (alive.get()) startCapture(area, options) }
            network.scheduleWithFixedDelay(
                { tick(packet, area.channels.size) }, 0,
                1_000_000_000L / options.fps, TimeUnit.NANOSECONDS
            )
        } catch (e: Exception) {
            finish(English.connectionFailed(UserMessage.from(e)))
        }
    }

    private fun scheduleOwnershipChecks(credentials: Credentials) {
        val client = HueApi(this, credentials.bridge, credentials.applicationKey)
        ownership.scheduleWithFixedDelay({
            if (alive.get()) try {
                check(client.ownedBy(areaId, applicationId)) { OWNERSHIP_LOST }
            } catch (e: Exception) {
                if (alive.getAndSet(false)) finish(English.syncInterrupted(UserMessage.from(e)))
            }
        }, 3, 3, TimeUnit.SECONDS)
    }
    private fun startCapture(area: HueArea, options: SyncOptions) {
        try {
            val currentProjection = projection ?: return
            capture = CaptureCoordinator(this, currentProjection, gpu, area, options, alive::get,
                { colors, ms, device ->
                    latest = colors
                    frameCount++
                    lastFrameAt = SystemClock.elapsedRealtime()
                    sampleMs = ms
                    renderer = device
                }, ::finish).also { it.open() }
            main.post { if (alive.get()) getSystemService(NotificationManager::class.java)
                .notify(1, notification(English.syncActive(area.name))) }
        } catch (e: Exception) { finish(English.captureUnavailable(UserMessage.from(e))) }
    }
    private fun tick(packet: HuePacket, channelCount: Int) {
        if (!alive.get()) return
        try {
            sendLatest(packet)
            val now = SystemClock.elapsedRealtime()
            if (now - lastStatsAt >= 1_000) publishStatus(now, channelCount)
        } catch (e: Exception) {
            finish(English.syncInterrupted(UserMessage.from(e)))
        }
    }

    private fun sendLatest(packet: HuePacket) {
        val colors = latest
        if (colors.isEmpty()) return
        val visible = contentVisible
        if (encodedColors !== colors || encodedVisible != visible) {
            packet.update(if (visible) colors else hiddenColors)
            encodedColors = colors
            encodedVisible = visible
        } else {
            packet.advanceSequence()
        }
        stream.send(packet.bytes)
        packets++
    }

    private fun publishStatus(now: Long, channelCount: Int) {
        val frames = frameCount
        val fps = if (lastStatsAt == 0L) 0 else
            ((frames - lastStatsFrames) * 1_000 / (now - lastStatsAt)).toInt()
        val colors = latest
        val message = when {
            !contentVisible -> English.CONTENT_NOT_VISIBLE_LIGHTS_DIMMED
            lastFrameAt == 0L -> English.WAITING_FOR_THE_FIRST_FRAME
            colors.maxOrNull()?.let { it < .015f } == true ->
                English.BLACK_SCREEN_DARK_SCENE_OR_UNCAPTURABLE
            now - lastFrameAt > 3_000 -> English.STATIC_IMAGE_KEEPING_COLORS_ACTIVE
            else -> English.SYNCHRONIZATION_ACTIVE
        }
        val swatches = IntArray(channelCount) { i ->
            val offset = i * 3
            Color.rgb((colors[offset] * 255).toInt(),
                (colors[offset + 1] * 255).toInt(),
                (colors[offset + 2] * 255).toInt())
        }
        SyncStatus.value = SyncSnapshot(
            running = true, message = message, fps = fps, gpuMs = sampleMs,
            channels = channelCount, renderer = renderer, colors = swatches, frames = packets,
            acquisitionMs = capture?.acquisitionMs ?: 0f,
            drawReadbackMs = capture?.drawReadbackMs ?: 0f
        )
        lastStatsAt = now
        lastStatsFrames = frames
    }
    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, SyncService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "sync").setSmallIcon(R.drawable.ic_sync)
            .setContentTitle(English.HUESYNC_TV_AMBIENT_LIGHT).setContentText(message).setContentIntent(open)
            .setOngoing(true).setSilent(true).addAction(0, English.STOP_2, stop).build()
    }
    private fun finish(message: String) {
        alive.set(false)
        main.post {
            SyncStatus.value = SyncStatus.value.copy(running = false, message = message, fps = 0)
            stopSelf()
        }
    }
    override fun onDestroy() {
        alive.set(false)
        ownership.shutdownNow()
        stream.abort()
        projection?.unregisterCallback(callback)
        projection?.stop()
        projection = null
        gpu.post {
            gpu.removeCallbacksAndMessages(null)
            capture?.close()
            capture = null
            gpuThread.quitSafely()
        }
        // Serialized behind any in-flight start: even cancellation during REST/DTLS setup releases ownership.
        network.execute {
            stream.close()
            if (startedArea && applicationId.isNotEmpty()) runCatching {
                if (api?.ownedBy(areaId, applicationId) == true) api?.action(areaId, false)
            }
        }
        network.shutdown() // periodic tasks are cancelled; queued cleanup is allowed to finish.
        if (SyncStatus.value.running) SyncStatus.value = SyncStatus.value.copy(running = false, message = English.SERVICE_STOPPED, fps = 0)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onTaskRemoved(rootIntent: Intent?) { finish(English.APP_CLOSED_SYNCHRONIZATION_ENDED) }
    companion object {
        const val EXTRA_CONSENT = "projectionConsent"
        const val ACTION_STOP = "com.snapps91.huesync.STOP"
        private const val OWNERSHIP_LOST = English.THE_BRIDGE_ENDED_THE_STREAM_OR
    }
}
