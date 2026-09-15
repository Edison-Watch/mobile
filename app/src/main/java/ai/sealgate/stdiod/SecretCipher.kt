package ai.sealgate.stdiod

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets (the tunnel credential, the in-flight PKCE verifier)
 * at rest using an AES-256-GCM key held in the AndroidKeyStore.
 *
 * The key never leaves the Keystore (hardware-backed on devices with a TEE or
 * StrongBox), so the ciphertext stored in SharedPreferences is useless off the
 * device: it cannot be read from a backup restored to another phone, nor from a
 * `run-as`/root dump of the prefs file without also compromising the Keystore.
 * That is a deliberate step up from storing the `ewc_` bearer token in plain
 * text, and it makes an `allowBackup` copy of the prefs inert.
 *
 * Values are stored as `v1:` + base64(iv ‖ ciphertext‖GCM-tag). A stored value
 * without the prefix is treated as legacy plaintext and returned as-is, so an
 * existing sign-in survives the upgrade and is re-encrypted the next time it is
 * saved. A value that fails to decrypt (e.g. the Keystore key is gone after a
 * cross-device restore) yields null, which the callers treat as "signed out" -
 * the correct outcome for a credential that must not survive off its device.
 */
object SecretCipher {

    /** Wrap a plaintext secret for storage. */
    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val blob = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, blob, 0, iv.size)
        System.arraycopy(ciphertext, 0, blob, iv.size, ciphertext.size)
        return PREFIX + Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    /**
     * Unwrap a value produced by [encrypt]. Returns a legacy (unprefixed)
     * plaintext value unchanged, and null when a prefixed value cannot be
     * decrypted (corrupt, or the key is no longer available).
     */
    fun decrypt(stored: String): String? {
        if (!stored.startsWith(PREFIX)) return stored
        return try {
            val blob = Base64.decode(stored.substring(PREFIX.length), Base64.NO_WRAP)
            if (blob.size <= IV_LENGTH) return null
            val iv = blob.copyOfRange(0, IV_LENGTH)
            val ciphertext = blob.copyOfRange(IV_LENGTH, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "failed to decrypt stored secret; treating as signed out", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "stored secret was not valid base64", e)
            null
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private const val TAG = "SecretCipher"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "sealgate_secret_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    // AES-GCM standard nonce length; the Keystore generates it on encrypt.
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128
    private const val PREFIX = "v1:"
}
