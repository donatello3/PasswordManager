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
import android.view.View
import androidx.appcompat.app.AlertDialog

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

            // Verify local password
            if (CryptoManager.verifyPasswordOnly(this, password)) {
                showLoading(true)

                lifecycleScope.launch {
                    try {
                        val email = CryptoManager.getLoggedInEmail(this@UnlockActivity)
                        if (email != null) {
                            val firestore = FirestoreDataSource(this@UnlockActivity)

                            // 1. Check email verification status
                            val isVerified = firestore.isEmailVerified()
                            if (!isVerified) {
                                showLoading(false)
                                showEmailNotVerifiedDialog(firestore)
                                return@launch
                            }

                            // 2. Email is verified → sign in to Firebase
                            val signInSuccess = firestore.signInWithEmail(email, password)
                            if (!signInSuccess) {
                                Toast.makeText(this@UnlockActivity, "Firebase sign in failed", Toast.LENGTH_SHORT).show()
                                showLoading(false)
                                return@launch
                            }

                            // 3. Derive key and provide repository
                            val key = CryptoManager.getDatabaseKey(this@UnlockActivity, password)
                            if (key != null) {
                                val app = application as PasswordManagerApplication
                                app.currentMasterPassword = password
                                app.appContainer.provideRepository(key)

                                // 4. Sync remote passwords (optional, may be long)
                                val repository = app.appContainer.repository
                                repository?.syncPasswordsFromRemote()
                            } else {
                                Toast.makeText(this@UnlockActivity, "Failed to derive key", Toast.LENGTH_SHORT).show()
                                showLoading(false)
                                return@launch
                            }
                        } else {
                            Toast.makeText(this@UnlockActivity, "No email found", Toast.LENGTH_SHORT).show()
                            showLoading(false)
                            return@launch
                        }

                        // 5. All good → go to MainActivity
                        startActivity(Intent(this@UnlockActivity, MainActivity::class.java))
                        finish()
                    } catch (e: Exception) {
                        Toast.makeText(this@UnlockActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    } finally {
                        showLoading(false)
                    }
                }
            } else {
                Toast.makeText(this, "Wrong password", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showEmailNotVerifiedDialog(firestore: FirestoreDataSource) {
        AlertDialog.Builder(this)
            .setTitle("Email Not Verified")
            .setMessage("Please verify your email address before accessing your vault. Check your inbox (and spam folder) for the verification link.")
            .setPositiveButton("Resend Email") { _, _ ->
                lifecycleScope.launch {
                    val success = firestore.sendVerificationEmail()
                    if (success) {
                        Toast.makeText(this@UnlockActivity, "Verification email resent. Please check your inbox.", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@UnlockActivity, "Failed to resend email. Try again later.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLoading(isLoading: Boolean) {
        binding.loadingOverlay.visibility = if (isLoading) View.VISIBLE else View.GONE
    }
}