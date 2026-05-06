package com.example.doomtodoomscroll

import android.graphics.drawable.Drawable

data class AppLimitModel(
    val appName: String,
    val packageName: String,
    val icon: Drawable,
    var hourLimit: Int = 0, // This is the variable the user changes in the UI
    var usageMinutes: Long = 0,
    var isLoading: Boolean = false
)