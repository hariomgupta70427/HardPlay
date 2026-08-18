package com.hardplay.telegram.tdlib

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.SecureRandom

/**
 * Supplies TDLib's `databaseEncryptionKey`.
 *
 * TDLib will happily run with an empty key, and its official Android example
 * does. That leaves the authenticated session — the one asset here whose loss
 * actually matters — as a readable file. So a 32-byte key is generated once,
 * sealed with an AES-GCM key that lives in the platform keystore, and the sealed
 * blob is all that touches disk.
 *
 * What this buys: a copy of the app's data directory, however obtained, is inert
 * off-device, because the unwrapping key is hardware-backed and non-exportable.
 * What it does not buy: protection from code running as this app on this device.
 * That is the biometric gate's job, not this file's, and pretending otherwise is
 * how security theatre starts.
 *
 * `setUserAuthenticationRequired` is deliberately off. Background sync has to be
 * able to open the session while the phone is locked, and requiring auth here
 * would trade a working sync for a guarantee the gate already provides.
 */
internal class TdlibSessionKey(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * @return the 32-byte key, stable for the life of the install.
     *
     * Falls back to a plain random key held only in this process if the keystore
     * is unavailable or has been invalidated. That costs session persistence —
     * TDLib will not open a database whose key changed, so the user re-logs in —
     * which is the correct failure: worse than seamless, better than either
     * crashing on launch or silently writing the key out in the clear.
     */
    fun obtain(): ByteArray = runCatching { unsealOrCreate() }
        .getOrElse { ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) } }

    private fun unsealOrCreate(): ByteArray {
        val stored = prefs.getString(KEY_SEALED, null)
        if (stored != null) {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            if (blob.size > GCM_IV_BYTES) return unseal(blob)
        }
        val fresh = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_SEALED, Base64.encodeToString(seal(fresh), Base64.NO_WRAP))
            .apply()
        return fresh
    }

    private fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        // GCM generates its own IV; prepend it so unseal can recover it.
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun unseal(blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            wrappingKey(),
            GCMParameterSpec(GCM_TAG_BITS, blob, 0, GCM_IV_BYTES),
        )
        return cipher.doFinal(blob, GCM_IV_BYTES, blob.size - GCM_IV_BYTES)
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "hardplay.session.wrap.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFS = "hardplay_session"
        const val KEY_SEALED = "tdlib_key_sealed"
        const val KEY_BYTES = 32
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
