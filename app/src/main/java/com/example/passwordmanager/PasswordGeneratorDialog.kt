package com.example.passwordmanager

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import com.example.passwordmanager.databinding.DialogPasswordGeneratorBinding
import java.security.SecureRandom
import android.widget.SeekBar

class PasswordGeneratorDialog (
    private val context: Context,
    private val onPasswordGenerated: (String) -> Unit
) {

    private val binding = DialogPasswordGeneratorBinding.inflate(LayoutInflater.from(context))
    private var currentPassword: String = ""

    fun show() {
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.password_generator_title)
            .setView(binding.root)
            .create()

        // Set up SeekBar listener
        binding.sbLength.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val length = progress + 1  // Convert 0-31 to 1-32
                binding.tvLengthValue.text = length.toString()
                generateAndDisplayPassword()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Generate initial password and display
        generateAndDisplayPassword()

        // Refresh button: regenerate with same settings and update preview
        binding.btnRefresh.setOnClickListener {
            generateAndDisplayPassword()
        }

        // Use button: return current password and close
        binding.btnUse.setOnClickListener {
            onPasswordGenerated(currentPassword)
            dialog.dismiss()
        }

        // Cancel button: just close
        binding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun generateAndDisplayPassword() {
        val length = binding.sbLength.progress + 1  // Convert to actual length
        val includeDigits = binding.cbDigits.isChecked
        val includeLetters = binding.cbLetters.isChecked
        val includeSymbols = binding.cbSymbols.isChecked

        currentPassword = generatePassword(length, includeDigits, includeLetters, includeSymbols)
        binding.tvPasswordPreview.text = currentPassword
    }

    private fun generatePassword(
        length: Int,
        includeDigits: Boolean,
        includeLetters: Boolean,
        includeSymbols: Boolean
    ): String {
        // Build character pool
        val digits = "0123456789"
        val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        val symbols = "!@#$%^&*()_+[]{};:,.?"

        val pool = StringBuilder()
        if (includeDigits) pool.append(digits)
        if (includeLetters) pool.append(letters)
        if (includeSymbols) pool.append(symbols)

        if (pool.isEmpty()) {
            // Fallback: at least letters
            pool.append(letters)
        }

        val random = SecureRandom()
        return (1..length)
            .map { pool[random.nextInt(pool.length)] }
            .joinToString("")
    }
}
