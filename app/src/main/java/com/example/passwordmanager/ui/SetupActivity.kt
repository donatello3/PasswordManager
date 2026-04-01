package com.example.passwordmanager.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.passwordmanager.databinding.ActivitySetupBinding
import com.example.passwordmanager.utils.CryptoManager

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Using View Binding (recommended) – enable it in build.gradle.kts:
        // android { buildFeatures { viewBinding = true } }
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCreate.setOnClickListener {
            val password = binding.etPassword.text.toString()
            val confirm = binding.etConfirmPassword.text.toString()

            if (password.isEmpty()) {
                Toast.makeText(this, "Password cannot be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (password != confirm) {
                Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Store master password key and hash
            val key = CryptoManager.setupMasterPassword(this, password)
            // The key is the derived byte array used for database encryption.
            // At this point we also need to create an empty database (we'll do it later).
            // For now, just navigate to MainActivity.
            // In a real flow, after setup we would also initialize the database with this key.
            // But we haven't created the database yet – we'll handle that after unlocking.

            // After setup, go to MainActivity (or UnlockActivity). Usually after first setup, you want to unlock directly.
            // Since we just set the password, we can go to UnlockActivity, which will ask again.
            startActivity(Intent(this, UnlockActivity::class.java))
            finish()
        }
    }
}