package com.example.passwordmanager

import android.content.Context
import com.example.passwordmanager.data.database.AppDatabase
import com.example.passwordmanager.data.repository.PasswordRepository

class AppContainer(private val context: Context) {
    private var database: AppDatabase? = null
    private var _repository: PasswordRepository? = null
    val repository: PasswordRepository?
        get() = _repository

    fun provideDatabase(passphrase: ByteArray): AppDatabase {
        return AppDatabase.Companion.getInstance(context, passphrase)
    }

    fun provideRepository(passphrase: ByteArray): PasswordRepository {
        val db = provideDatabase(passphrase)
        _repository = PasswordRepository(db.passwordDao())
        return _repository!!
    }
}