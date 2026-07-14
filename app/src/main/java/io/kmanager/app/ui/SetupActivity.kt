package io.kmanager.app.ui

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.kmanager.app.data.remote.FirestoreDataSource
import io.kmanager.app.databinding.ActivitySetupBinding
import io.kmanager.app.utils.CryptoManager
import io.kmanager.app.utils.PasswordValidator
import kotlinx.coroutines.launch
import android.view.View
import androidx.appcompat.app.AlertDialog

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Показываем подсказку о требованиях под полем
        binding.tilPassword.helperText = PasswordValidator.HINT

        // Сброс ошибок при начале ввода
        binding.etPassword.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) binding.tilPassword.error = null
        }
        binding.etConfirmPassword.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) binding.tilConfirmPassword.error = null
        }

        binding.btnCreate.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString()
            val confirm = binding.etConfirmPassword.text.toString()

            // Сбрасываем ошибки перед новой проверкой
            binding.tilPassword.error = null
            binding.tilConfirmPassword.error = null

            if (email.isEmpty()) {
                Toast.makeText(this, "Email cannot be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                Toast.makeText(this, "Invalid email format", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Валидация сложности пароля
            when (val result = PasswordValidator.validate(password)) {
                is PasswordValidator.Result.Invalid -> {
                    binding.tilPassword.error = result.reason
                    binding.etPassword.requestFocus()
                    return@setOnClickListener
                }
                is PasswordValidator.Result.Valid -> { /* продолжаем */ }
            }

            if (password != confirm) {
                binding.tilConfirmPassword.error = "Passwords do not match"
                binding.etConfirmPassword.requestFocus()
                return@setOnClickListener
            }

            showLoading(true)

            // Генерируем случайную соль — она будет использована локально
            // и загружена в Firestore, чтобы оставаться единой на всех устройствах.
            val salt = CryptoManager.generateSalt()
            CryptoManager.setupAccount(this, email, password, salt)

            val firestore = FirestoreDataSource(this)
            lifecycleScope.launch {
                try {
                    val success = firestore.signUpWithEmail(email, password)

                    if (success) {
                        // Загружаем соль в Firestore (пользователь уже авторизован после signUp)
                        firestore.uploadUserSalt(salt)

                        Toast.makeText(this@SetupActivity, "Account created! Please unlock.", Toast.LENGTH_SHORT).show()
                        // Send verification email
                        val emailSent = firestore.sendVerificationEmail()
                        if (emailSent) {
                            showVerificationDialog()
                        } else {
                            Toast.makeText(this@SetupActivity, "Failed to send verification email", Toast.LENGTH_SHORT).show()
                            startActivity(Intent(this@SetupActivity, UnlockActivity::class.java))
                            finish()
                        }
                    } else {
                        Toast.makeText(this@SetupActivity, "Firebase registration failed", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    showLoading(false)
                }
            }
        }
    }

    private fun showVerificationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Verify Your Email")
            .setMessage("We've sent a verification link to your email address. Please verify your email before you can access your vault.\n\nIf you don't see the email, check your spam folder.")
            .setPositiveButton("OK") { _, _ ->
                startActivity(Intent(this, UnlockActivity::class.java))
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun showLoading(isLoading: Boolean) {
        binding.loadingOverlay.visibility = if (isLoading) View.VISIBLE else View.GONE
    }
}