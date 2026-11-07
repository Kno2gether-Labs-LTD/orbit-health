package dev.avijit.orbithealth.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 32 random bytes used as the SQLCipher passphrase. Wrapped with a non-exportable AES-256-GCM key in
 * AndroidKeyStore; the wrapped form (IV + ciphertext) lives in no-backup storage. Never log the key.
 */
object DbKey {
    private const val ALIAS = "orbit_db_key"
    private const val FILE = "db.key"
    private const val IV_LEN = 12

    @Synchronized
    fun get(context: Context): ByteArray {
        val file = File(context.noBackupFilesDir, FILE)
        if (file.exists()) {
            val blob = file.readBytes()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, blob, 0, IV_LEN))
            return cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN)
        }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey())
        val blob = cipher.iv + cipher.doFinal(key)
        val tmp = File(file.parentFile, "$FILE.tmp")
        tmp.writeBytes(blob)
        check(tmp.renameTo(file)) { "could not persist db key" }
        return key
    }

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }
}
