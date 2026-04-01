package com.example.passwordmanager.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "passwords")
data class PasswordEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,            // e.g., "Google Account"
    val username: String,
    val password: String,
    val url: String = "",
    val notes: String = "",
    val category: String = "General",
    val createdAt: Date = Date(),
    val updatedAt: Date = Date()
)