package com.tunnelgo.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Chỉ lưu ciphertext trong SharedPreferences; nội dung profile được mã hóa AES-GCM bằng khóa Android Keystore. */
class ProfileCryptoStore(context: Context) {
    private val prefs = context.getSharedPreferences("tunnelgo_vault", Context.MODE_PRIVATE)
    private val alias = "tunnelgo_profile_aes_v1"
    private val storageKey = "profiles_ciphertext"

    @Synchronized
    fun load(): List<StoredProfile> {
        val blob = prefs.getString(storageKey, null) ?: return emptyList()
        val pieces = blob.split('.', limit = 2)
        require(pieces.size == 2) { "Kho profile bị lỗi định dạng" }
        val iv = Base64.decode(pieces[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(pieces[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        val json = String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            StoredProfile(
                id = obj.getString("id"), name = obj.getString("name"),
                type = obj.getString("type"), host = obj.getString("host"),
                port = obj.getInt("port"), payload = obj.getString("payload")
            )
        }
    }

    @Synchronized
    fun save(profiles: List<StoredProfile>) {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(JSONObject().apply {
                put("id", profile.id); put("name", profile.name); put("type", profile.type)
                put("host", profile.host); put("port", profile.port); put("payload", profile.payload)
            })
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(array.toString().toByteArray(Charsets.UTF_8))
        val blob = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(prefs.edit().putString(storageKey, blob).commit()) { "Không thể lưu profile" }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}

data class StoredProfile(
    val id: String,
    val name: String,
    val type: String,
    val host: String,
    val port: Int,
    /** VLESS URI hoặc toàn bộ cấu hình .ovpn, chỉ được ghi vào bộ nhớ sau khi mã hóa. */
    val payload: String
)
