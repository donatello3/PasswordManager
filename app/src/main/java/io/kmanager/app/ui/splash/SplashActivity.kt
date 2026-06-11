package io.kmanager.app.ui.splash

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import io.kmanager.app.ui.LoginActivity
import io.kmanager.app.ui.UnlockActivity
import io.kmanager.app.utils.CryptoManager

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