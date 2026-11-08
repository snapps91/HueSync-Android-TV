package com.snapps91.huesync

import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.DTLSClientProtocol
import org.bouncycastle.tls.DTLSTransport
import org.bouncycastle.tls.PSKTlsClient
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.UDPTransport
import org.bouncycastle.tls.crypto.impl.jcajce.JcaTlsCryptoProvider
import java.io.Closeable
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom

class HueStream : Closeable {
    @Volatile private var socket: DatagramSocket? = null
    private var transport: DTLSTransport? = null

    fun connect(ip: String, identity: String, clientKey: String) {
        HueApi.validateLocalIp(ip)
        connectTo(InetAddress.getByName(ip), ENTERTAINMENT_PORT, identity, clientKey)
    }

    internal fun connectTo(address: InetAddress, port: Int, identity: String, clientKey: String) {
        require(clientKey.matches(Regex("[0-9a-fA-F]{32}")))
        val psk = ByteArray(16) { clientKey.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val udp = DatagramSocket().also { socket = it }
        try {
            udp.connect(address, port)
            val crypto = JcaTlsCryptoProvider().create(SecureRandom())
            val client = object : PSKTlsClient(crypto, identity.toByteArray(Charsets.US_ASCII), psk) {
                override fun getSupportedVersions() = arrayOf(ProtocolVersion.DTLSv12)
                override fun getSupportedCipherSuites() = intArrayOf(CipherSuite.TLS_PSK_WITH_AES_128_GCM_SHA256)
                override fun getHandshakeTimeoutMillis(): Int = 8000
            }
            transport = DTLSClientProtocol().connect(client, UDPTransport(udp, 1200))
        } catch (e: Exception) {
            udp.close()
            throw e
        } finally {
            psk.fill(0)
        }
    }

    fun send(packet: ByteArray) {
        val current = transport ?: throw java.io.IOException(English.HUE_STREAM_IS_NOT_CONNECTED)
        current.send(packet, 0, packet.size)
    }

    /** Can be called from the main thread to cancel a blocked handshake immediately. */
    fun abort() {
        socket?.close()
    }

    override fun close() {
        runCatching { transport?.close() }
        socket?.close()
        transport = null
    }

    companion object {
        private const val ENTERTAINMENT_PORT = 2100
    }
}
