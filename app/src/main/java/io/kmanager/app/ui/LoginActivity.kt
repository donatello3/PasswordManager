package io.kmanager.app.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.kmanager.app.data.remote.FirestoreDataSource
import io.kmanager.app.databinding.ActivityLoginBinding
import io.kmanager.app.utils.CryptoManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
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
                        // Получаем соль из Firestore.
                        // Если соли нет (старый аккаунт до миграции) — используем
                        // детерминированную соль и сразу загружаем её, чтобы
                        // последующие входы с других устройств работали корректно.
                        var salt = firestore.downloadUserSalt()
                        if (salt == null) {
                            Log.w("LoginActivity", "Salt not found in Firestore, migrating legacy account")
                            salt = CryptoManager.getLegacySalt(email)
                            firestore.uploadUserSalt(salt)
                        }

                        // Сохраняем локальные данные для разблокировки (UnlockActivity).
                        // Запускаем на IO-потоке — PBKDF2 с 100k итерациями блокирует Main thread.
                        withContext(Dispatchers.IO) {
                            CryptoManager.setupAccount(this@LoginActivity, email, password, salt)
                        }

                        Toast.makeText(this@LoginActivity, "Login successful", Toast.LENGTH_SHORT).show()
                        startActivity(Intent(this@LoginActivity, UnlockActivity::class.java))
                        finish()
                    } else {
                        Toast.makeText(this@LoginActivity, "Firebase login failed", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("LoginActivity", "Login error", e)
                    Toast.makeText(
                        this@LoginActivity,
                        "Login error. Please try again.",
                        Toast.LENGTH_LONG
                    ).show()
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