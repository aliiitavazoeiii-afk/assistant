package com.ali.assistant.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureSecretStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("assistant_secure", Context.MODE_PRIVATE)
    private val legacy = context.getSharedPreferences("assistant_settings", Context.MODE_PRIVATE)

    fun getAppToken(): String {
        val encrypted = prefs.getString("app_token_cipher", null)
        val iv = prefs.getString("app_token_iv", null)
        if (encrypted != null && iv != null) return decrypt(encrypted, iv)
        val old = legacy.getString("app_token", "").orEmpty()
        if (old.isNotBlank()) { setAppToken(old); legacy.edit().remove("app_token").apply() }
        return old
    }

    fun setAppToken(value: String) {
        if (value.isBlank()) { prefs.edit().remove("app_token_cipher").remove("app_token_iv").apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("app_token_cipher", Base64.encodeToString(encrypted, Base64.NO_WRAP)).putString("app_token_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).apply()
    }

    private fun decrypt(data: String, iv: String): String = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrDefault("")

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }

    companion object { private const val ALIAS = "assistant_app_token_v1" }
}
