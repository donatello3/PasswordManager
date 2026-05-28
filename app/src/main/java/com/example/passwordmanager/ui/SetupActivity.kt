package com.example.passwordmanager.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.passwordmanager.data.remote.FirestoreDataSource
import com.example.passwordmanager.databinding.ActivitySetupBinding
import com.example.passwordmanager.utils.CryptoManager
import kotlinx.coroutines.launch

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCreate.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString()
            val confirm = binding.etConfirmPassword.text.toString()

            if (email.isEmpty()) {
                Toast.makeText(this, "Email cannot be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                Toast.makeText(this, "Invalid email format", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (password.isEmpty()) {
                Toast.makeText(this, "Password cannot be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (password != confirm) {
                Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Store email + password, derive key
            val key = CryptoManager.setupAccount(this, email, password)

            val firestore = FirestoreDataSource(this)
            lifecycleScope.launch {
                val success = firestore.signUpWithEmail(email, password)

                if (success) {
                    Toast.makeText(this@SetupActivity, "Account created! Please unlock.", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this@SetupActivity, UnlockActivity::class.java))
                    finish()
                } else {
                    Toast.makeText(this@SetupActivity, "Firebase registration failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}