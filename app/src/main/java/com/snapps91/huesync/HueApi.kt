package com.snapps91.huesync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

class LinkButtonRequired : IOException("Press the button on the Bridge and try again within 30 seconds.")

/** HTTPS with Hue CA chain validation and bridge-ID verification (the certificate is not issued to its IP). */
class HueApi(context: Context, private val bridge: Bridge, private val key: String = "") {
    private data class Response(
        val body: String,
        val headers: Map<String, List<String>>,
        val bridgeId: String
    )

    private val socketFactory = SSLContext.getInstance("TLS").apply {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
        context.resources.openRawResource(R.raw.hue_ca).use { stream ->
            CertificateFactory.getInstance("X.509").generateCertificates(stream).forEachIndexed { i, cert ->
                store.setCertificateEntry("hue-$i", cert)
            }
        }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        init(null, managers.trustManagers, null)
    }.socketFactory

    private fun request(method: String, path: String, body: JSONObject? = null): Response {
        validateLocalIp(bridge.ip)
        val connection = URL("https://${bridge.ip}$path").openConnection() as HttpsURLConnection
        connection.sslSocketFactory = socketFactory
        connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, session ->
            val cert = session.peerCertificates.firstOrNull() as? X509Certificate
            val cn = cert?.let(::certificateBridgeId)
            cn != null && (bridge.id.isEmpty() || cn.equals(bridge.id, ignoreCase = true))
        }
        connection.connectTimeout = 4000
        connection.readTimeout = 4000
        connection.instanceFollowRedirects = false
        connection.requestMethod = method
        connection.setRequestProperty("Accept", "application/json")
        if (key.isNotEmpty()) connection.setRequestProperty("hue-application-key", key)
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code == 401 || code == 403) throw IOException("Hue pairing was revoked. Pair the Bridge again.")
            if (code !in 200..299) throw IOException("Bridge returned HTTP $code. Check the network and firmware.")
            val peer = connection.serverCertificates.first() as X509Certificate
            val peerIdentity = certificateBridgeId(peer)
                ?: throw IOException("Bridge certificate has no Hue identity")
            if (bridge.id.isNotEmpty() && !peerIdentity.equals(bridge.id, ignoreCase = true))
                throw IOException("Bridge identity differs from the paired Bridge")
            val text = connection.inputStream.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 2_000_000) throw IOException("Bridge response is too large")
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
            return Response(text, connection.headerFields.filterKeys { it != null }, peerIdentity)
        } finally { connection.disconnect() }
    }

    private fun certificateBridgeId(cert: X509Certificate): String? =
        BRIDGE_CN.find(cert.subjectX500Principal.name)?.groupValues?.get(1)?.lowercase()

    fun identify(): Bridge {
        val response = request("GET", "/api/config")
        val config = JSONObject(response.body)
        val id = config.getString("bridgeid").lowercase()
        require(Regex("[0-9a-f]{16}").matches(id)) { "Invalid Bridge ID" }
        if (bridge.id.isNotEmpty()) require(id == bridge.id.lowercase()) { "This is not the paired Bridge" }
        require(id == response.bridgeId) { "Bridge certificate and ID do not match" }
        return Bridge(bridge.ip, id, config.optString("name", "Hue Bridge"))
    }
    fun pair(): Credentials {
        require(bridge.id.isNotEmpty())
        val result = JSONArray(request("POST", "/api", JSONObject()
            .put("devicetype", "huesync#androidtv").put("generateclientkey", true)).body).getJSONObject(0)
        result.optJSONObject("error")?.let {
            if (it.optInt("type") == 101) throw LinkButtonRequired()
            throw IOException("Bridge rejected pairing (${it.optInt("type")})")
        }
        val success = result.getJSONObject("success")
        val client = success.getString("clientkey")
        require(client.matches(Regex("[a-fA-F0-9]{32}"))) { "Invalid Hue client key" }
        return Credentials(bridge, success.getString("username"), client)
    }
    fun applicationId(): String {
        val headers = request("GET", "/auth/v1").headers
        return headers.entries.firstOrNull { it.key.equals("hue-application-id", true) }
            ?.value?.firstOrNull()?.also { require(it.isNotBlank()) }
            ?: throw IOException("Bridge did not return hue-application-id; update its firmware.")
    }
    private fun resource(path: String, method: String = "GET", body: JSONObject? = null): JSONArray {
        val obj = JSONObject(request(method, "/clip/v2/resource/$path", body).body)
        val errors = obj.optJSONArray("errors")
        if (errors != null && errors.length() > 0) throw IOException("Hue operation rejected: " +
            errors.getJSONObject(0).optString("description", "Bridge error"))
        return obj.getJSONArray("data")
    }
    fun areas(): List<HueArea> {
        val data = resource("entertainment_configuration")
        return (0 until data.length()).map { index ->
            val obj = data.getJSONObject(index)
            val channels = obj.getJSONArray("channels")
            HueArea(obj.getString("id"), obj.getJSONObject("metadata").getString("name"),
                (0 until channels.length()).map { i ->
                    val channel = channels.getJSONObject(i)
                    val pos = channel.getJSONObject("position")
                    HueChannel(channel.getInt("channel_id"), pos.optDouble("x", 0.0).toFloat(),
                        pos.optDouble("z", 0.0).toFloat())
                }, obj.optString("status") == "active")
        }
    }
    fun action(id: String, start: Boolean) {
        java.util.UUID.fromString(id)
        resource("entertainment_configuration/$id", "PUT", JSONObject().put("action", if (start) "start" else "stop"))
    }
    fun ownedBy(id: String, applicationId: String): Boolean {
        val obj = resource("entertainment_configuration/$id").getJSONObject(0)
        return obj.optString("status") == "active" &&
            obj.optJSONObject("active_streamer")?.optString("rid") == applicationId
    }
    companion object {
        private val BRIDGE_CN = Regex("(?:^|,)CN=([0-9a-fA-F]{16})(?:,|$)")

        fun validateLocalIp(value: String) {
            require(value.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))) { "Enter a local IPv4 address, such as 192.168.1.20" }
            require(value.split('.').all { it.toInt() in 0..255 }) { "Invalid IPv4 address" }
            val address = InetAddress.getByName(value)
            require(address.isSiteLocalAddress || address.isLinkLocalAddress) { "The Bridge must be on the local network" }
        }
    }
}
