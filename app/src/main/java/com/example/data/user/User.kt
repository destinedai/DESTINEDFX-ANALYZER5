package com.example.data.user

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class User(
    @PrimaryKey val email: String,
    val username: String,
    val passwordHash: String,
    val uid: String = "",
    val registrationDate: Long = System.currentTimeMillis()
)
