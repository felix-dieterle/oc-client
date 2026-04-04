package com.felix.occlient.data.repository

import com.felix.occlient.data.database.SessionDao
import com.felix.occlient.data.model.Session
import kotlinx.coroutines.flow.Flow

class SessionRepository(private val sessionDao: SessionDao) {
    fun getAllSessions(): Flow<List<Session>> = sessionDao.getAllSessions()

    suspend fun getSessionById(id: String): Session? = sessionDao.getSessionById(id)

    suspend fun createSession(name: String): Session {
        val session = Session(name = name)
        sessionDao.insertSession(session)
        return session
    }

    suspend fun updateSession(session: Session) = sessionDao.updateSession(session)

    suspend fun deleteSession(session: Session) = sessionDao.deleteSession(session)

    suspend fun incrementMessageCount(sessionId: String) {
        val session = sessionDao.getSessionById(sessionId) ?: return
        sessionDao.updateSession(
            session.copy(
                messageCount = session.messageCount + 1,
                lastUsed = System.currentTimeMillis()
            )
        )
    }
}
