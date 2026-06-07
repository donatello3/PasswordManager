package com.example.passwordmanager.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.passwordmanager.MainActivity
import com.example.passwordmanager.PasswordManagerApplication
import com.example.passwordmanager.R
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

        setupBiometric()

        binding.btnUnlock.setOnClickListener {
            val password = binding.etPassword.text.toString()
            if (password.isEmpty()) {
                Toast.makeText(this, "Enter password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (CryptoManager.verifyPasswordOnly(this, password)) {
                proceedWithPassword(password)
            } else {
                Toast.makeText(this, "Wrong password", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Biometric ────────────────────────────────────────────────────────────

    private fun setupBiometric() {
        val biometricEnabled = CryptoManager.isBiometricEnabled(this)
        val storedPassword = CryptoManager.getMasterPasswordForBiometric(this)
        val canAuth = BiometricManager.from(this).canAuthenticate(BIOMETRIC_WEAK)

        if (biometricEnabled && storedPassword != null && canAuth == BiometricManager.BIOMETRIC_SUCCESS) {
            binding.btnBiometric.visibility = View.VISIBLE
            binding.btnBiometric.setOnClickListener { showBiometricPrompt() }
            // Auto-show prompt on screen open
            showBiometricPrompt()
        }
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                val password = CryptoManager.getMasterPasswordForBiometric(this@UnlockActivity)
                if (password != null) {
                    proceedWithPassword(password)
                } else {
                    Toast.makeText(this@UnlockActivity, "Biometric data not found. Enter password.", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                // User cancelled — no action needed, password field is available
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                Toast.makeText(this@UnlockActivity, getString(R.string.biometric_error), Toast.LENGTH_SHORT).show()
            }
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_prompt_title))
            .setSubtitle(getString(R.string.biometric_prompt_subtitle))
            .setNegativeButtonText(getString(R.string.biometric_prompt_negative))
            .setAllowedAuthenticators(BIOMETRIC_WEAK)
            .build()

        BiometricPrompt(this, executor, callback).authenticate(promptInfo)
    }

    // ── Core unlock flow ─────────────────────────────────────────────────────

    private fun proceedWithPassword(password: String) {
        showLoading(true)
        lifecycleScope.launch {
            try {
                val email = CryptoManager.getLoggedInEmail(this@UnlockActivity)
                if (email == null) {
                    Toast.makeText(this@UnlockActivity, "No email found", Toast.LENGTH_SHORT).show()
                    showLoading(false)
                    return@launch
                }

                val firestore = FirestoreDataSource(this@UnlockActivity)

                // 1. Check email verification
                if (!firestore.isEmailVerified()) {
                    showLoading(false)
                    showEmailNotVerifiedDialog(firestore)
                    return@launch
                }

                // 2. Sign in to Firebase
                if (!firestore.signInWithEmail(email, password)) {
                    Toast.makeText(this@UnlockActivity, "Firebase sign in failed", Toast.LENGTH_SHORT).show()
                    showLoading(false)
                    return@launch
                }

                // 3. Derive key and provide repository
                val key = CryptoManager.getDatabaseKey(this@UnlockActivity, password)
                if (key == null) {
                    Toast.makeText(this@UnlockActivity, "Failed to derive key", Toast.LENGTH_SHORT).show()
                    showLoading(false)
                    return@launch
                }

                val app = application as PasswordManagerApplication
                app.currentMasterPassword = password
                app.appContainer.provideRepository(key)
                app.appContainer.repository?.syncPasswordsFromRemote()

                // 4. Navigate to main screen
                startActivity(Intent(this@UnlockActivity, MainActivity::class.java))
                finish()

            } catch (e: Exception) {
                Toast.makeText(this@UnlockActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                showLoading(false)
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun showEmailNotVerifiedDialog(firestore: FirestoreDataSource) {
        AlertDialog.Builder(this)
            .setTitle("Email Not Verified")
            .setMessage("Please verify your email address before accessing your vault. Check your inbox (and spam folder) for the verification link.")
            .setPositiveButton("Resend Email") { _, _ ->
                lifecycleScope.launch {
                    val success = firestore.sendVerificationEmail()
                    Toast.makeText(
                        this@UnlockActivity,
                        if (success) "Verification email resent. Please check your inbox."
                        else "Failed to resend email. Try again later.",
                        if (success) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLoading(isLoading: Boolean) {
        binding.loadingOverlay.visibility = if (isLoading) View.VISIBLE else View.GONE
    }
}