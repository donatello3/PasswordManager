package com.example.passwordmanager.utils

import android.content.Context
import com.example.passwordmanager.data.database.PasswordEntry
import com.google.gson.Gson
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

object EncryptionManager {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
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
        return android.util.Base64.encodeToString(combined, android.util.Base64.DEFAULT)
    }

    fun decryptEntry(context: Context, encryptedData: String, masterPassword: String): PasswordEntry? {
        val key = getEncryptionKey(context, masterPassword) ?: return null
        val combined = android.util.Base64.decode(encryptedData, android.util.Base64.DEFAULT)
        val iv = combined.copyOfRange(0, 12)
        val encryptedBytes = combined.copyOfRange(12, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = javax.crypto.spec.GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        val decryptedBytes = cipher.doFinal(encryptedBytes)
        val json = String(decryptedBytes)
        return gson.fromJson(json, PasswordEntry::class.java)
    }

    private fun getEncryptionKey(context: Context, masterPassword: String): SecretKey? {
        // Derive a 256-bit key from master password using PBKDF2 (same as CryptoManager)
        // For simplicity, reuse CryptoManager's key derivation. We'll add a method in CryptoManager.
        val keyBytes = CryptoManager.getDatabaseKey(context, masterPassword) ?: return null
        // Take first 32 bytes (256 bits) for AES
        return SecretKeySpec(keyBytes.copyOf(32), "AES")
    }
}