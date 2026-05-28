package com.example.passwordmanager.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.passwordmanager.MainActivity
import com.example.passwordmanager.PasswordManagerApplication
import com.example.passwordmanager.data.remote.FirestoreDataSource
import com.example.passwordmanager.databinding.ActivityUnlockBinding
import com.example.passwordmanager.utils.CryptoManager
import kotlinx.coroutines.launch

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
                    app.currentMasterPassword = password
                    app.appContainer.provideRepository(key)

                    // Можно заблокировать кнопку на время загрузки, чтобы не было повторных нажатий
                    binding.btnUnlock.isEnabled = false

                    lifecycleScope.launch {
                        val email = CryptoManager.getLoggedInEmail(this@UnlockActivity)
                        if (email != null) {
                            val firestore = FirestoreDataSource(this@UnlockActivity)
                            val success = firestore.signInWithEmail(email, password)
                            if (success) {
                                // Вызываем синхронизацию с Firebase после успешной аутентификации!
                                val repository = app.appContainer.repository
                                repository?.syncPasswordsFromRemote()
                            } else {
                                Toast.makeText(this@UnlockActivity, "Firebase sign in failed", Toast.LENGTH_SHORT).show()
                            }
                        }

                        // Переход происходит только ПОСЛЕ окончания фоновой загрузки данных
                        // (или если загрузка завершилась с ошибкой, мы всё равно пускаем локально)
                        startActivity(Intent(this@UnlockActivity, MainActivity::class.java))
                        finish()
                    }
                } else {
                    Toast.makeText(this, "Failed to derive key", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Wrong password", Toast.LENGTH_SHORT).show()
            }
        }
    }
}