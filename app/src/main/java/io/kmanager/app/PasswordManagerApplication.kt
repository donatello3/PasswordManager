package io.kmanager.app

import android.app.Application
import io.kmanager.app.utils.AppLockManager
import java.util.Arrays

class PasswordManagerApplication : Application() {
    lateinit var appContainer: AppContainer
    var currentMasterPassword: CharArray? = null

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
        AppLockManager.init(this)
    }

    fun clearMasterPassword() {
        currentMasterPassword?.let { Arrays.fill(it, ' ') }
        currentMasterPassword = null
    }
}