package com.example.passwordmanager.utils
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object CryptoManager {
    private const val PREF_NAME = "crypto_prefs"
    private const val KEY_SALT = "salt"
    private const val KEY_HASH = "hash"

    private const val ITERATIONS = 100_000
    private const val KEY_LENGTH = 256

    fun setupMasterPassword(context: Context, masterPassword: String): ByteArray {
        // Generate a random salt
        val salt = ByteArray(32)
        SecureRandom().nextBytes(salt)

        // Derive key
        val key = deriveKey(masterPassword, salt)

        // Hash the master password for verification
        val hash = hashPassword(masterPassword, salt)

        // Store salt and hash securely using EncryptedSharedPreferences
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val sharedPrefs = EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        sharedPrefs.edit()
            .putString(KEY_SALT, salt.joinToString(",") { it.toString() })
            .putString(KEY_HASH, hash.joinToString(",") { it.toString() })
            .apply()

        return key
    }

    fun isMasterPasswordSet(context: Context): Boolean {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val sharedPrefs = EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        return sharedPrefs.contains(KEY_SALT) && sharedPrefs.contains(KEY_HASH)
    }

    fun verifyMasterPassword(context: Context, masterPassword: String): Boolean {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val sharedPrefs = EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        val saltStr = sharedPrefs.getString(KEY_SALT, null) ?: return false
        val hashStr = sharedPrefs.getString(KEY_HASH, null) ?: return false

        val salt = saltStr.split(",").map { it.toByte() }.toByteArray()
        val storedHash = hashStr.split(",").map { it.toByte() }.toByteArray()

        val derivedHash = hashPassword(masterPassword, salt)
        return derivedHash.contentEquals(storedHash)
    }

    fun getDatabaseKey(context: Context, masterPassword: String): ByteArray? {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val sharedPrefs = EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        val saltStr = sharedPrefs.getString(KEY_SALT, null) ?: return null
        val salt = saltStr.split(",").map { it.toByte() }.toByteArray()
        return deriveKey(masterPassword, salt)
    }

    private fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    private fun hashPassword(password: String, salt: ByteArray): ByteArray {
        // Use a fast hash for verification (SHA-256 is fine)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(password.toByteArray())
        return digest.digest()
    }
}