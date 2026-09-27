package io.kmanager.app.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(entities = [PasswordEntry::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase(){
    abstract fun passwordDao(): PasswordDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        @Volatile
        private var nativeLibLoaded = false

        private const val DB_NAME = "password_manager.db"

        private fun ensureNativeLibLoaded() {
            if (!nativeLibLoaded) {
                synchronized(this) {
                    if (!nativeLibLoaded) {
                        System.loadLibrary("sqlcipher")
                        nativeLibLoaded = true
                    }
                }
            }
        }

        fun getInstance(context: Context, passphrase: ByteArray): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    ensureNativeLibLoaded()
                    val factory = SupportOpenHelperFactory(passphrase)
                    val instance = Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        DB_NAME
                    )
                        .openHelperFactory(factory)
                        .build()
                    INSTANCE = instance
                    instance
                }
            }
        }

        /**
         * Закрывает текущий экземпляр БД, удаляет файл и сбрасывает синглтон.
         * Вызывать при выходе из аккаунта или обнаружении повреждённой БД.
         */
        fun resetInstance(context: Context) {
            synchronized(this) {
                try { INSTANCE?.close() } catch (_: Exception) {}
                INSTANCE = null
                try { context.applicationContext.deleteDatabase(DB_NAME) } catch (_: Exception) {}
            }
        }

        /**
         * Закрывает текущее соединение с БД, НЕ удаляя файл на диске.
         * В отличие от [resetInstance], используется при авто-блокировке приложения
         * (таймаут бездействия / уход в фон) — локальные данные сохраняются
         * и снова становятся доступны после повторного ввода мастер-пароля.
         */
        fun closeInstance() {
            synchronized(this) {
                try { INSTANCE?.close() } catch (_: Exception) {}
                INSTANCE = null
            }
        }
    }
}