package io.kmanager.app.ui

import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.kmanager.app.data.remote.FirestoreDataSource
import io.kmanager.app.databinding.ActivityForgotPasswordBinding
import kotlinx.coroutines.launch

class ForgotPasswordActivity : AppCompatActivity() {

    private lateinit var binding: ActivityForgotPasswordBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityForgotPasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSendReset.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            binding.tilEmail.error = null

            if (email.isEmpty()) {
                binding.tilEmail.error = getString(io.kmanager.app.R.string.error_email_empty)
                binding.etEmail.requestFocus()
                return@setOnClickListener
            }
            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                binding.tilEmail.error = getString(io.kmanager.app.R.string.error_email_invalid)
                binding.etEmail.requestFocus()
                return@setOnClickListener
            }

            showDataLossConfirmationDialog(email)
        }

        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    /**
     * Предупреждаем пользователя о потере данных перед отправкой письма.
     */
    private fun showDataLossConfirmationDialog(email: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(io.kmanager.app.R.string.forgot_password_confirm_title))
            .setMessage(getString(io.kmanager.app.R.string.forgot_password_confirm_message))
            .setPositiveButton(getString(io.kmanager.app.R.string.forgot_password_confirm_yes)) { _, _ ->
                sendResetEmail(email)
            }
            .setNegativeButton(getString(io.kmanager.app.R.string.cancel), null)
            .show()
    }

    private fun sendResetEmail(email: String) {
        showLoading(true)
        val firestore = FirestoreDataSource(this)
        lifecycleScope.launch {
            try {
                val success = firestore.sendPasswordResetEmail(email)
                if (success) {
                    showSuccessDialog()
                } else {
                    Toast.makeText(
                        this@ForgotPasswordActivity,
                        getString(io.kmanager.app.R.string.forgot_password_send_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@ForgotPasswordActivity,
                    getString(io.kmanager.app.R.string.forgot_password_send_failed),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private fun showSuccessDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(io.kmanager.app.R.string.forgot_password_success_title))
            .setMessage(getString(io.kmanager.app.R.string.forgot_password_success_message))
            .setPositiveButton(getString(io.kmanager.app.R.string.forgot_password_success_btn)) { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun showLoading(isLoading: Boolean) {
        binding.loadingOverlay.visibility = if (isLoading) View.VISIBLE else View.GONE
    }
}

