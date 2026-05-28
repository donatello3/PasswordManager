package com.example.passwordmanager

import android.content.Context
import com.example.passwordmanager.data.database.AppDatabase
import com.example.passwordmanager.data.remote.FirestoreDataSource
import com.example.passwordmanager.data.repository.PasswordRepository
import com.google.firebase.auth.FirebaseAuth

class AppContainer(private val context: Context) {
    private var database: AppDatabase? = null
    private var _repository: PasswordRepository? = null
    internal var repository: PasswordRepository? = null

    fun provideDatabase(passphrase: ByteArray): AppDatabase {
        return AppDatabase.Companion.getInstance(context, passphrase)
    }

    fun provideRepository(passphrase: ByteArray): PasswordRepository {
        val db = provideDatabase(passphrase)
        val remote = if (isUserSignedIn()) FirestoreDataSource(context) else null
        val repo = PasswordRepository(db.passwordDao(), remote, context)
        repository = repo
        return repo
    }

    private fun isUserSignedIn(): Boolean {
        return FirebaseAuth.getInstance().currentUser != null
    }
}