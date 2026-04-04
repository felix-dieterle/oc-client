package com.felix.occlient

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.felix.occlient.ui.navigation.AppNavigation
import com.felix.occlient.ui.theme.OcClientTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OcClientTheme {
                AppNavigation()
            }
        }
    }
}
