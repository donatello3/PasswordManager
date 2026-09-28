package io.kmanager.app.data.remote

import android.content.Context
import android.util.Base64
import android.util.Log
import io.kmanager.app.data.database.PasswordEntry
import io.kmanager.app.utils.EncryptionManager
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

class FirestoreDataSource(private val context: Context) {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val currentUserEmail: String?
        get() = auth.currentUser?.email

    private val currentUserId: String?
        get() = auth.currentUser?.uid

    companion object {
        private const val TAG = "FirestoreDataSource"
        private const val KEY_VERIFIER_PLAINTEXT = "VAULT_OK"
    }

    suspend fun signInWithEmail(email: String, password: String): Boolean {
        return try {
            auth.signInWithEmailAndPassword(email, password).await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "signInWithEmail failed", e)
            false
        }
    }

    suspend fun uploadEntry(entry: PasswordEntry, masterPassword: CharArray?): String? {
        if (!entry.syncEnabled) return null
        val uid = currentUserId ?: return null
        if (masterPassword == null || masterPassword.isEmpty()) return null
        val passwordString = String(masterPassword)
        val encrypted = EncryptionManager.encryptEntry(context, entry, passwordString) ?: return null
        val data = mapOf(
            "encryptedData" to encrypted,
            // Локальные часы устройства (сохраняется для обратной совместимости
            // со старыми документами / как fallback, если serverTimestamp почему-то не резолвится).
            "lastModified" to entry.lastModified,
            // Авторитетное время для разрешения конфликтов между устройствами —
            // назначается сервером Firestore, НЕ зависит от часов устройства-отправителя.
            // Это устраняет баг с "устаревшими данными", когда часы одного из устройств
            // (особенно эмуляторов) отстают/спешат относительно других.
            "serverTimestamp" to FieldValue.serverTimestamp()
        )
        val docRef = if (entry.remoteId != null) {
            db.collection("users").document(uid).collection("passwords").document(entry.remoteId)
        } else {
            db.collection("users").document(uid).collection("passwords").document()
        }
        return try {
            docRef.set(data).await()
            docRef.id
        } catch (e: Exception) {
            Log.e(TAG, "uploadEntry failed", e)
            null
        }
    }

    suspend fun deleteRemoteEntry(entry: PasswordEntry) {
        if (entry.remoteId == null) return
        val uid = currentUserId ?: return
        try {
            db.collection("users").document(uid).collection("passwords")
                .document(entry.remoteId).delete().await()
        } catch (e: Exception) {
            Log.e(TAG, "deleteRemoteEntry failed", e)
        }
    }

    /**
     * Скачивает все зашифрованные записи пользователя из Firestore.
     *
     * Возвращает `null`, если запрос вообще не удалось выполнить (нет сети и т.д.) —
     * это отличается от пустого списка (аккаунт действительно без записей), чтобы
     * вызывающий код (см. [io.kmanager.app.data.repository.PasswordRepository.syncPasswordsFromRemote])
     * не удалял локальные записи на основании заведомо неполных/ошибочных данных.
     *
     * Явно запрашивает данные с сервера ([Source.SERVER]), а не из локального
     * офлайн-кэша Firestore SDK — иначе после добавления записи на другом устройстве
     * этот метод мог тихо вернуть устаревший кэш без новой записи (без какой-либо ошибки).
     * При ошибке сервера (например, кратковременная потеря сети) — fallback на кэш,
     * чтобы не оставлять пользователя совсем без данных, если он реально офлайн.
     */
    suspend fun fetchAllEntries(masterPassword: CharArray?): List<PasswordEntry>? {
        val uid = currentUserId ?: return null
        if (masterPassword == null || masterPassword.isEmpty()) return null
        val passwordString = String(masterPassword)

        val collection = db.collection("users").document(uid).collection("passwords")
        val snapshot = try {
            withTimeout(20_000L) { collection.get(Source.SERVER).await() }
        } catch (e: Exception) {
            Log.w(TAG, "fetchAllEntries: server fetch failed (${e.message}), falling back to cache")
            try {
                withTimeout(10_000L) { collection.get(Source.CACHE).await() }
            } catch (e2: Exception) {
                Log.e(TAG, "fetchAllEntries failed (server and cache)", e2)
                return null
            }
        }

        Log.d(TAG, "fetchAllEntries: fetched ${snapshot.documents.size} remote document(s)")
        val entries = mutableListOf<PasswordEntry>()
        var decryptFailures = 0
        for (doc in snapshot.documents) {
            val encrypted = doc.getString("encryptedData") ?: continue
            // Приоритет — серверное время Firestore (не зависит от часов устройства).
            // Fallback на локальное поле lastModified нужен только для документов
            val serverMillis = doc.getTimestamp("serverTimestamp")?.toDate()?.time
            val lastModifiedRemote = serverMillis ?: (doc.getLong("lastModified") ?: 0)
            try {
                val entry = EncryptionManager.decryptEntry(context, encrypted, passwordString)
                if (entry != null) {
                    entries.add(entry.copy(remoteId = doc.id, lastModified = lastModifiedRemote))
                } else {
                    decryptFailures++
                }
            } catch (e: Exception) {
                decryptFailures++
                Log.e(TAG, "Decryption failed for document ${doc.id}", e)
            }
        }
        if (decryptFailures > 0) {
            // Не прерываем синк из-за этого, но логируем — частая причина: рассинхронизация
            // соли/ключа шифрования между устройствами (см. downloadUserSalt/getLegacySalt).
            Log.w(TAG, "fetchAllEntries: $decryptFailures document(s) failed to decrypt")
        }
        return entries
    }

    /**
     * Загружает соль пользователя в Firestore (документ users/{uid}/metadata/crypto).
     * Соль не является секретом — она защищена Firebase Auth-правилами (по uid).
     * Заодно сохраняет email как обычное информационное поле (не используется
     * для разграничения доступа — путь теперь строится по uid).
     */
    suspend fun uploadUserSalt(salt: ByteArray): Boolean {
        val uid = currentUserId ?: return false
        return try {
            val saltBase64 = Base64.encodeToString(salt, Base64.NO_WRAP)
            val data = mutableMapOf<String, Any>("salt" to saltBase64)
            currentUserEmail?.let { data["email"] = it }
            withTimeout(15_000L) {
                db.collection("users").document(uid)
                    .collection("metadata").document("crypto")
                    .set(data, SetOptions.merge())
                    .await()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "uploadUserSalt failed", e)
            false
        }
    }

    /**
     * Скачивает соль пользователя из Firestore.
     * Возвращает null, если соль ещё не сохранена (старый аккаунт — требует миграции).
     */
    suspend fun downloadUserSalt(): ByteArray? {
        val uid = currentUserId ?: return null
        return try {
            val doc = withTimeout(15_000L) {
                db.collection("users").document(uid)
                    .collection("metadata").document("crypto")
                    .get().await()
            }
            val saltBase64 = doc.getString("salt") ?: return null
            Base64.decode(saltBase64, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "downloadUserSalt failed", e)
            null
        }
    }

    suspend fun signUpWithEmail(email: String, password: String): Boolean {
        return try {
            // Создаем пользователя
            auth.createUserWithEmailAndPassword(email, password).await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "signUpWithEmail failed", e)
            false
        }
    }

    suspend fun sendVerificationEmail(): Boolean {
        return try {
            auth.currentUser?.sendEmailVerification()?.await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendVerificationEmail failed", e)
            false
        }
    }

    suspend fun isEmailVerified(): Boolean {
        return try {
            auth.currentUser?.reload()?.await()
            auth.currentUser?.isEmailVerified == true
        } catch (e: Exception) {
            Log.e(TAG, "isEmailVerified check failed", e)
            false
        }
    }

    /**
     * Отправляет письмо со ссылкой для сброса пароля через Firebase Auth.
     */
    suspend fun sendPasswordResetEmail(email: String): Boolean {
        return try {
            withTimeout(15_000L) {
                auth.sendPasswordResetEmail(email).await()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendPasswordResetEmail failed", e)
            false
        }
    }

    /**
     * Обновляет пароль текущего пользователя в Firebase Auth.
     * Вызывается когда пользователь сбросил пароль через email на слабый,
     * и мы принудительно предлагаем ему задать новый соответствующий требованиям.
     */
    suspend fun updateAuthPassword(newPassword: String): Boolean {
        return try {
            withTimeout(15_000L) {
                auth.currentUser?.updatePassword(newPassword)?.await()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "updateAuthPassword failed", e)
            false
        }
    }

    /**
     * Результат проверки ключа шифрования хранилища.
     */
    enum class KeyVerifyResult { VALID, INVALID, NOT_FOUND }

    /**
     * Загружает keyVerifier в Firestore — зашифрованную метку "VAULT_OK".
     * Используется для обнаружения смены мастер-пароля (сброс через Firebase).
     */
    suspend fun uploadKeyVerifier(masterPassword: String, salt: ByteArray): Boolean {
        val uid = currentUserId ?: return false
        return try {
            val verifier = EncryptionManager.encryptString(KEY_VERIFIER_PLAINTEXT, masterPassword, salt)
            withTimeout(15_000L) {
                db.collection("users").document(uid)
                    .collection("metadata").document("crypto")
                    .set(mapOf("keyVerifier" to verifier), SetOptions.merge())
                    .await()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "uploadKeyVerifier failed", e)
            false
        }
    }

    /**
     * Проверяет keyVerifier: скачивает из Firestore и пытается расшифровать.
     * - VALID:     ключ совпадает — вход штатный
     * - INVALID:   ключ не совпадает — пароль был сброшен, хранилище нужно очистить
     * - NOT_FOUND: верификатор отсутствует — старый аккаунт, загрузим новый
     */
    suspend fun verifyKey(masterPassword: String, salt: ByteArray): KeyVerifyResult {
        val uid = currentUserId ?: return KeyVerifyResult.NOT_FOUND
        return try {
            val doc = withTimeout(15_000L) {
                db.collection("users").document(uid)
                    .collection("metadata").document("crypto")
                    .get().await()
            }
            val verifier = doc.getString("keyVerifier") ?: return KeyVerifyResult.NOT_FOUND
            val plaintext = EncryptionManager.decryptString(verifier, masterPassword, salt)
            if (plaintext == KEY_VERIFIER_PLAINTEXT) KeyVerifyResult.VALID else KeyVerifyResult.INVALID
        } catch (e: Exception) {
            Log.e(TAG, "verifyKey failed — treating as VALID to avoid blocking login", e)
            KeyVerifyResult.VALID
        }
    }

    /**
     * Удаляет все записи паролей пользователя из Firestore.
     * Вызывается при обнаружении смены мастер-пароля.
     */
    suspend fun deleteAllUserPasswords(): Boolean {
        val uid = currentUserId ?: return false
        return try {
            val snapshot = withTimeout(30_000L) {
                db.collection("users").document(uid)
                    .collection("passwords").get().await()
            }
            for (doc in snapshot.documents) {
                doc.reference.delete().await()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "deleteAllUserPasswords failed", e)
            false
        }
    }

    /**
     * Повторно подтверждает личность пользователя перед необратимыми операциями
     * (удаление аккаунта). Firebase требует "свежий" вход для [FirebaseUser.delete] —
     * этот вызов обновляет сессию независимо от того, истекла ли она, и заодно
     * служит проверкой того, что пользователь действительно знает мастер-пароль.
     */
    suspend fun reauthenticate(password: String): Boolean {
        val user = auth.currentUser ?: return false
        val email = user.email ?: return false
        return try {
            val credential = EmailAuthProvider.getCredential(email, password)
            withTimeout(15_000L) { user.reauthenticate(credential).await() }
            true
        } catch (e: Exception) {
            Log.e(TAG, "reauthenticate failed", e)
            false
        }
    }

    /**
     * Удаляет метаданные пользователя (salt, keyVerifier, email) из Firestore.
     */
    suspend fun deleteUserMetadata(): Boolean {
        val uid = currentUserId ?: return false
        return try {
            withTimeout(15_000L) {
                db.collection("users").document(uid)
                    .collection("metadata").document("crypto")
                    .delete().await()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "deleteUserMetadata failed", e)
            false
        }
    }

    /**
     * Удаляет сам аккаунт Firebase Auth
     */
    suspend fun deleteAuthAccount(): Boolean {
        val user = auth.currentUser ?: return false
        return try {
            withTimeout(15_000L) { user.delete().await() }
            true
        } catch (e: Exception) {
            Log.e(TAG, "deleteAuthAccount failed", e)
            false
        }
    }
}