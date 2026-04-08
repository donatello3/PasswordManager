package com.example.passwordmanager

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.passwordmanager.data.database.PasswordEntry
import com.example.passwordmanager.data.repository.PasswordRepository
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PasswordAdapter
    private lateinit var searchView: androidx.appcompat.widget.SearchView
    private lateinit var categorySpinner: Spinner
    private lateinit var fabAdd: FloatingActionButton

    private val repository: PasswordRepository?
        get() = (application as PasswordManagerApplication).appContainer.repository

    private var allPasswords: List<PasswordEntry> = emptyList()
    private var currentCategoryFilter: String = "All Categories"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        recyclerView = findViewById(R.id.recyclerView)
        searchView = findViewById(R.id.searchView)
        categorySpinner = findViewById(R.id.categorySpinner)
        fabAdd = findViewById(R.id.fabAdd)

        // Set up RecyclerView
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = PasswordAdapter(emptyList(),
            onItemClick = { entry -> editPassword(entry) },
            onItemLongClick = { entry -> confirmDelete(entry) }
        )
        recyclerView.adapter = adapter

        // Set up category spinner
        val categories = resources.getStringArray(R.array.categories_default).toMutableList()
        val adapterSpinner = ArrayAdapter(this, android.R.layout.simple_spinner_item, categories)
        adapterSpinner.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        categorySpinner.adapter = adapterSpinner
        categorySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                currentCategoryFilter = categories[position]
                filterPasswords()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }

        // Set up search
        searchView.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                filterPasswords(query)
                return true
            }
            override fun onQueryTextChange(newText: String?): Boolean {
                filterPasswords(newText)
                return true
            }
        })

        // FAB click
        fabAdd.setOnClickListener {
            startActivity(Intent(this, AddEditActivity::class.java))
        }

        // Load passwords
        loadPasswords()
    }

    private fun loadPasswords() {
        lifecycleScope.launch {
            repository?.getAllPasswords()?.collect { passwords ->
                allPasswords = passwords
                filterPasswords()
            }
        }
    }

    private fun filterPasswords(query: String? = null) {
        var filtered = allPasswords
        if (currentCategoryFilter != "All Categories") {
            filtered = filtered.filter { it.category == currentCategoryFilter }
        }
        if (!query.isNullOrEmpty()) {
            filtered = filtered.filter {
                it.title.contains(query, ignoreCase = true) ||
                        it.username.contains(query, ignoreCase = true)
            }
        }
        updateAdapter(filtered)
    }

    private fun updateAdapter(passwords: List<PasswordEntry>) {
        adapter = PasswordAdapter(passwords,
            onItemClick = { editPassword(it) },
            onItemLongClick = { confirmDelete(it) }
        )
        recyclerView.adapter = adapter
    }

    private fun editPassword(entry: PasswordEntry) {
        val intent = Intent(this, PasswordDetailActivity::class.java)
        intent.putExtra("entry_id", entry.id)
        startActivity(intent)
    }

    private fun confirmDelete(entry: PasswordEntry) {
        AlertDialog.Builder(this)
            .setTitle("Delete Password")
            .setMessage("Are you sure you want to delete ${entry.title}?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    repository?.delete(entry)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
