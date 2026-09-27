package dev.forge.ide.model.android

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.forge.ide.model.preset.ModelSecretStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores model credentials encrypted with a key held in the Android Keystore.
 *
 * The key never leaves the Keystore and the ciphertext is written to app-private
 * [SharedPreferences], so a model API key is never persisted in plain text. The
 * plaintext is also kept in memory for the current process so a Keystore that
 * refuses to cooperate (for example after a device restore invalidates the key)
 * degrades to "session only" instead of losing the credential mid-session —
 * [persistent] tells the UI which of the two it is.
 *
 * Nothing here is ever logged.
 */
class KeystoreModelSecretStore(
    context: Context,
    preferencesName: String = DEFAULT_PREFERENCES_NAME,
) : ModelSecretStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    /** Session-only cache, also the fallback when encryption is unavailable. */
    private val memory = LinkedHashMap<String, String>()

    private val keystoreAvailable: Boolean by lazy {
        runCatching {
            val store = KeyStore.getInstance(KEYSTORE_PROVIDER)
            store.load(null)
            true
        }.getOrDefault(false)
    }

    override val persistent: Boolean get() = keystoreAvailable

    override suspend fun put(ref: String, secret: String) {
        memory[ref] = secret
        if (!keystoreAvailable) return
        val payload = runCatching { encrypt(secret) }.getOrNull() ?: return
        prefs.edit().putString(fieldKey(ref), payload).apply()
    }

    override suspend fun get(ref: String): String? {
        memory[ref]?.let { return it }
        if (!keystoreAvailable) return null
        val payload = prefs.getString(fieldKey(ref), null) ?: return null
        val secret = runCatching { decrypt(payload) }.getOrNull() ?: return null
        memory[ref] = secret
        return secret
    }

    override suspend fun remove(ref: String) {
        memory.remove(ref)
        prefs.edit().remove(fieldKey(ref)).apply()
    }

    private fun encrypt(secret: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        return "${encode(cipher.iv)}:$SEPARATOR${encode(encrypted)}"
    }

    private fun decrypt(payload: String): String? {
        val parts = payload.split(SEPARATOR)
        if (parts.size != 2) return null
        val iv = decode(parts[0]) ?: return null
        val body = decode(parts[1]) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String): ByteArray? =
        runCatching { Base64.decode(text, Base64.NO_WRAP) }.getOrNull()

    private fun fieldKey(ref: String): String = "$FIELD_SECRET_PREFIX$ref"

    companion object {
        const val DEFAULT_PREFERENCES_NAME: String = "forge.model-secrets"

        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "forge.model.credentials"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_LENGTH_BITS = 128
        private const val KEY_SIZE_BITS = 256
        private const val SEPARATOR = ":"
        private const val FIELD_SECRET_PREFIX = "models.secret."
    }
}
