package com.hienlh.ppm.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.edit
import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.client.SecretStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** SharedPreferences as the core's [KeyValueStore]. */
class PrefsStore(context: Context, name: String = "ppm") : KeyValueStore {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String?) = prefs.edit { if (value == null) remove(key) else putString(key, value) }
}

/**
 * Device-local preferences that stay on this device (not synced to the server).
 *
 * [redesign] toggles the mobile & tablet redesign (default `false`; exposed in Settings -> About
 * on debug builds until Task 23 enables it by default).
 */
class LocalPrefs(private val store: KeyValueStore) {
    private var state by mutableStateOf(readRedesign(store))

    var redesign: Boolean
        get() = state
        set(value) {
            state = value
            writeRedesign(store, value)
            sharedState = value
        }

    fun refresh(): Boolean {
        val current = readRedesign(store)
        state = current
        sharedState = current
        return current
    }

    companion object {
        const val REDESIGN_KEY = "redesign"
        const val DEFAULT_REDESIGN = false

        private var boundStore: KeyValueStore? = null
        private var sharedState by mutableStateOf(DEFAULT_REDESIGN)

        fun readRedesign(store: KeyValueStore): Boolean =
            store.get(REDESIGN_KEY)?.toBooleanStrictOrNull() ?: DEFAULT_REDESIGN

        fun writeRedesign(store: KeyValueStore, enabled: Boolean) {
            store.put(REDESIGN_KEY, enabled.toString())
        }

        fun bind(store: KeyValueStore): LocalPrefs {
            boundStore = store
            sharedState = readRedesign(store)
            return LocalPrefs(store)
        }

        fun of(context: Context): LocalPrefs = bind(PrefsStore(context.applicationContext ?: context))

        var redesign: Boolean
            get() = sharedState
            set(value) {
                sharedState = value
                boundStore?.let { writeRedesign(it, value) }
            }
    }
}

/** Convenience accessor on any [KeyValueStore] for the local redesign toggle. */
var KeyValueStore.redesign: Boolean
    get() = LocalPrefs.readRedesign(this)
    set(value) {
        LocalPrefs.writeRedesign(this, value)
        LocalPrefs.redesign = value
    }

/** Whether the mobile & tablet redesign is active in the current composition tree. */
val LocalRedesign = staticCompositionLocalOf { LocalPrefs.DEFAULT_REDESIGN }


/**
 * Passwords sealed with an AES-GCM key that never leaves the Android Keystore; the sealed bytes
 * (IV first) sit in a SharedPreferences file of their own. A key the system no longer has reads
 * as no password, so the app asks for it again.
 */
class KeystoreSecrets(context: Context) : SecretStore {
    private val prefs = context.getSharedPreferences("ppm_secrets", Context.MODE_PRIVATE)

    override fun read(account: String): String? {
        val stored = prefs.getString(account, null) ?: return null
        return runCatching {
            val sealed = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
            String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
        }.getOrNull()
    }

    override fun write(account: String, secret: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        prefs.edit { putString(account, Base64.encodeToString(sealed, Base64.NO_WRAP)) }
    }

    override fun delete(account: String) = prefs.edit { remove(account) }

    private fun key(): SecretKey {
        val keystore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keystore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "ppm-server-passwords"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
