package io.kmanager.app.utils

import android.content.Context
import android.util.Base64
import android.util.Log
import io.kmanager.app.data.database.PasswordEntry
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonSerializer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object EncryptionManager {
    private const val TAG = "EncryptionManager"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PBKDF2_ITERATIONS = 100_000
    private const val KEY_LENGTH_BITS = 256

    // Форматы для лениентного разбора СТАРЫХ записей, сохранённых до перехода
    // на epoch millis (см. ниже) — DefaultDateTypeAdapter Gson формирует строку
    // по-разному в зависимости от локали/версии ICU конкретного устройства,
    // поэтому запись, созданная на одном устройстве, могла не парситься на другом.
    private val legacyDateFormats = listOf(
        "MMM d, yyyy, h:mm:ss a",
        "MMM d, yyyy HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
    ).map { SimpleDateFormat(it, Locale.US) }

    // Gson с детерминированной, не зависящей от локали/устройства сериализацией Date:
    // храним как epoch millis (число), а не как отформатированную строку.
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(Date::class.java, JsonSerializer<Date> { src, _, _ ->
            com.google.gson.JsonPrimitive(src.time)
        })
        .registerTypeAdapter(Date::class.java, JsonDeserializer { json, _, _ ->
            val primitive = json.asJsonPrimitive
            if (primitive.isNumber) {
                Date(primitive.asLong)
            } else {
                // Legacy-запись (создана до фикса) — пробуем разобрать известные форматы,
                // при неудаче не роняем всю запись, а используем текущее время.
                val str = primitive.asString
                legacyDateFormats.firstNotNullOfOrNull {
                    try { it.parse(str) } catch (_: Exception) { null }
                } ?: run {
                    Log.w(TAG, "Failed to parse legacy date '$str', falling back to now()")
                    Date()
                }
            }
        })
        .create()

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