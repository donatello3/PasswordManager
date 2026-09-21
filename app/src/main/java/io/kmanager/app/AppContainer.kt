package io.kmanager.app

import android.content.Context
import io.kmanager.app.data.database.AppDatabase
import io.kmanager.app.data.remote.FirestoreDataSource
import io.kmanager.app.data.repository.PasswordRepository
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

    fun clearRepository() {
        repository = null
        _repository = null
        AppDatabase.resetInstance(context)
        database = null
    }

    /**
     * Закрывает текущую сессию (соединение с БД) БЕЗ удаления файла — используется
     * при авто-блокировке приложения (таймаут бездействия / уход в фон).
     * В отличие от [clearRepository] (полный логаут), локальные записи паролей
     * сохраняются на диске и снова становятся доступны после ввода мастер-пароля
     * на экране UnlockActivity.
     */
    fun closeRepositorySession() {
        repository = null
        _repository = null
        AppDatabase.closeInstance()
        database = null
    }
}