package com.example.passwordmanager.data.repository

import com.example.passwordmanager.data.database.PasswordDao
import com.example.passwordmanager.data.database.PasswordEntry
import kotlinx.coroutines.flow.Flow

class PasswordRepository(private val dao: PasswordDao) {
    fun getAllPasswords(): Flow<List<PasswordEntry>> = dao.getAllPasswords()
    fun searchPasswords(query: String): Flow<List<PasswordEntry>> = dao.searchPasswords(query)
    fun getPasswordsByCategory(category: String): Flow<List<PasswordEntry>> = dao.getPasswordsByCategory(category)
    fun getAllCategories(): Flow<List<String>> = dao.getAllCategories()

    suspend fun insert(entry: PasswordEntry) = dao.insert(entry)
    suspend fun update(entry: PasswordEntry) = dao.update(entry)
    suspend fun delete(entry: PasswordEntry) = dao.delete(entry)
    suspend fun getPasswordById(id: Long): PasswordEntry? = dao.getPasswordById(id)
}