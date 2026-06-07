package com.example.passwordmanager.utils
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object CryptoManager {
    private const val TAG = "CryptoManager"
    private const val PREF_NAME = "crypto_prefs"
    private const val KEY_SALT = "salt"
    private const val KEY_HASH = "hash"
    private const val KEY_EMAIL = "email"
    private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
    private const val KEY_MASTER_PASSWORD_BIOMETRIC = "master_pwd_biometric"
    private const val MASTER_KEY_ALIAS = "_androidx_security_master_key"

    private const val ITERATIONS = 100_000
    private const val KEY_LENGTH = 256

    /**
     * Удаляет повреждённые данные EncryptedSharedPreferences и ключ из Android KeyStore.
     * Вызывается когда EncryptedSharedPreferences не может расшифровать данные
     * (например, после переустановки приложения или восстановления из бэкапа).
     */
    private fun clearCorruptedData(context: Context) {
        Log.w(TAG, "Clearing corrupted EncryptedSharedPreferences data")
        try {
            // Удаляем файл shared prefs
            val prefsFile = File(context.applicationInfo.dataDir + "/shared_prefs/" + PREF_NAME + ".xml")
            if (prefsFile.exists()) {
                prefsFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete prefs file", e)
        }
        try {
            // Удаляем ключ из Android KeyStore
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            if (keyStore.containsAlias(MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(MASTER_KEY_ALIAS)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete KeyStore entry", e)
        }
        try {
            // Удаляем файл базы данных — он зашифрован старым ключом и недоступен.
            // MIUI/OEM backup мог восстановить его после переустановки с другим ключом.
            context.applicationContext.deleteDatabase("password_manager.db")
            Log.w(TAG, "Deleted stale database file")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete database file", e)
        }
    }

    /**
     * Открывает EncryptedSharedPreferences. Если данные повреждены — очищает их и пересоздаёт.
     * Выполняет до 3 попыток с очисткой между ними, чтобы справиться с восстановлением данных
     * из OEM-бэкапов (MIUI и др.), которые могут восстановить SharedPreferences-файл
     * без соответствующего ключа из Android Keystore.
     */
    private fun getEncryptedPrefs(context: Context): SharedPreferences {
        var lastException: Exception? = null
        repeat(3) { attempt ->
            try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                return EncryptedSharedPreferences.create(
                    context,
                    PREF_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "EncryptedSharedPreferences failed (attempt ${attempt + 1}/3): ${e.message}")
                clearCorruptedData(context)
            }
        }
        throw lastException ?: IllegalStateException("EncryptedSharedPreferences creation failed")
    }

    @Deprecated("")
    fun setupMasterPassword(context: Context, masterPassword: String): ByteArray {
        // Generate a random salt
        val salt = ByteArray(32)
        SecureRandom().nextBytes(salt)

        // Derive key
        val key = deriveKey(masterPassword, salt)

        // Hash the master password for verification
        val hash = hashPassword(masterPassword, salt)

        // Store salt and hash securely using EncryptedSharedPreferences
        val sharedPrefs = getEncryptedPrefs(context)
        sharedPrefs.edit()
            .putString(KEY_SALT, salt.joinToString(",") { it.toString() })
            .putString(KEY_HASH, hash.joinToString(",") { it.toString() })
            .apply()

        return key
    }

    fun setupAccount(context: Context, email: String, password: String): ByteArray {
        val salt = getDeterministicSalt(email)

        // Derive key from password
        val key = deriveKey(password, salt)

        // Hash password for verification
        val hash = hashPassword(password, salt)

        // Проактивно очищаем возможные остатки от предыдущей установки (MIUI-бэкап и т.д.)
        // перед созданием нового хранилища, чтобы гарантировать чистое состояние.
        clearCorruptedData(context)

        // Store salt, hash, and email in EncryptedSharedPreferences
        val sharedPrefs = getEncryptedPrefs(context)
        sharedPrefs.edit()
            .putString(KEY_SALT, salt.joinToString(",") { it.toString() })
            .putString(KEY_HASH, hash.joinToString(",") { it.toString() })
            .putString(KEY_EMAIL, email)
            .apply()

        return key
    }

    fun verifyAccount(context: Context, email: String, password: String): Boolean {
        return try {
            val sharedPrefs = getEncryptedPrefs(context)
            val storedEmail = sharedPrefs.getString(KEY_EMAIL, null) ?: return false
            if (storedEmail != email) return false

            val saltStr = sharedPrefs.getString(KEY_SALT, null) ?: return false
            val hashStr = sharedPrefs.getString(KEY_HASH, null) ?: return false

            val salt = saltStr.split(",").map { it.toByte() }.toByteArray()
            val storedHash = hashStr.split(",").map { it.toByte() }.toByteArray()

            val derivedHash = hashPassword(password, salt)
            derivedHash.contentEquals(storedHash)
        } catch (e: Exception) {
            Log.e(TAG, "verifyAccount failed", e)
            false
        }
    }

    fun getLoggedInEmail(context: Context): String? {
        return try {
            getEncryptedPrefs(context).getString(KEY_EMAIL, null)
        } catch (e: Exception) {
            Log.e(TAG, "getLoggedInEmail failed", e)
            null
        }
    }

    fun isMasterPasswordSet(context: Context): Boolean {
        return try {
            val sharedPrefs = getEncryptedPrefs(context)
            sharedPrefs.contains(KEY_SALT) && sharedPrefs.contains(KEY_HASH)
        } catch (e: Exception) {
            // getEncryptedPrefs сам обработал ошибку и пересоздал prefs,
            // значит данных нет → пароль не установлен
            Log.w(TAG, "isMasterPasswordSet: returning false after error: ${e.message}")
            false
        }
    }

    @Deprecated("")
    fun verifyMasterPassword(context: Context, masterPassword: String): Boolean {
        return try {
            val sharedPrefs = getEncryptedPrefs(context)
            val saltStr = sharedPrefs.getString(KEY_SALT, null) ?: return false
            val hashStr = sharedPrefs.getString(KEY_HASH, null) ?: return false

            val salt = saltStr.split(",").map { it.toByte() }.toByteArray()
            val storedHash = hashStr.split(",").map { it.toByte() }.toByteArray()

            val derivedHash = hashPassword(masterPassword, salt)
            derivedHash.contentEquals(storedHash)
        } catch (e: Exception) {
            Log.e(TAG, "verifyMasterPassword failed", e)
            false
        }
    }

    fun verifyPasswordOnly(context: Context, password: String): Boolean {
        return try {
            val sharedPrefs = getEncryptedPrefs(context)
            // Check if email exists (meaning account is set up)
            val storedEmail = sharedPrefs.getString(KEY_EMAIL, null) ?: return false
            val saltStr = sharedPrefs.getString(KEY_SALT, null) ?: return false
            val hashStr = sharedPrefs.getString(KEY_HASH, null) ?: return false

            val salt = saltStr.split(",").map { it.toByte() }.toByteArray()
            val storedHash = hashStr.split(",").map { it.toByte() }.toByteArray()

            val derivedHash = hashPassword(password, salt)
            derivedHash.contentEquals(storedHash)
        } catch (e: Exception) {
            Log.e(TAG, "verifyPasswordOnly failed", e)
            false
        }
    }

    fun getDatabaseKey(context: Context, masterPassword: String): ByteArray? {
        return try {
            val sharedPrefs = getEncryptedPrefs(context)
            val saltStr = sharedPrefs.getString(KEY_SALT, null) ?: return null
            val salt = saltStr.split(",").map { it.toByte() }.toByteArray()
            deriveKey(masterPassword, salt)
        } catch (e: Exception) {
            Log.e(TAG, "getDatabaseKey failed", e)
            null
        }
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

    fun setBiometricEnabled(context: Context, enabled: Boolean) {
        try {
            getEncryptedPrefs(context).edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply()
        } catch (e: Exception) {
            Log.e(TAG, "setBiometricEnabled failed", e)
        }
    }

    fun isBiometricEnabled(context: Context): Boolean {
        return try {
            getEncryptedPrefs(context).getBoolean(KEY_BIOMETRIC_ENABLED, false)
        } catch (e: Exception) {
            false
        }
    }

    fun saveMasterPasswordForBiometric(context: Context, password: String) {
        try {
            getEncryptedPrefs(context).edit().putString(KEY_MASTER_PASSWORD_BIOMETRIC, password).apply()
        } catch (e: Exception) {
            Log.e(TAG, "saveMasterPasswordForBiometric failed", e)
        }
    }

    fun getMasterPasswordForBiometric(context: Context): String? {
        return try {
            getEncryptedPrefs(context).getString(KEY_MASTER_PASSWORD_BIOMETRIC, null)
        } catch (e: Exception) {
            null
        }
    }

    fun clearSession(context: Context) {
        try {
            getEncryptedPrefs(context).edit().clear().apply()
        } catch (e: Exception) {
            Log.e(TAG, "clearSession failed, clearing corrupted data", e)
            clearCorruptedData(context)
        }
    }

    private fun getDeterministicSalt(email: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(email.toByteArray())
    }
}