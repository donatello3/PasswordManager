package com.example.passwordmanager.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.passwordmanager.data.remote.FirestoreDataSource
import com.example.passwordmanager.databinding.ActivityLoginBinding
import com.example.passwordmanager.utils.CryptoManager
import kotlinx.coroutines.launch
import android.view.View

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSignIn.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            showLoading(true)
            val firestore = FirestoreDataSource(this)
            lifecycleScope.launch {
                try {
                    val success = firestore.signInWithEmail(email, password)

                    if (success) {
                        // Сохраняем локальные данные для разблокировки (UnlockActivity)
                        CryptoManager.setupAccount(this@LoginActivity, email, password)

                        Toast.makeText(this@LoginActivity, "Login successful", Toast.LENGTH_SHORT).show()
                        startActivity(Intent(this@LoginActivity, UnlockActivity::class.java))
                        finish()
                    } else {
                        Toast.makeText(this@LoginActivity, "Firebase login failed", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    showLoading(false)
                }
            }
        }

        binding.btnCreateAccount.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
    }

    private fun showLoading(isLoading: Boolean) {
        binding.loadingOverlay.visibility = if (isLoading) View.VISIBLE else View.GONE
    }
}