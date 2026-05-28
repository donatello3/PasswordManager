package com.example.passwordmanager.data.remote

import android.content.Context
import com.example.passwordmanager.data.database.PasswordEntry
import com.example.passwordmanager.utils.EncryptionManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import kotlin.text.get

class FirestoreDataSource(private val context: Context) {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val currentUserEmail: String?
        get() = auth.currentUser?.email

    suspend fun signInWithEmail(email: String, password: String): Boolean {
        return try {
            auth.signInWithEmailAndPassword(email, password).await()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun uploadEntry(entry: PasswordEntry, masterPassword: String): String? {
        if (!entry.syncEnabled) return null
        val email = currentUserEmail ?: return null
        val encrypted = EncryptionManager.encryptEntry(context, entry, masterPassword) ?: return null
        val data = mapOf(
            "encryptedData" to encrypted,
            "lastModified" to entry.lastModified,
            "localId" to entry.id
        )
        val docRef = if (entry.remoteId != null) {
            db.collection("users").document(email).collection("passwords").document(entry.remoteId)
        } else {
            db.collection("users").document(email).collection("passwords").document()
        }
        docRef.set(data).await()
        return docRef.id
    }

    suspend fun deleteRemoteEntry(entry: PasswordEntry) {
        if (entry.remoteId == null) return
        val email = currentUserEmail ?: return
        db.collection("users").document(email).collection("passwords").document(entry.remoteId).delete().await()
    }

    suspend fun fetchAllEntries(masterPassword: String): List<PasswordEntry> {
        val email = currentUserEmail ?: return emptyList()
        val snapshot = db.collection("users").document(email).collection("passwords").get().await()
        val entries = mutableListOf<PasswordEntry>()
        for (doc in snapshot.documents) {
            val encrypted = doc.getString("encryptedData") ?: continue
            val lastModifiedRemote = doc.getLong("lastModified") ?: 0
            val localId = doc.getLong("localId") ?: 0

            try {
                val entry = EncryptionManager.decryptEntry(context, encrypted, masterPassword)
                if (entry != null) {
                    val syncedEntry = entry.copy(
                        remoteId = doc.id,
                        lastModified = lastModifiedRemote,
                        id = localId  // keep local id
                    )
                    entries.add(syncedEntry)
                }
            } catch (e: Exception) {
                // Игнорируем записи, которые невозможно расшифровать текущим ключом
                e.printStackTrace()
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
            e.printStackTrace()
            false
        }
    }
}