package com.example.rhdtool.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeUtils {
    private val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun nowMillis(): Long = System.currentTimeMillis()
    fun nowTimeString(): String = formatter.format(Date())
}
