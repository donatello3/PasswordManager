package com.example.passwordmanager

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.passwordmanager.data.database.PasswordEntry
import com.example.passwordmanager.data.repository.PasswordRepository
import com.example.passwordmanager.ui.LoginActivity
import com.example.passwordmanager.utils.CryptoManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PasswordAdapter
    private lateinit var searchView: androidx.appcompat.widget.SearchView
    private lateinit var categorySpinner: Spinner
    private lateinit var fabAdd: FloatingActionButton
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView

    private val repository: PasswordRepository?
        get() = (application as PasswordManagerApplication).appContainer.repository

    private var allPasswords: List<PasswordEntry> = emptyList()
    private var currentCategoryFilter: String = "All Categories"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Set up toolbar
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        // Set up drawer
        drawerLayout = findViewById(R.id.drawerLayout)
        navigationView = findViewById(R.id.navigationView)

        // Hamburger icon opens the drawer
        toolbar.setNavigationOnClickListener {
            drawerLayout.openDrawer(navigationView)
        }

        // Set user email in drawer header
        val headerView = navigationView.getHeaderView(0)
        val tvUserEmail = headerView.findViewById<TextView>(R.id.tvNavUserEmail)
        tvUserEmail.text = FirebaseAuth.getInstance().currentUser?.email ?: ""

        // Navigation drawer item clicks
        navigationView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_logout -> {
                    drawerLayout.closeDrawers()
                    showLogoutConfirmation()
                }
            }
            true
        }

        // Initialize views
        recyclerView = findViewById(R.id.recyclerView)
        searchView = findViewById(R.id.searchView)
        categorySpinner = findViewById(R.id.categorySpinner)
        fabAdd = findViewById(R.id.fabAdd)
        loadingOverlay = findViewById(R.id.loadingOverlay)

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

        observeData()
    }

    private fun showLogoutConfirmation() {
        AlertDialog.Builder(this)
            .setTitle(R.string.logout_confirm_title)
            .setMessage(R.string.logout_confirm_message)
            .setPositiveButton(R.string.logout_confirm_yes) { _, _ -> performLogout() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun performLogout() {
        FirebaseAuth.getInstance().signOut()
        val app = application as PasswordManagerApplication
        app.currentMasterPassword = ""
        app.appContainer.clearRepository()
        CryptoManager.clearSession(this)
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun showLoading(isLoading: Boolean) {
        loadingOverlay.visibility = if (isLoading) View.VISIBLE else View.GONE
    }

    private fun observeData() {
        showLoading(true)
        lifecycleScope.launch {
            repository?.getAllPasswords()?.collect { passwords ->
                allPasswords = passwords
                filterPasswords()
                showLoading(false)
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
                lifecycleScope.launch { repository?.delete(entry) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

