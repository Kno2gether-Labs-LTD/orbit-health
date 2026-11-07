package dev.avijit.orbithealth.server

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How the phone trusts its paired server's TLS certificate. Stored with the pairing; records written before this
 * existed have no mode and load as [PIN].
 * - [PIN]: the self-signed leaf whose SHA-256 is [PairedServer.fingerprint] ([PinnedTls]).
 * - [PUBLIC_CA]: the system trust store plus an exact host name check for the paired host ([PublicCaTls]); no
 *   fingerprint is stored.
 */
@Serializable
enum class ServerTrustMode {
    @SerialName("pin") PIN,
    @SerialName("public_ca") PUBLIC_CA,
}

/** Seals small secrets at rest. */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a non-exportable key in AndroidKeyStore (as [dev.avijit.orbithealth.data.DbKey] wraps the database
 * key). Blob = 12-byte IV + ciphertext with tag.
 */
class KeystoreSecretBox(private val alias: String = "orbit_server_key") : SecretBox {
    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_LEN))
        return cipher.doFinal(sealed, IV_LEN, sealed.size - IV_LEN)
    }

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}

/**
 * The paired server (URL, trust mode, pinned fingerprint if any, device token, name, scopes), sealed with [box] in [file], which lives in
 * no-backup storage so it never leaves the phone in a backup. Unreadable state (e.g. the Keystore key is gone) counts
 * as "not paired".
 */
class ServerStore(private val file: File, private val box: SecretBox) {
    private val mutex = Mutex()
    private var loaded = false // guarded by mutex
    private val _paired = MutableStateFlow<PairedServer?>(null)

    /** The paired server; null until [current] (or [save]) has loaded it, and when none is paired. */
    val paired: StateFlow<PairedServer?> = _paired.asStateFlow()

    suspend fun current(): PairedServer? = mutex.withLock { ensureLoaded() }

    suspend fun save(server: PairedServer) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val blob = box.seal(ServerClient.json.encodeToString(PairedServer.serializer(), server).toByteArray(Charsets.UTF_8))
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            // Flushed to disk before the rename, so a crash or power loss never leaves an empty or partial file.
            java.io.FileOutputStream(tmp).use { out ->
                out.write(blob)
                out.fd.sync()
            }
            check(tmp.renameTo(file)) { "could not persist the paired server" }
        }
        loaded = true
        _paired.value = server
    }

    suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) {
            file.delete()
            cursorFile.delete()
        }
        loaded = true
        _paired.value = null
    }

    /**
     * The push cursor: per metric, the newest local sample row id already covered by a push to the server paired as
     * [deviceId]. Null when none is stored for that pairing (a new pairing starts over). Row ids only, no health data
     * or secrets, so it sits beside the sealed file in plain JSON.
     */
    suspend fun pushCursor(deviceId: String): Map<String, Long>? = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                if (!cursorFile.exists()) return@withContext null
                val c = ServerClient.json.decodeFromString(PushCursor.serializer(), cursorFile.readText())
                c.rowIds.takeIf { c.deviceId == deviceId }
            } catch (e: Exception) {
                null // unreadable: the push falls back to the server's watermarks alone
            }
        }
    }

    suspend fun setPushCursor(deviceId: String, rowIds: Map<String, Long>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${cursorFile.name}.tmp")
            java.io.FileOutputStream(tmp).use { out ->
                out.write(ServerClient.json.encodeToString(PushCursor.serializer(), PushCursor(deviceId, rowIds)).toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            check(tmp.renameTo(cursorFile)) { "could not persist the push cursor" }
        }
    }

    private val cursorFile get() = File(file.parentFile, "${file.name}.cursor")

    @Serializable
    private data class PushCursor(val deviceId: String, val rowIds: Map<String, Long>)

    private suspend fun ensureLoaded(): PairedServer? {
        if (!loaded) {
            val (server, ok) = withContext(Dispatchers.IO) { read() }
            _paired.value = server
            // A failed read is not remembered: the next call tries again (the Keystore may be back).
            loaded = ok
        }
        return _paired.value
    }

    /** The stored server (null when none) and whether the read succeeded. */
    private fun read(): Pair<PairedServer?, Boolean> {
        if (!file.exists()) return null to true
        return try {
            ServerClient.json.decodeFromString(PairedServer.serializer(), box.open(file.readBytes()).toString(Charsets.UTF_8)) to true
        } catch (e: Exception) {
            null to false // kept on disk: a transient Keystore failure must not unpair; a new pairing overwrites it
        }
    }
}
