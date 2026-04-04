package com.felix.occlient

import android.app.Application
import com.felix.occlient.data.database.AppDatabase
import com.felix.occlient.data.preferences.SettingsDataStore
import com.felix.occlient.data.repository.SessionRepository

class OcClientApplication : Application() {
    val database by lazy { AppDatabase.getDatabase(this) }
    val settingsDataStore by lazy { SettingsDataStore(this) }
    val sessionRepository by lazy { SessionRepository(database.sessionDao()) }
}
