package com.example.passwordmanager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.passwordmanager.data.database.PasswordEntry
import com.example.passwordmanager.data.repository.PasswordRepository
import com.example.passwordmanager.databinding.ActivityPasswordDetailBinding
import kotlinx.coroutines.launch

class PasswordDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPasswordDetailBinding
    private var entryId: Long = -1
    private var entry: PasswordEntry? = null

    private val repository: PasswordRepository?
        get() = (application as PasswordManagerApplication).appContainer.repository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPasswordDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        entryId = intent.getLongExtra("entry_id", -1)
        if (entryId == -1L) {
            Toast.makeText(this, "Error: No entry ID", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        loadEntry()

        binding.btnCopyUsername.setOnClickListener {
            entry?.username?.let { copyToClipboard(it, "username") }
        }

        binding.btnCopyPassword.setOnClickListener {
            entry?.password?.let { copyToClipboard(it, "password") }
        }

        binding.btnEdit.setOnClickListener {
            val intent = Intent(this, AddEditActivity::class.java)
            intent.putExtra("entry_id", entryId)
            startActivity(intent)
        }

        // Back button
        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        // Reload entry when returning from edit screen
        loadEntry()
    }

    private fun loadEntry() {
        lifecycleScope.launch {
            entry = repository?.getPasswordById(entryId)
            if (entry != null) {
                displayEntry(entry!!)
            } else {
                Toast.makeText(this@PasswordDetailActivity, "Entry not found", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun displayEntry(entry: PasswordEntry) {
        binding.tvTitle.text = entry.title
        binding.tvUsername.text = entry.username
        binding.tvPassword.text = entry.password
        binding.tvUrl.text = entry.url.ifEmpty { "—" }
        binding.tvNotes.text = entry.notes.ifEmpty { "—" }
        binding.tvCategory.text = entry.category
    }

    private fun copyToClipboard(text: String, field: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("PasswordManager", text)
        clipboard.setPrimaryClip(clip)

        val message = when (field) {
            "username" -> getString(R.string.username_copied)
            "password" -> getString(R.string.password_copied)
            else -> "Copied to clipboard"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}