package com.example.rhdtool.data

class LogRepo(private val dao: EventDao) {
    val eventsFlow = dao.getAllFlow()

    suspend fun insert(event: Event) = dao.insert(event)

    suspend fun clear() = dao.clear()
}
