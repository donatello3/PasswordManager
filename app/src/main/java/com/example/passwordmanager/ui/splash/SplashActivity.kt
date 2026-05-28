package com.example.passwordmanager.ui.splash

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.example.passwordmanager.ui.LoginActivity
import com.example.passwordmanager.ui.SetupActivity
import com.example.passwordmanager.ui.UnlockActivity
import com.example.passwordmanager.utils.CryptoManager

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No layout needed – just a blank screen (or you can create a simple layout)
        Handler(Looper.getMainLooper()).postDelayed({
            // Check if master password exists using CryptoManager
            val hasPassword = CryptoManager.isMasterPasswordSet(this)
            if (hasPassword) {
                startActivity(Intent(this, UnlockActivity::class.java))
            } else {
                startActivity(Intent(this, LoginActivity::class.java))
            }
            finish()
        }, 1500) // 1.5 seconds delay
    }
}