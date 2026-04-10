package com.felix.occlient.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.felix.occlient.network.SshManagerHolder
import com.felix.occlient.ui.theme.TerminalAmber
import com.felix.occlient.ui.theme.TerminalGreen
import kotlinx.coroutines.launch

private enum class LogDirection { SENT, RECEIVED, SYSTEM }
private data class DisplayEntry(val text: String, val direction: LogDirection)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onNavigateBack: () -> Unit) {
    val logs by SshManagerHolder.sshManager.logs.collectAsState()
    val listState = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Entries are stored pre-cleaned (ANSI stripped) in SshManager.  Keep each entry as a
    // whole unit so direction prefixes (→ / ←) always stay with their content.
    // Direction is resolved here once so the items lambda does no repeated string searches.
    val displayEntries = remember(logs) {
        logs.mapNotNull { entry ->
            val trimmed = entry.trim()
            if (trimmed.isBlank()) null
            else {
                val direction = when {
                    trimmed.contains("] →") -> LogDirection.SENT
                    trimmed.contains("] ←") -> LogDirection.RECEIVED
                    else -> LogDirection.SYSTEM
                }
                DisplayEntry(trimmed, direction)
            }
        }
    }

    LaunchedEffect(displayEntries.size) {
        if (displayEntries.isNotEmpty()) listState.animateScrollToItem(displayEntries.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connection Log", fontFamily = FontFamily.Monospace) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(displayEntries.joinToString("\n") { it.text }))
                            scope.launch { snackbarHostState.showSnackbar("Logs copied to clipboard") }
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy logs to clipboard")
                    }
                    IconButton(onClick = { SshManagerHolder.sshManager.clearLogs() }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear logs")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (displayEntries.isEmpty()) {
                Text(
                    "No logs yet",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp)
                ) {
                    items(displayEntries) { entry ->
                        // Color-code by direction: green for sent (→), amber for received (←),
                        // muted for system/informational entries.
                        val entryColor = when (entry.direction) {
                            LogDirection.SENT -> TerminalGreen
                            LogDirection.RECEIVED -> TerminalAmber
                            LogDirection.SYSTEM -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(
                            text = entry.text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = entryColor,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}
