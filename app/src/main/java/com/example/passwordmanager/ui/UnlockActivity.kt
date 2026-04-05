package com.example.passwordmanager.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.passwordmanager.MainActivity
import com.example.passwordmanager.PasswordManagerApplication
import com.example.passwordmanager.databinding.ActivityUnlockBinding
import com.example.passwordmanager.utils.CryptoManager

class UnlockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUnlockBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUnlockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnUnlock.setOnClickListener {
            val password = binding.etPassword.text.toString()

            if (password.isEmpty()) {
                Toast.makeText(this, "Enter password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Verify password against stored email + hash
            if (CryptoManager.verifyPasswordOnly(this, password)) {
                val key = CryptoManager.getDatabaseKey(this, password)
                if (key != null) {
                    val app = application as PasswordManagerApplication
                    app.appContainer.provideRepository(key)
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                } else {
                    Toast.makeText(this, "Failed to derive key", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Wrong password", Toast.LENGTH_SHORT).show()
            }
        }
    }
}