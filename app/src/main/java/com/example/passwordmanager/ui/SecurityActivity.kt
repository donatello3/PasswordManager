package com.example.passwordmanager.ui

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.example.passwordmanager.PasswordManagerApplication
import com.example.passwordmanager.R
import com.example.passwordmanager.utils.CryptoManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial

class SecurityActivity : AppCompatActivity() {

    private lateinit var switchBiometric: SwitchMaterial
    private lateinit var biometricSettingRow: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_security)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        switchBiometric = findViewById(R.id.switchBiometric)
        biometricSettingRow = findViewById(R.id.biometricSettingRow)

        val biometricStatus = checkBiometricAvailability()

        // Disable the row if biometrics are not available/enrolled
        if (biometricStatus != BiometricManager.BIOMETRIC_SUCCESS) {
            biometricSettingRow.isEnabled = false
            biometricSettingRow.alpha = 0.4f
            val message = when (biometricStatus) {
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
                BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                    getString(R.string.biometric_not_available)
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                    getString(R.string.biometric_not_enrolled)
                else -> getString(R.string.biometric_not_available)
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }

        // Restore current state
        switchBiometric.isChecked = CryptoManager.isBiometricEnabled(this)

        // Handle tap on the whole row
        biometricSettingRow.setOnClickListener {
            if (biometricStatus != BiometricManager.BIOMETRIC_SUCCESS) return@setOnClickListener

            if (!switchBiometric.isChecked) {
                // Enable biometric → confirm with fingerprint first
                showBiometricEnrollPrompt()
            } else {
                // Disable biometric
                disableBiometric()
            }
        }
    }

    private fun checkBiometricAvailability(): Int {
        val biometricManager = BiometricManager.from(this)
        return biometricManager.canAuthenticate(BIOMETRIC_WEAK)
    }

    private fun showBiometricEnrollPrompt() {
        val masterPassword = (application as PasswordManagerApplication).currentMasterPassword
        if (masterPassword.isEmpty()) {
            Toast.makeText(this, "Session expired. Please re-open the app.", Toast.LENGTH_SHORT).show()
            return
        }

        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                // Save master password and enable biometric
                CryptoManager.saveMasterPasswordForBiometric(this@SecurityActivity, masterPassword)
                CryptoManager.setBiometricEnabled(this@SecurityActivity, true)
                switchBiometric.isChecked = true
                Toast.makeText(this@SecurityActivity, getString(R.string.biometric_enabled), Toast.LENGTH_SHORT).show()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                // User cancelled or error — don't change state
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                Toast.makeText(this@SecurityActivity, getString(R.string.biometric_error), Toast.LENGTH_SHORT).show()
            }
        }

        val biometricPrompt = BiometricPrompt(this, executor, callback)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_prompt_title))
            .setSubtitle(getString(R.string.biometric_prompt_subtitle))
            .setNegativeButtonText(getString(R.string.cancel))
            .setAllowedAuthenticators(BIOMETRIC_WEAK)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    private fun disableBiometric() {
        CryptoManager.setBiometricEnabled(this, false)
        switchBiometric.isChecked = false
        Toast.makeText(this, getString(R.string.biometric_disabled), Toast.LENGTH_SHORT).show()
    }
}

