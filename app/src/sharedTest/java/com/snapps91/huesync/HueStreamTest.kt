package com.snapps91.huesync

import org.bouncycastle.tls.*
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HueStreamTest {
    @Test(timeout = 15000) fun sendsHuePacketOverAuthenticatedDtls12() {
        val identity = "pairing-username-not-auth-v1-resource-id"
        val key = "00112233445566778899aabbccddeeff"
        val psk = ByteArray(16) { key.substring(it*2,it*2+2).toInt(16).toByte() }
        val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val serverThread = Executors.newSingleThreadExecutor()
        val client = HueStream()
        try {
            val received = serverThread.submit<ByteArray> {
                val transport = object : DatagramTransport {
                    override fun getReceiveLimit() = 1200
                    override fun getSendLimit() = 1200
                    override fun close() { socket.close() }
                    override fun send(buf: ByteArray, off: Int, len: Int) { socket.send(DatagramPacket(buf, off, len)) }
                    override fun receive(buf: ByteArray, off: Int, len: Int, wait: Int): Int {
                        socket.soTimeout = wait.coerceAtLeast(1)
                        val packet = DatagramPacket(buf,off,len)
                        socket.receive(packet)
                        if (!socket.isConnected) socket.connect(packet.socketAddress)
                        return packet.length
                    }
                }
                val manager = object : TlsPSKIdentityManager {
                    override fun getHint() = byteArrayOf()
                    override fun getPSK(offered: ByteArray): ByteArray? =
                        if (offered.contentEquals(identity.toByteArray(Charsets.US_ASCII))) psk.copyOf() else null
                }
                val server = object : PSKTlsServer(BcTlsCrypto(SecureRandom()), manager) {
                    override fun getSupportedVersions() = arrayOf(ProtocolVersion.DTLSv12)
                    override fun getSupportedCipherSuites() = intArrayOf(CipherSuite.TLS_PSK_WITH_AES_128_GCM_SHA256)
                    override fun getHandshakeTimeoutMillis() = 8000
                }
                val dtls = DTLSServerProtocol().accept(server,transport)
                val output = ByteArray(1200)
                var count: Int
                do { count = dtls.receive(output,0,output.size,4000) } while (count < 0)
                output.copyOf(count)
            }
            client.connectTo(InetAddress.getLoopbackAddress(), socket.localPort, identity, key)
            val packet = HuePacket("00112233-4455-6677-8899-aabbccddeeff", listOf(HueChannel(5,0f,0f)))
                .update(floatArrayOf(.8f,.2f,.1f))
            client.send(packet)
            assertArrayEquals(packet, received.get(5,TimeUnit.SECONDS))
        } finally {
            client.close(); socket.close(); serverThread.shutdownNow()
        }
    }
}
