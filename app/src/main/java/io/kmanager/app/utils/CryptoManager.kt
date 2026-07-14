package io.kmanager.app.utils
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
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

    // Biometric-bound Keystore key for encrypting the master password
    private const val BIOMETRIC_KEY_ALIAS = "kmanager_biometric_key"
    private const val BIOMETRIC_PREFS_NAME = "biometric_prefs"
    private const val BIOMETRIC_KEY_IV = "biometric_iv"
    private const val BIOMETRIC_KEY_CIPHERTEXT = "biometric_ciphertext"
    private const val GCM_TAG_LENGTH = 128

    private const val ITERATIONS = 100_000
    private const val KEY_LENGTH = 256

    /**
     * Удаляет повреждённые данные EncryptedSharedPreferences и ключ из Android KeyStore.
     * Вызывается когда EncryptedSharedPreferences не может расшифровать данные
     * (например, после переустановки приложения или восстановления из бэкапа).
     */
    internal fun clearCorruptedData(context: Context) {
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

    /**
     * Генерирует криптографически случайную соль (32 байта).
     */
    fun generateSalt(): ByteArray {
        val salt = ByteArray(32)
        SecureRandom().nextBytes(salt)
        return salt
    }

    /**
     * Детерминированная соль на основе email — используется ТОЛЬКО для миграции
     * существующих аккаунтов, у которых соль ещё не сохранена в Firestore.
     */
    fun getLegacySalt(email: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(email.toByteArray())
    }

    /**
     * Настраивает локальный аккаунт с явно переданной солью.
     * Соль должна быть получена из Firestore (при входе) либо сгенерирована
     * при регистрации и после этого загружена в Firestore, чтобы обеспечить
     * одинаковое шифрование на всех устройствах пользователя.
     */
    fun setupAccount(context: Context, email: String, password: String, salt: ByteArray): ByteArray {
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

    // ── Biometric-bound Keystore key ─────────────────────────────────────────

    /**
     * Creates (or retrieves) a Keystore AES-GCM key that requires biometric authentication
     * to use. Even on a rooted device, this key cannot be used without the user's fingerprint.
     */
    private fun getOrCreateBiometricKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        keyStore.getKey(BIOMETRIC_KEY_ALIAS, null)?.let { return it as SecretKey }

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        keyGen.init(
            KeyGenParameterSpec.Builder(
                BIOMETRIC_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                // Invalidate key if new biometrics are enrolled — forces re-setup
                .setInvalidatedByBiometricEnrollment(true)
                .build()
        )
        return keyGen.generateKey()
    }

    /**
     * Returns a Cipher ready for encryption (new random IV).
     * Pass this as CryptoObject to BiometricPrompt.
     * After successful biometric auth, call [encryptWithCipher].
     */
    fun prepareBiometricEncryptCipher(): Cipher {
        val key = getOrCreateBiometricKey()
        val cipher = Cipher.getInstance("${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_GCM}/${KeyProperties.ENCRYPTION_PADDING_NONE}")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher
    }

    /**
     * Returns a Cipher ready for decryption using the stored IV.
     * Pass this as CryptoObject to BiometricPrompt.
     * After successful biometric auth, call [decryptWithCipher].
     *
     * Returns null if:
     * - no encrypted password is stored yet
     * - the biometric key has been invalidated (new biometrics enrolled)
     */
    fun prepareBiometricDecryptCipher(context: Context): Cipher? {
        val iv = getBiometricIv(context) ?: return null
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
            val key = keyStore.getKey(BIOMETRIC_KEY_ALIAS, null) as? SecretKey ?: return null
            val cipher = Cipher.getInstance("${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_GCM}/${KeyProperties.ENCRYPTION_PADDING_NONE}")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            cipher
        } catch (e: KeyPermanentlyInvalidatedException) {
            Log.w(TAG, "Biometric key invalidated (new biometrics enrolled). Disabling biometric unlock.")
            clearBiometricData(context)
            null
        } catch (e: InvalidKeyException) {
            Log.w(TAG, "Biometric key invalid, disabling biometric unlock", e)
            clearBiometricData(context)
            null
        }
    }

    /**
     * Encrypts [password] with the authenticated [cipher] and stores ciphertext + IV.
     * Call only from BiometricPrompt.AuthenticationCallback.onAuthenticationSucceeded.
     */
    fun encryptWithCipher(context: Context, cipher: Cipher, password: String) {
        val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        getBiometricPrefs(context).edit()
            .putString(BIOMETRIC_KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .putString(BIOMETRIC_KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    /**
     * Decrypts and returns the master password using the authenticated [cipher].
     * Call only from BiometricPrompt.AuthenticationCallback.onAuthenticationSucceeded.
     */
    fun decryptWithCipher(context: Context, cipher: Cipher): String? {
        val ciphertext = getBiometricCiphertext(context) ?: return null
        return try {
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "decryptWithCipher failed", e)
            null
        }
    }

    fun hasBiometricPasswordStored(context: Context): Boolean {
        return getBiometricIv(context) != null && getBiometricCiphertext(context) != null
    }

    private fun getBiometricIv(context: Context): ByteArray? {
        val str = getBiometricPrefs(context).getString(BIOMETRIC_KEY_IV, null) ?: return null
        return Base64.decode(str, Base64.NO_WRAP)
    }

    private fun getBiometricCiphertext(context: Context): ByteArray? {
        val str = getBiometricPrefs(context).getString(BIOMETRIC_KEY_CIPHERTEXT, null) ?: return null
        return Base64.decode(str, Base64.NO_WRAP)
    }

    private fun getBiometricPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(BIOMETRIC_PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun clearBiometricData(context: Context) {
        getBiometricPrefs(context).edit().clear().apply()
        setBiometricEnabled(context, false)
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
            if (keyStore.containsAlias(BIOMETRIC_KEY_ALIAS)) keyStore.deleteEntry(BIOMETRIC_KEY_ALIAS)
        } catch (e: Exception) {
            Log.e(TAG, "clearBiometricData: failed to delete key", e)
        }
    }

    // ── Legacy — kept for migration compatibility, will be cleaned up ─────────

    @Deprecated("Use encryptWithCipher / decryptWithCipher instead")
    fun saveMasterPasswordForBiometric(context: Context, password: String) {
        // No-op: replaced by biometric-bound key approach
        Log.w(TAG, "saveMasterPasswordForBiometric called — this is a no-op, migrate to encryptWithCipher")
    }

    @Deprecated("Use prepareBiometricDecryptCipher + decryptWithCipher instead")
    fun getMasterPasswordForBiometric(context: Context): String? = null

    fun clearSession(context: Context) {
        try {
            getEncryptedPrefs(context).edit().clear().apply()
        } catch (e: Exception) {
            Log.e(TAG, "clearSession failed, clearing corrupted data", e)
            clearCorruptedData(context)
        }
    }

}