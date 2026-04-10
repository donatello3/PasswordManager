package com.example.passwordmanager

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.passwordmanager.data.database.PasswordEntry
import com.example.passwordmanager.data.repository.PasswordRepository
import com.example.passwordmanager.databinding.ActivityAddEditBinding
import kotlinx.coroutines.launch
import org.passay.CharacterRule
import org.passay.EnglishCharacterData
import org.passay.PasswordGenerator
import java.util.Date

class AddEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddEditBinding
    private var entryId: Long = -1
    private var existingEntry: PasswordEntry? = null   // Store the original entry if editing

    private val repository: PasswordRepository?
        get() = (application as PasswordManagerApplication).appContainer.repository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up category spinner
        val categories = resources.getStringArray(R.array.categories_default).toMutableList()
        // Remove "All Categories" for add/edit screen
        categories.remove("All Categories")
        val adapterSpinner = ArrayAdapter(this, android.R.layout.simple_spinner_item, categories)
        adapterSpinner.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerCategory.adapter = adapterSpinner

        // Set up generate password button
        binding.btnGenerate.setOnClickListener {
            PasswordGeneratorDialog(this) { generatedPassword ->
                binding.etPassword.setText(generatedPassword)
                // Move cursor to the end
                binding.etPassword.setSelection(generatedPassword.length)
            }.show()
        }

        // Back button
        binding.btnBack.setOnClickListener {
            finish()
        }

        // Set up save button
        binding.btnSave.setOnClickListener {
            savePassword()
        }

        // Check if editing (entryId passed in intent)
        entryId = intent.getLongExtra("entry_id", -1)
        if (entryId != -1L) {
            loadEntryForEditing()
        }
    }

    private fun loadEntryForEditing() {
        lifecycleScope.launch {
            // Use the new getPasswordById method
            val entry = repository?.getPasswordById(entryId)
            if (entry != null) {
                existingEntry = entry   // Save the original for later (preserve createdAt)
                binding.etTitle.setText(entry.title)
                binding.etUsername.setText(entry.username)
                binding.etPassword.setText(entry.password)
                binding.etUrl.setText(entry.url)
                binding.etNotes.setText(entry.notes)

                // Set category spinner position
                val categories = resources.getStringArray(R.array.categories_default).toMutableList()
                categories.remove("All Categories")
                val pos = categories.indexOf(entry.category)
                if (pos >= 0) binding.spinnerCategory.setSelection(pos)
            } else {
                Toast.makeText(this@AddEditActivity, "Entry not found", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun savePassword() {
        val title = binding.etTitle.text.toString().trim()
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()
        val url = binding.etUrl.text.toString().trim()
        val notes = binding.etNotes.text.toString().trim()
        val category = binding.spinnerCategory.selectedItem.toString()

        if (title.isEmpty() || username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Title, username and password are required", Toast.LENGTH_SHORT).show()
            return
        }

        val now = Date()

        lifecycleScope.launch {
            if (entryId == -1L) {
                // Insert new entry
                val newEntry = PasswordEntry(
                    title = title,
                    username = username,
                    password = password,
                    url = url,
                    notes = notes,
                    category = category,
                    createdAt = now,
                    updatedAt = now
                )
                repository?.insert(newEntry)
            } else {
                // Update existing entry – preserve original createdAt
                val original = existingEntry
                if (original != null) {
                    val updatedEntry = original.copy(
                        title = title,
                        username = username,
                        password = password,
                        url = url,
                        notes = notes,
                        category = category,
                        updatedAt = now
                        // createdAt remains unchanged
                    )
                    repository?.update(updatedEntry)
                } else {
                    // Fallback: fetch fresh entry to preserve createdAt
                    val existing = repository?.getPasswordById(entryId)
                    if (existing != null) {
                        val updatedEntry = existing.copy(
                            title = title,
                            username = username,
                            password = password,
                            url = url,
                            notes = notes,
                            category = category,
                            updatedAt = now
                        )
                        repository?.update(updatedEntry)
                    } else {
                        Toast.makeText(this@AddEditActivity, "Error: entry not found", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                }
            }
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}