package com.keelmobile.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets stay in Android Keystore (AES-256-GCM wrapped values stored in private
 * app SharedPreferences excluded from backups). Never logged, never placed in prompts
 * or URLs.
 */
interface CredentialStore {
    fun put(key: String, secret: String)
    fun get(key: String): String?
    fun remove(key: String)
    fun has(key: String): Boolean = !get(key).isNullOrEmpty()
}

class AndroidKeystoreCredentialStore(context: Context) : CredentialStore {
    private val prefs = context.getSharedPreferences("keel_keystore_vault", Context.MODE_PRIVATE)
    private val fallbackMemory = mutableMapOf<String, String>()

    override fun put(key: String, secret: String) {
        val trimmed = secret.trim()
        if (trimmed.isEmpty()) {
            remove(key)
            return
        }
        val secretKey = getOrCreateKey()
        if (secretKey == null) {
            // Robolectric / JVM test environment where AndroidKeyStore provider is absent
            fallbackMemory[key] = trimmed
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(1 + iv.size + ciphertext.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(ciphertext, 0, combined, 1 + iv.size, ciphertext.size)
        val encoded = Base64.encodeToString(combined, Base64.NO_WRAP)
        prefs.edit().putString(key, encoded).apply()
    }

    override fun get(key: String): String? {
        val secretKey = getOrCreateKey() ?: return fallbackMemory[key]
        val encoded = prefs.getString(key, null) ?: return null
        return runCatching {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            val ivLen = combined[0].toInt() and 0xFF
            val iv = combined.copyOfRange(1, 1 + ivLen)
            val ciphertext = combined.copyOfRange(1 + ivLen, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    override fun remove(key: String) {
        fallbackMemory.remove(key)
        prefs.edit().remove(key).apply()
    }

    private fun getOrCreateKey(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey) ?: run {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            gen.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            gen.generateKey()
        }
    }.getOrNull()

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "keel_mobile_vault_aes256"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        const val KEY_GITHUB_TOKEN = "github_token"
        const val KEY_BRAVE_SEARCH = "brave_search_api_key"
        fun mcpTokenKey(serverId: String): String = "mcp_token_$serverId"
    }
}
