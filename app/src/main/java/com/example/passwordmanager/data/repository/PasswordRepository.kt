package com.example.passwordmanager.data.repository

import android.content.Context
import com.example.passwordmanager.PasswordManagerApplication
import com.example.passwordmanager.data.database.PasswordDao
import com.example.passwordmanager.data.database.PasswordEntry
import com.example.passwordmanager.data.remote.FirestoreDataSource
import kotlinx.coroutines.flow.Flow
import kotlin.text.compareTo
import kotlin.text.insert

class PasswordRepository(private val dao: PasswordDao,
                         private val remoteDataSource: FirestoreDataSource? = null,
                         private val context: Context) {
    fun getAllPasswords(): Flow<List<PasswordEntry>> = dao.getAllPasswords()
    fun searchPasswords(query: String): Flow<List<PasswordEntry>> = dao.searchPasswords(query)
    fun getPasswordsByCategory(category: String): Flow<List<PasswordEntry>> = dao.getPasswordsByCategory(category)
    fun getAllCategories(): Flow<List<String>> = dao.getAllCategories()

    suspend fun insert(entry: PasswordEntry) {
        dao.insert(entry)
        if (entry.syncEnabled && remoteDataSource != null) {
            val masterPassword = getMasterPassword() // we'll need to store it temporarily
            val remoteId = remoteDataSource.uploadEntry(entry, masterPassword)
            if (remoteId != null) {
                dao.update(entry.copy(remoteId = remoteId))
            }
        }
    }

    suspend fun update(entry: PasswordEntry) {
        val oldEntry = dao.getPasswordById(entry.id)
        dao.update(entry)
        if (entry.syncEnabled && remoteDataSource != null) {
            val masterPassword = getMasterPassword()
            remoteDataSource.uploadEntry(entry, masterPassword)
        } else if (oldEntry?.syncEnabled == true && !entry.syncEnabled) {
        }
    }

    suspend fun delete(entry: PasswordEntry) {
        dao.delete(entry)
        if (entry.syncEnabled && remoteDataSource != null) {
            remoteDataSource.deleteRemoteEntry(entry)
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
            // Проверяем, есть ли запись уже в базе (по ID из Firebase)
            val existing = remoteEntry.remoteId?.let { dao.getPasswordByRemoteId(it) }

            if (existing == null) {
                // Записи нет — вставляем её как новую (id = 0 позволяет Room самому сгенерировать локальный ключ)
                dao.insert(remoteEntry.copy(id = 0, syncEnabled = true))
            } else {
                // Если запись есть, обновляем её в случае если версия на сервере новее
                if (remoteEntry.lastModified > existing.lastModified) {
                    dao.update(remoteEntry.copy(id = existing.id, syncEnabled = true))
                }
            }
        }
    }
}