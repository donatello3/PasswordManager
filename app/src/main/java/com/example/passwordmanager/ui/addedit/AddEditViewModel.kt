package com.example.passwordmanager.ui.addedit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.passwordmanager.data.database.PasswordEntry
import com.example.passwordmanager.data.repository.PasswordRepository
import kotlinx.coroutines.launch

class AddEditViewModel(private val repository: PasswordRepository) : ViewModel() {

    suspend fun insert(entry: PasswordEntry) {
        viewModelScope.launch {
            repository.insert(entry)
        }
    }

    suspend fun update(entry: PasswordEntry) {
        viewModelScope.launch {
            repository.update(entry)
        }
    }
}