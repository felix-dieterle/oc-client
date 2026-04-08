package com.felix.occlient

import android.app.Application
import com.felix.occlient.data.database.AppDatabase
import com.felix.occlient.data.preferences.SettingsDataStore
import com.felix.occlient.data.repository.SessionRepository
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class OcClientApplication : Application() {
    val database by lazy { AppDatabase.getDatabase(this) }
    val settingsDataStore by lazy { SettingsDataStore(this) }
    val sessionRepository by lazy { SessionRepository(database.sessionDao()) }

    override fun onCreate() {
        super.onCreate()
        // Remove Android's built-in (limited) BouncyCastle provider and replace it with the
        // full BouncyCastle library so that JSch can use algorithms such as Ed25519 and
        // curve25519-sha256 that are otherwise reported as "not available".
        // The built-in Android "BC" provider is incomplete; inserting the full provider at
        // position 1 (highest priority) after removing the stub ensures correct resolution.
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }
}
