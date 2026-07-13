package io.kmanager.app.data.remote

import android.content.Context
import android.util.Log
import io.kmanager.app.data.database.PasswordEntry
import io.kmanager.app.utils.EncryptionManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class FirestoreDataSource(private val context: Context) {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val currentUserEmail: String?
        get() = auth.currentUser?.email

    companion object {
        private const val TAG = "FirestoreDataSource"
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
        val email = currentUserEmail ?: return null
        if (masterPassword == null || masterPassword.isEmpty()) return null
        val passwordString = String(masterPassword)
        val encrypted = EncryptionManager.encryptEntry(context, entry, passwordString) ?: return null
        val data = mapOf(
            "encryptedData" to encrypted,
            "lastModified" to entry.lastModified
        )
        val docRef = if (entry.remoteId != null) {
            db.collection("users").document(email).collection("passwords").document(entry.remoteId)
        } else {
            db.collection("users").document(email).collection("passwords").document()
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
        val email = currentUserEmail ?: return
        try {
            db.collection("users").document(email).collection("passwords")
                .document(entry.remoteId).delete().await()
        } catch (e: Exception) {
            Log.e(TAG, "deleteRemoteEntry failed", e)
        }
    }

    suspend fun fetchAllEntries(masterPassword: CharArray?): List<PasswordEntry> {
        val email = currentUserEmail ?: return emptyList()
        if (masterPassword == null || masterPassword.isEmpty()) return emptyList()
        val passwordString = String(masterPassword)
        val snapshot = try {
            db.collection("users").document(email).collection("passwords").get().await()
        } catch (e: Exception) {
            Log.e(TAG, "fetchAllEntries failed", e)
            return emptyList()
        }
        val entries = mutableListOf<PasswordEntry>()
        for (doc in snapshot.documents) {
            val encrypted = doc.getString("encryptedData") ?: continue
            val lastModifiedRemote = doc.getLong("lastModified") ?: 0
            try {
                val entry = EncryptionManager.decryptEntry(context, encrypted, passwordString)
                if (entry != null) {
                    entries.add(entry.copy(remoteId = doc.id, lastModified = lastModifiedRemote))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Decryption failed for document ${doc.id}", e)
            }
        }
        return entries
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
        // Force refresh to get latest status from server
        return try {
            auth.currentUser?.reload()?.await()
            auth.currentUser?.isEmailVerified == true
        } catch (e: Exception) {
            Log.e(TAG, "isEmailVerified check failed", e)
            false
        }
    }
}