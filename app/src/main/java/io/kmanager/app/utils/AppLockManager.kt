package io.kmanager.app.utils

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.kmanager.app.PasswordManagerApplication
import io.kmanager.app.ui.UnlockActivity

/**
 * Авто-блокировка приложения при уходе в фон дольше настроенного таймаута.
 */
object AppLockManager : DefaultLifecycleObserver {

    private const val TAG = "AppLockManager"
    private const val PREFS_NAME = "app_lock_prefs"
    private const val KEY_TIMEOUT_MILLIS = "lock_timeout_millis"

    /** Значение по умолчанию — 5 минут. */
    const val DEFAULT_TIMEOUT_MILLIS = 5 * 60_000L

    /** Варианты таймаута для UI настроек (SecurityActivity): millis to display label. */
    val TIMEOUT_OPTIONS: List<Pair<Long, String>> = listOf(
        30_000L to "30 seconds",
        60_000L to "1 minute",
        5 * 60_000L to "5 minutes",
        15 * 60_000L to "15 minutes",
    )

    private var appContext: Context? = null
    private var backgroundedAt: Long? = null

    /** Вызывается один раз из [PasswordManagerApplication.onCreate]. */
    fun init(context: Context) {
        appContext = context.applicationContext
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        // Все Activity приложения ушли в фон.
        if (isSessionActive()) {
            backgroundedAt = System.currentTimeMillis()
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        val wentToBackgroundAt = backgroundedAt
        backgroundedAt = null

        if (wentToBackgroundAt == null || !isSessionActive()) return

        val elapsed = System.currentTimeMillis() - wentToBackgroundAt
        if (elapsed >= getTimeoutMillis(appContext ?: return)) {
            Log.i(TAG, "Timeout exceeded (${elapsed}ms >= ${getTimeoutMillis(appContext!!)}ms) — locking vault")
            performLock()
        }
    }

    private fun isSessionActive(): Boolean {
        val app = appContext as? PasswordManagerApplication ?: return false
        return app.currentMasterPassword != null
    }

    private fun performLock() {
        val context = appContext ?: return
        val app = context as? PasswordManagerApplication ?: return

        // Стираем мастер-пароль из памяти и закрываем сессию БД, НЕ удаляя файл
        // и НЕ трогая сессию Firebase Auth — это блокировка, а не логаут.
        app.clearMasterPassword()
        app.appContainer.closeRepositorySession()

        val intent = Intent(context, UnlockActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        context.startActivity(intent)
    }

    fun getTimeoutMillis(context: Context): Long {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_TIMEOUT_MILLIS, DEFAULT_TIMEOUT_MILLIS)
    }

    fun setTimeoutMillis(context: Context, millis: Long) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_TIMEOUT_MILLIS, millis)
            .apply()
    }
}


