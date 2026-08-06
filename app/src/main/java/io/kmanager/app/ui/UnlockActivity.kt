package io.kmanager.app.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.kmanager.app.MainActivity
import io.kmanager.app.PasswordManagerApplication
import io.kmanager.app.R
import io.kmanager.app.data.database.AppDatabase
import io.kmanager.app.data.remote.FirestoreDataSource
import io.kmanager.app.databinding.ActivityUnlockBinding
import io.kmanager.app.utils.CryptoManager
import kotlinx.coroutines.launch

class UnlockActivity : AppCompatActivity() {

    private companion object {
        private const val TAG = "UnlockActivity"
    }

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

        binding.btnBackToLogin.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Sign out")
                .setMessage("Are you sure you want to sign out and return to the login screen?")
                .setPositiveButton("Sign out") { _, _ -> signOutAndGoToLogin() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ── Biometric ────────────────────────────────────────────────────────────

    private fun setupBiometric() {
        val biometricEnabled = CryptoManager.isBiometricEnabled(this)
        val hasStored = CryptoManager.hasBiometricPasswordStored(this)
        val canAuth = BiometricManager.from(this).canAuthenticate(BIOMETRIC_STRONG)

        if (biometricEnabled && hasStored && canAuth == BiometricManager.BIOMETRIC_SUCCESS) {
            binding.btnBiometric.visibility = View.VISIBLE
            binding.btnBiometric.setOnClickListener { showBiometricPrompt() }
            showBiometricPrompt()
        }
    }

    private fun showBiometricPrompt() {
        // Prepare the decrypt cipher using the stored IV
        val decryptCipher = CryptoManager.prepareBiometricDecryptCipher(this)
        if (decryptCipher == null) {
            // Key was invalidated (new biometrics enrolled) — biometric auto-disabled
            Toast.makeText(this, "Biometric data cleared. Please re-enable in Security settings.", Toast.LENGTH_LONG).show()
            binding.btnBiometric.visibility = View.GONE
            return
        }

        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                val cipher = result.cryptoObject?.cipher
                if (cipher == null) {
                    Toast.makeText(this@UnlockActivity, "Biometric cipher unavailable", Toast.LENGTH_SHORT).show()
                    return
                }
                val password = CryptoManager.decryptWithCipher(this@UnlockActivity, cipher)
                if (password != null) {
                    proceedWithPassword(password)
                } else {
                    Toast.makeText(this@UnlockActivity, "Biometric data not found. Enter password.", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                // User cancelled — password field available
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
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .build()

        BiometricPrompt(this, executor, callback)
            .authenticate(promptInfo, BiometricPrompt.CryptoObject(decryptCipher))
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
                app.currentMasterPassword = password.toCharArray()
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

    private fun signOutAndGoToLogin() {
        // Clear master password from memory
        val app = application as PasswordManagerApplication
        app.currentMasterPassword = null

        // Close the encrypted DB so it can be re-opened on next login
        AppDatabase.resetInstance(this)

        // Sign out from Firebase
        com.google.firebase.auth.FirebaseAuth.getInstance().signOut()

        // Navigate back to LoginActivity, clear the back stack
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}