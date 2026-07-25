package io.kmanager.app.utils

import android.content.Context
import android.util.Base64
import io.kmanager.app.data.database.PasswordEntry
import com.google.gson.Gson
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object EncryptionManager {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PBKDF2_ITERATIONS = 100_000
    private const val KEY_LENGTH_BITS = 256
    private val gson = Gson()

    fun encryptEntry(context: Context, entry: PasswordEntry, masterPassword: String): String? {
        val key = getEncryptionKey(context, masterPassword) ?: return null
        val json = gson.toJson(entry)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encryptedBytes = cipher.doFinal(json.toByteArray())
        // Prepend IV (first 12 bytes of cipher's IV)
        val iv = cipher.iv
        val combined = iv + encryptedBytes
        return Base64.encodeToString(combined, Base64.DEFAULT)
    }

    fun decryptEntry(context: Context, encryptedData: String, masterPassword: String): PasswordEntry? {
        val key = getEncryptionKey(context, masterPassword) ?: return null
        val combined = Base64.decode(encryptedData, Base64.DEFAULT)
        val iv = combined.copyOfRange(0, 12)
        val encryptedBytes = combined.copyOfRange(12, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        val decryptedBytes = cipher.doFinal(encryptedBytes)
        val json = String(decryptedBytes)
        return gson.fromJson(json, PasswordEntry::class.java)
    }

    /**
     * Encrypts [plaintext] using AES-GCM with a key derived from [password] and [salt].
     * Does not require Context — suitable for key verification stored in Firestore.
     */
    fun encryptString(plaintext: String, password: String, salt: ByteArray): String {
        val key = deriveKeyFromPassword(password, salt)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = cipher.iv + encrypted
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * Decrypts a string previously encrypted with [encryptString].
     * Returns null if decryption fails (wrong key / corrupted data).
     */
    fun decryptString(encrypted: String, password: String, salt: ByteArray): String? {
        return try {
            val key = deriveKeyFromPassword(password, salt)
            val combined = Base64.decode(encrypted, Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, 12)
            val data = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun deriveKeyFromPassword(password: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun getEncryptionKey(context: Context, masterPassword: String): SecretKey? {
        // Derive a 256-bit key from master password using PBKDF2 (same as CryptoManager)
        // For simplicity, reuse CryptoManager's key derivation. We'll add a method in CryptoManager.
        val keyBytes = CryptoManager.getDatabaseKey(context, masterPassword) ?: return null
        // Take first 32 bytes (256 bits) for AES
        return SecretKeySpec(keyBytes.copyOf(32), "AES")
    }
}