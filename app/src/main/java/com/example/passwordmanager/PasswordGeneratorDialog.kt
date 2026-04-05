package com.example.passwordmanager

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import com.example.passwordmanager.databinding.DialogPasswordGeneratorBinding
import java.security.SecureRandom

class PasswordGeneratorDialog (
    private val context: Context,
    private val onPasswordGenerated: (String) -> Unit
) {

    private val binding = DialogPasswordGeneratorBinding.inflate(LayoutInflater.from(context))

    fun show() {
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.password_generator_title)
            .setView(binding.root)
            .create()

        binding.btnGenerate.setOnClickListener {
            val length = binding.etLength.text.toString().toIntOrNull() ?: 16
            val includeDigits = binding.cbDigits.isChecked
            val includeLetters = binding.cbLetters.isChecked
            val includeSymbols = binding.cbSymbols.isChecked

            val password = generatePassword(length, includeDigits, includeLetters, includeSymbols)
            onPasswordGenerated(password)
            dialog.dismiss()
        }

        binding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
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