package com.example.rhdtool.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.rhdtool.data.Event
import com.example.rhdtool.data.LogRepo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class MainViewModel(private val repo: LogRepo) : ViewModel() {
    val events: Flow<List<Event>> = repo.eventsFlow

    fun insert(event: Event) = viewModelScope.launch {
        repo.insert(event)
    }

    fun clear() = viewModelScope.launch {
        repo.clear()
    }
}
