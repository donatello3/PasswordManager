package com.example.passwordmanager

import android.app.Application

class PasswordManagerApplication : Application() {
    lateinit var appContainer: AppContainer
    var currentMasterPassword: String = ""

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
    }
}