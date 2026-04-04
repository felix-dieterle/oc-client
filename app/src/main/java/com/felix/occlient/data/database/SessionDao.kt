package com.felix.occlient.data.database

import androidx.room.*
import com.felix.occlient.data.model.Session
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY lastUsed DESC")
    fun getAllSessions(): Flow<List<Session>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSessionById(id: String): Session?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: Session)

    @Update
    suspend fun updateSession(session: Session)

    @Delete
    suspend fun deleteSession(session: Session)
}
