package com.felix.occlient.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.felix.occlient.network.SshConnectionState
import com.felix.occlient.ui.theme.TerminalGreen
import com.felix.occlient.viewmodel.ChatMessage
import com.felix.occlient.viewmodel.ChatViewModel
import com.felix.occlient.viewmodel.MessageType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToLog: () -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val sessionName by viewModel.sessionName.collectAsState()
    val error by viewModel.error.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    val statusColor = when (connectionState) {
        SshConnectionState.CONNECTED -> TerminalGreen
        SshConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary
        SshConnectionState.ERROR -> MaterialTheme.colorScheme.error
        SshConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(sessionName, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.Circle, contentDescription = null, tint = statusColor, modifier = Modifier.size(8.dp))
                            Text(connectionState.name.lowercase(), style = MaterialTheme.typography.labelSmall, color = statusColor)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToLog) {
                        Icon(Icons.Default.Assignment, contentDescription = "Logs")
                    }
                    if (connectionState == SshConnectionState.CONNECTED) {
                        IconButton(onClick = { viewModel.disconnect() }) {
                            Icon(Icons.Default.PowerSettingsNew, contentDescription = "Disconnect", tint = MaterialTheme.colorScheme.error)
                        }
                    } else if (connectionState == SshConnectionState.DISCONNECTED || connectionState == SshConnectionState.ERROR) {
                        IconButton(onClick = { viewModel.connect() }) {
                            Icon(Icons.Default.PowerSettingsNew, contentDescription = "Connect", tint = TerminalGreen)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp) {
                Row(
                    modifier = Modifier.padding(8.dp).fillMaxWidth().navigationBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Enter prompt...", fontFamily = FontFamily.Monospace) },
                        singleLine = false,
                        maxLines = 4,
                        enabled = connectionState == SshConnectionState.CONNECTED
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                viewModel.sendPrompt(inputText.trim())
                                inputText = ""
                            }
                        },
                        enabled = connectionState == SshConnectionState.CONNECTED && inputText.isNotBlank()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                ChatMessageItem(message)
            }
        }
    }
}

@Composable
fun ChatMessageItem(message: ChatMessage) {
    val bgColor = when (message.type) {
        MessageType.USER -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        MessageType.SYSTEM -> MaterialTheme.colorScheme.surfaceVariant
        MessageType.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    val textColor = when (message.type) {
        MessageType.USER -> MaterialTheme.colorScheme.primary
        MessageType.SYSTEM -> MaterialTheme.colorScheme.onSurfaceVariant
        MessageType.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }
    val alignment = if (message.type == MessageType.USER) Alignment.End else Alignment.Start

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(bgColor, MaterialTheme.shapes.medium)
                .padding(12.dp)
        ) {
            Text(
                text = message.content,
                color = textColor,
                fontFamily = if (message.type == MessageType.SYSTEM) FontFamily.Monospace else FontFamily.Default,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
