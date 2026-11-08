package com.snapps91.huesync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.util.ArrayDeque

/** Entirely local discovery. Resolutions are serialized for older Android TV NSD implementations. */
@Suppress("DEPRECATION")
class BridgeDiscovery(
    context: Context,
    private val found: (Bridge) -> Unit,
    private val error: (String) -> Unit
) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val multicast = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
        ?.createMulticastLock("HueSync discovery")
        ?.apply { setReferenceCounted(false) }
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    @Volatile private var active = false
    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) = Unit
        override fun onDiscoveryStopped(type: String) = Unit
        override fun onServiceLost(info: NsdServiceInfo) = Unit
        override fun onStartDiscoveryFailed(type: String, code: Int) {
            close()
            error(English.discoveryUnavailable(code))
        }
        override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
        override fun onServiceFound(info: NsdServiceInfo) = synchronized(pending) {
            if (active) {
                pending.add(info)
                resolveNext()
            }
        }
    }

    private fun resolveNext() {
        if (resolving || pending.isEmpty() || !active) return
        resolving = true
        nsd.resolveService(pending.removeFirst(), object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) = finish()

            override fun onServiceResolved(info: NsdServiceInfo) {
                val ip = info.host?.hostAddress
                if (active && ip != null && runCatching { HueApi.validateLocalIp(ip) }.isSuccess) {
                    val id = info.attributes["bridgeid"]?.toString(Charsets.UTF_8)?.lowercase() ?: ""
                    found(Bridge(ip, id, info.serviceName))
                }
                finish()
            }

            private fun finish() = synchronized(pending) {
                resolving = false
                resolveNext()
            }
        })
    }

    fun start() {
        if (active) return
        active = true
        try {
            multicast?.acquire()
            nsd.discoverServices("_hue._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            close()
            error(English.DISCOVERY_FAILED_ENTER_THE_LOCAL_IP)
        }
    }

    fun close() {
        active = false
        synchronized(pending) { pending.clear() }
        runCatching { nsd.stopServiceDiscovery(listener) }
        if (multicast?.isHeld == true) multicast.release()
    }
}
