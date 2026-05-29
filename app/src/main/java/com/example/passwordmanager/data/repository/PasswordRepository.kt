package com.example.passwordmanager.data.repository

import android.content.Context
import com.example.passwordmanager.PasswordManagerApplication
import com.example.passwordmanager.data.database.PasswordDao
import com.example.passwordmanager.data.database.PasswordEntry
import com.example.passwordmanager.data.remote.FirestoreDataSource
import kotlinx.coroutines.flow.Flow

class PasswordRepository(private val dao: PasswordDao,
                         private val remoteDataSource: FirestoreDataSource? = null,
                         private val context: Context) {
    fun getAllPasswords(): Flow<List<PasswordEntry>> = dao.getAllPasswords()
    fun searchPasswords(query: String): Flow<List<PasswordEntry>> = dao.searchPasswords(query)
    fun getPasswordsByCategory(category: String): Flow<List<PasswordEntry>> = dao.getPasswordsByCategory(category)
    fun getAllCategories(): Flow<List<String>> = dao.getAllCategories()

    suspend fun insert(entry: PasswordEntry) {
        val newId = dao.insert(entry)
        if (entry.syncEnabled && remoteDataSource != null) {
            try {
                val masterPassword = getMasterPassword()
                val entryWithId = entry.copy(id = newId)
                val remoteId = remoteDataSource.uploadEntry(entryWithId, masterPassword)
                if (remoteId != null) {
                    dao.update(entryWithId.copy(remoteId = remoteId))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun update(entry: PasswordEntry) {
        val oldEntry = dao.getPasswordById(entry.id)
        if (entry.syncEnabled && remoteDataSource != null) {
            try {
                val masterPassword = getMasterPassword()
                val remoteId = remoteDataSource.uploadEntry(entry, masterPassword)
                val entryToSave = if (remoteId != null) entry.copy(remoteId = remoteId) else entry
                dao.update(entryToSave)
            } catch (e: Exception) {
                e.printStackTrace()
                dao.update(entry)
            }
        } else if (!entry.syncEnabled && oldEntry?.remoteId != null && remoteDataSource != null) {
            try {
                remoteDataSource.deleteRemoteEntry(oldEntry)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            dao.update(entry.copy(remoteId = null))
        } else {
            dao.update(entry)
        }
    }

    suspend fun delete(entry: PasswordEntry) {
        dao.delete(entry)
        if (entry.remoteId != null && remoteDataSource != null) {
            try {
                remoteDataSource.deleteRemoteEntry(entry)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun getMasterPassword(): String {
        return (context as? PasswordManagerApplication)?.currentMasterPassword ?: ""
    }
    suspend fun getPasswordById(id: Long): PasswordEntry? = dao.getPasswordById(id)

    suspend fun syncPasswordsFromRemote() {
        if (remoteDataSource == null) return
        val masterPassword = getMasterPassword()
        if (masterPassword.isEmpty()) return

        val remoteEntries = remoteDataSource.fetchAllEntries(masterPassword)
        for (remoteEntry in remoteEntries) {
            val remoteId = remoteEntry.remoteId ?: continue
            // Дедуплицируем только по remoteId (Firestore document ID)
            val existing = dao.getPasswordByRemoteId(remoteId)
            if (existing == null) {
                // Новая запись — вставляем с id=0, Room сгенерирует локальный ключ
                dao.insert(remoteEntry.copy(id = 0, syncEnabled = true))
            } else {
                // Обновляем только если версия на сервере новее
                if (remoteEntry.lastModified > existing.lastModified) {
                    dao.update(remoteEntry.copy(id = existing.id, syncEnabled = true))
                }
            }
        }
    }
}