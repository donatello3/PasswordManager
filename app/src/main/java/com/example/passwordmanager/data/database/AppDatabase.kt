package com.example.passwordmanager.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import net.sqlcipher.database.SupportFactory

@Database(entities = [PasswordEntry::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase(){
    abstract fun passwordDao(): PasswordDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private const val DB_NAME = "password_manager.db"

        fun getInstance(context: Context, passphrase: ByteArray): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val factory = SupportFactory(passphrase)
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
    }
}