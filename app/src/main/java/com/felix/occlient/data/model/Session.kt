package com.felix.occlient.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * RUN  – starts an interactive opencode TUI session via `opencode run`.
 * SERVE – connects to a running `opencode serve` instance via SSH.
 */
enum class SessionType { RUN, SERVE }

@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsed: Long = System.currentTimeMillis(),
    val messageCount: Int = 0,
    val sessionType: SessionType = SessionType.RUN
)
