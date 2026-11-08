package com.snapps91.huesync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials never leave app-private storage; the encryption key stays in Android Keystore. */
class SyncSettings(context: Context) {
    private val prefs = context.getSharedPreferences("huesync", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun saveCredentials(value: Credentials) {
        val json = JSONObject()
            .put("ip", value.bridge.ip)
            .put("id", value.bridge.id)
            .put("name", value.bridge.name)
            .put("key", value.applicationKey)
            .put("client", value.clientKey)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        check(prefs.edit().putString("credentials", encoded).remove("area").commit()) {
            "Could not save Bridge pairing"
        }
    }

    fun credentials(): Credentials? {
        val stored = prefs.getString("credentials", null) ?: return null
        val data = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        }
        val obj = JSONObject(String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8))
        val bridge = Bridge(obj.getString("ip"), obj.getString("id"), obj.getString("name"))
        return Credentials(bridge, obj.getString("key"), obj.getString("client"))
    }

    var area: String
        get() = prefs.getString("area", "") ?: ""
        set(value) {
            prefs.edit().putString("area", value).apply()
        }

    fun options() = SyncOptions(
        fps = prefs.getInt("fps", 30),
        brightness = prefs.getFloat("brightness", .75f),
        smoothingMs = prefs.getInt("smoothing", 100),
        saturation = prefs.getFloat("saturation", 1.15f),
        crop = prefs.getFloat("crop", .08f),
        invertHorizontal = prefs.getBoolean("invertHorizontal", true)
    )

    fun saveOptions(value: SyncOptions) {
        prefs.edit()
            .putInt("fps", value.fps)
            .putFloat("brightness", value.brightness)
            .putInt("smoothing", value.smoothingMs)
            .putFloat("saturation", value.saturation)
            .putFloat("crop", value.crop)
            .remove("compatibilityCapture")
            .putBoolean("invertHorizontal", value.invertHorizontal)
            .apply()
    }

    companion object {
        private const val KEY_ALIAS = "huesync.credentials"
    }
}
