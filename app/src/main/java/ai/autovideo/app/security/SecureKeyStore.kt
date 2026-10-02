package ai.autovideo.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts API credentials with an AES-GCM key that never leaves Android Keystore. */
class SecureKeyStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("secure_api_keys", Context.MODE_PRIVATE)

    @Synchronized
    fun put(provider: String, value: String) {
        if (value.isBlank()) {
            remove(provider)
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(provider.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(ivKey(provider), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(valueKey(provider), Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    @Synchronized
    fun get(provider: String): String? {
        val iv = preferences.getString(ivKey(provider), null)?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?: return null
        val encrypted = preferences.getString(valueKey(provider), null)?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            cipher.updateAAD(provider.toByteArray(Charsets.UTF_8))
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        }.getOrElse {
            remove(provider)
            null
        }
    }

    @Synchronized
    fun remove(provider: String) {
        preferences.edit().remove(ivKey(provider)).remove(valueKey(provider)).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun ivKey(provider: String) = "${provider}_iv"
    private fun valueKey(provider: String) = "${provider}_ciphertext"

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "autovideo_ai_api_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
