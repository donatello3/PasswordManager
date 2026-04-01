package com.example.passwordmanager.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.passwordmanager.data.database.PasswordEntry
import com.example.passwordmanager.data.repository.PasswordRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel (private val repository: PasswordRepository) : ViewModel() {

    private val _passwords = MutableStateFlow<List<PasswordEntry>>(emptyList())
    val passwords: StateFlow<List<PasswordEntry>> = _passwords

    private val _categories = MutableStateFlow<List<String>>(emptyList())
    val categories: StateFlow<List<String>> = _categories

    init {
        loadAllPasswords()
        loadCategories()
    }

    fun loadAllPasswords() {
        viewModelScope.launch {
            repository.getAllPasswords().collect { list ->
                _passwords.value = list
            }
        }
    }

    fun search(query: String) {
        viewModelScope.launch {
            repository.searchPasswords(query).collect { list ->
                _passwords.value = list
            }
        }
    }

    fun filterByCategory(category: String) {
        viewModelScope.launch {
            repository.getPasswordsByCategory(category).collect { list ->
                _passwords.value = list
            }
        }
    }

    fun loadCategories() {
        viewModelScope.launch {
            repository.getAllCategories().collect { list ->
                _categories.value = list
            }
        }
    }

    fun deletePassword(entry: PasswordEntry) {
        viewModelScope.launch {
            repository.delete(entry)
        }
    }
}