package io.kmanager.app.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.kmanager.app.R
import io.kmanager.app.data.database.AppDatabase
import io.kmanager.app.data.remote.FirestoreDataSource
import io.kmanager.app.data.remote.FirestoreDataSource.KeyVerifyResult
import io.kmanager.app.databinding.ActivityLoginBinding
import io.kmanager.app.databinding.DialogForcePasswordChangeBinding
import io.kmanager.app.utils.CryptoManager
import io.kmanager.app.utils.PasswordValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                        val setupOk = withContext(Dispatchers.IO) {
                            runSetupAccount(email, password, salt)
                        }

                        if (!setupOk) {
                            Toast.makeText(
                                this@LoginActivity,
                                "Failed to save local credentials. Please try signing in again.",
                                Toast.LENGTH_LONG
                            ).show()
                            showLoading(false)
                            return@launch
                        }

                        // Проверяем keyVerifier — обнаруживаем сброс пароля
                        val keyResult = withContext(Dispatchers.IO) {
                            firestore.verifyKey(password, salt)
                        }

                        when (keyResult) {
                            KeyVerifyResult.VALID -> {
                                // Штатный вход
                                proceedToUnlock()
                            }
                            KeyVerifyResult.NOT_FOUND -> {
                                // Старый аккаунт без верификатора — загружаем и продолжаем
                                withContext(Dispatchers.IO) {
                                    firestore.uploadKeyVerifier(password, salt)
                                }
                                proceedToUnlock()
                            }
                            KeyVerifyResult.INVALID -> {
                                // Пароль был сброшен — сначала проверяем его сложность
                                val passwordValid = PasswordValidator.validate(password) is PasswordValidator.Result.Valid
                                if (passwordValid) {
                                    showVaultResetDialog(firestore, email, password, salt)
                                } else {
                                    // Пароль слабый — принудительно просим сменить
                                    showForcePasswordChangeDialog(firestore, email, salt)
                                }
                            }
                        }
                    } else {
                        Toast.makeText(this@LoginActivity, "Invalid email or password", Toast.LENGTH_SHORT).show()
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

        binding.btnForgotPassword.setOnClickListener {
            startActivity(Intent(this, ForgotPasswordActivity::class.java))
        }
    }

    /**
     * Показывает диалог принудительной смены пароля когда после сброса
     * пользователь задал пароль не соответствующий требованиям безопасности.
     * После успешной смены продолжает стандартный сброс хранилища.
     */
    private fun showForcePasswordChangeDialog(
        firestore: FirestoreDataSource,
        email: String,
        oldSalt: ByteArray
    ) {
        val dialogBinding = DialogForcePasswordChangeBinding.inflate(layoutInflater)

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.force_pwd_change_title))
            .setView(dialogBinding.root)
            .setPositiveButton(getString(R.string.force_pwd_change_btn), null) // null — переопределим ниже
            .setCancelable(false)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newPassword = dialogBinding.etNewPassword.text.toString()
                val confirm = dialogBinding.etConfirmPassword.text.toString()

                dialogBinding.tilNewPassword.error = null
                dialogBinding.tilConfirmPassword.error = null

                // Валидируем новый пароль
                when (val result = PasswordValidator.validate(newPassword)) {
                    is PasswordValidator.Result.Invalid -> {
                        dialogBinding.tilNewPassword.error = result.reason
                        return@setOnClickListener
                    }
                    is PasswordValidator.Result.Valid -> { /* продолжаем */ }
                }

                if (newPassword != confirm) {
                    dialogBinding.tilConfirmPassword.error = getString(R.string.error_passwords_mismatch)
                    return@setOnClickListener
                }

                // Обновляем пароль в Firebase Auth и продолжаем сброс хранилища
                showLoading(true)
                lifecycleScope.launch {
                    val updated = withContext(Dispatchers.IO) {
                        firestore.updateAuthPassword(newPassword)
                    }
                    if (updated) {
                        // Пересохраняем локальные данные с новым паролем
                        val newSetupOk = withContext(Dispatchers.IO) {
                            runSetupAccount(email, newPassword, oldSalt)
                        }
                        if (newSetupOk) {
                            dialog.dismiss()
                            showVaultResetDialog(firestore, email, newPassword, oldSalt)
                        } else {
                            showLoading(false)
                            Toast.makeText(
                                this@LoginActivity,
                                getString(R.string.force_pwd_change_error),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } else {
                        showLoading(false)
                        Toast.makeText(
                            this@LoginActivity,
                            getString(R.string.force_pwd_change_error),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        dialog.show()
    }

    /**
     * Показывает диалог о сбросе хранилища, очищает данные и продолжает вход.
     */
    private fun showVaultResetDialog(
        firestore: FirestoreDataSource,
        email: String,
        password: String,
        salt: ByteArray
    ) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.vault_reset_title))
            .setMessage(getString(R.string.vault_reset_message))
            .setPositiveButton(getString(R.string.vault_reset_btn)) { _, _ ->
                showLoading(true)
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        // Удаляем данные из Firestore
                        firestore.deleteAllUserPasswords()
                        // Удаляем локальную БД
                        AppDatabase.resetInstance(this@LoginActivity)
                        // Генерируем новую соль и перезаписываем учётные данные
                        val newSalt = CryptoManager.generateSalt()
                        firestore.uploadUserSalt(newSalt)
                        runSetupAccount(email, password, newSalt)
                        firestore.uploadKeyVerifier(password, newSalt)
                    }
                    showLoading(false)
                    Toast.makeText(
                        this@LoginActivity,
                        getString(R.string.vault_reset_done),
                        Toast.LENGTH_LONG
                    ).show()
                    proceedToUnlock()
                }
            }
            .setCancelable(false)
            .show()
    }

    private fun proceedToUnlock() {
        Toast.makeText(this@LoginActivity, "Login successful", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this@LoginActivity, UnlockActivity::class.java))
        finish()
    }

    private fun showLoading(isLoading: Boolean) {
        binding.loadingOverlay.visibility = if (isLoading) View.VISIBLE else View.GONE
    }

    /**
     * Пытается сохранить локальные данные аккаунта (PBKDF2 + hash).
     * При AEADBadTagException / любой крипто-ошибке очищает испорченные данные
     * (характерно для OEM-восстановлений типа MIUI) и повторяет одну попытку.
     * Возвращает true при успехе, false — если не удалось и после повтора.
     */
    private fun runSetupAccount(email: String, password: String, salt: ByteArray): Boolean {
        repeat(2) { attempt ->
            try {
                CryptoManager.setupAccount(this, email, password, salt)
                return true
            } catch (e: Exception) {
                Log.e("LoginActivity", "setupAccount failed (attempt ${attempt + 1}/2)", e)
                if (attempt == 0) {
                    // Очищаем повреждённые данные и пробуем ещё раз
                    CryptoManager.clearCorruptedData(this)
                }
            }
        }
        return false
    }
}