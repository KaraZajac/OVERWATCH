package org.soulstone.overwatch.data.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Tiny at-rest secret store for on-device credentials (the Waze proxy token).
 *
 * Values are AES/GCM-encrypted with a key held in the Android Keystore (hardware
 * -backed where available and never exportable), and the ciphertext is kept in
 * app-private SharedPreferences. This protects the token beyond plain prefs —
 * against device backups, rooted-device extraction, and forensic access — so a
 * shared/published APK carries no credential and a compromised backup yields
 * nothing usable.
 *
 * No third-party dependency (Jetpack Security's EncryptedSharedPreferences is in
 * maintenance limbo); this is ~40 lines of standard Keystore AES/GCM instead.
 */
object SecureStore {

    private const val TAG = "SecureStore"
    private const val PREFS = "overwatch_secure"
    private const val KEY_ALIAS = "overwatch_secret_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val GCM_TAG_BITS = 128

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    /** Encrypt + persist [value] under [name]. Empty string clears the entry. */
    fun put(context: Context, name: String, value: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (value.isEmpty()) {
            prefs.edit { remove(name) }
            return
        }
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            // Prepend the (12-byte) IV so decryption is self-contained.
            val blob = Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
            prefs.edit { putString(name, blob) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to store secret '$name': ${e.message}")
        }
    }

    /** Decrypt the value stored under [name], or null if absent/undecryptable. */
    fun get(context: Context, name: String): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val blob = prefs.getString(name, null) ?: return null
        return try {
            val bytes = Base64.decode(blob, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, IV_LEN)
            val ct = bytes.copyOfRange(IV_LEN, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read secret '$name': ${e.message}")
            null
        }
    }
}
