package com.example.rhdtool.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: Event)

    @Query("SELECT * FROM events ORDER BY timestamp DESC")
    fun getAllFlow(): Flow<List<Event>>

    @Query("DELETE FROM events")
    suspend fun clear()
}
