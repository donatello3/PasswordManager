package io.kmanager.app.ui

import android.os.Bundle
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import io.kmanager.app.PasswordManagerApplication
import io.kmanager.app.R
import io.kmanager.app.utils.AppLockManager
import io.kmanager.app.utils.CryptoManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial

class SecurityActivity : AppCompatActivity() {

    private companion object {
        private const val TAG = "SecurityActivity"
    }

    private lateinit var switchBiometric: SwitchMaterial
    private lateinit var biometricSettingRow: LinearLayout
    private lateinit var autoLockSettingRow: LinearLayout
    private lateinit var tvAutoLockValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_security)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        switchBiometric = findViewById(R.id.switchBiometric)
        biometricSettingRow = findViewById(R.id.biometricSettingRow)
        autoLockSettingRow = findViewById(R.id.autoLockSettingRow)
        tvAutoLockValue = findViewById(R.id.tvAutoLockValue)

        // We require BIOMETRIC_STRONG because we use a CryptoObject (biometric-bound key).
        val biometricStatus = BiometricManager.from(this).canAuthenticate(BIOMETRIC_STRONG)

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

        switchBiometric.isChecked = CryptoManager.isBiometricEnabled(this)

        biometricSettingRow.setOnClickListener {
            if (biometricStatus != BiometricManager.BIOMETRIC_SUCCESS) return@setOnClickListener

            if (!switchBiometric.isChecked) {
                showBiometricEnrollPrompt()
            } else {
                disableBiometric()
            }
        }

        updateAutoLockValueLabel()
        autoLockSettingRow.setOnClickListener { showAutoLockTimeoutDialog() }
    }

    // ── Auto-lock timeout ────────────────────────────────────────────────────

    private fun updateAutoLockValueLabel() {
        val currentMillis = AppLockManager.getTimeoutMillis(this)
        val label = AppLockManager.TIMEOUT_OPTIONS.firstOrNull { it.first == currentMillis }?.second
            ?: AppLockManager.TIMEOUT_OPTIONS.first { it.first == AppLockManager.DEFAULT_TIMEOUT_MILLIS }.second
        tvAutoLockValue.text = label
    }

    private fun showAutoLockTimeoutDialog() {
        val options = AppLockManager.TIMEOUT_OPTIONS
        val labels = options.map { it.second }.toTypedArray()
        val currentMillis = AppLockManager.getTimeoutMillis(this)
        val checkedIndex = options.indexOfFirst { it.first == currentMillis }.let { if (it >= 0) it else options.indexOfFirst { o -> o.first == AppLockManager.DEFAULT_TIMEOUT_MILLIS } }

        AlertDialog.Builder(this)
            .setTitle(R.string.auto_lock_dialog_title)
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                AppLockManager.setTimeoutMillis(this, options[which].first)
                updateAutoLockValueLabel()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showBiometricEnrollPrompt() {
        val masterPassword = (application as PasswordManagerApplication).currentMasterPassword
        if (masterPassword == null || masterPassword.isEmpty()) {
            Toast.makeText(this, "Session expired. Please re-open the app.", Toast.LENGTH_SHORT).show()
            return
        }

        // Prepare encrypt cipher — this is a biometric-bound Keystore key operation
        val encryptCipher = try {
            CryptoManager.prepareBiometricEncryptCipher()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare biometric cipher", e)
            Toast.makeText(this, "Failed to prepare biometric setup", Toast.LENGTH_SHORT).show()
            return
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_prompt_title))
            .setSubtitle(getString(R.string.biometric_prompt_subtitle))
            .setNegativeButtonText(getString(R.string.cancel))
            // CryptoObject requires BIOMETRIC_STRONG — no DEVICE_CREDENTIAL allowed here
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .build()

        BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    val cipher = result.cryptoObject?.cipher
                    if (cipher == null) {
                        Toast.makeText(this@SecurityActivity, "Biometric cipher unavailable", Toast.LENGTH_SHORT).show()
                        return
                    }
                    try {
                        CryptoManager.encryptWithCipher(this@SecurityActivity, cipher, String(masterPassword))
                        CryptoManager.setBiometricEnabled(this@SecurityActivity, true)
                        switchBiometric.isChecked = true
                        Toast.makeText(this@SecurityActivity, "Biometric unlock enabled", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Log.e(TAG, "encryptWithCipher failed", e)
                        Toast.makeText(this@SecurityActivity, "Failed to save biometric data", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    // User cancelled — no state change
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Toast.makeText(this@SecurityActivity, getString(R.string.biometric_error), Toast.LENGTH_SHORT).show()
                }
            }
        ).authenticate(promptInfo, BiometricPrompt.CryptoObject(encryptCipher))
    }

    private fun disableBiometric() {
        CryptoManager.clearBiometricData(this)
        switchBiometric.isChecked = false
        Toast.makeText(this, getString(R.string.biometric_disabled), Toast.LENGTH_SHORT).show()
    }
}
