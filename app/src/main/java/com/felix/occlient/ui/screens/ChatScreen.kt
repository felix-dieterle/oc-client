package com.felix.occlient.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.*
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
    val isProcessing by viewModel.isProcessing.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Scroll to end whenever a new message arrives or the processing indicator appears/disappears.
    val totalItems = messages.size + if (isProcessing) 1 else 0
    LaunchedEffect(totalItems) {
        if (totalItems > 0) listState.animateScrollToItem(totalItems - 1)
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
                        Icon(Icons.AutoMirrored.Filled.Assignment, contentDescription = "Logs")
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
                        enabled = connectionState == SshConnectionState.CONNECTED && !isProcessing
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                viewModel.sendPrompt(inputText.trim())
                                inputText = ""
                            }
                        },
                        enabled = connectionState == SshConnectionState.CONNECTED && inputText.isNotBlank() && !isProcessing
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
            if (isProcessing) {
                item(key = "typing_indicator") {
                    TypingIndicator()
                }
            }
        }
    }
}

@Composable
fun ChatMessageItem(message: ChatMessage) {
    when (message.type) {
        MessageType.SYSTEM -> SystemMessageLabel(message.content)
        MessageType.USER, MessageType.ASSISTANT, MessageType.ERROR -> ChatBubble(message)
    }
}

/**
 * Small centred pill-style label for connection-status / informational messages
 * (e.g. "Connecting…", "Connected! Starting opencode…", "Disconnected").
 */
@Composable
private fun SystemMessageLabel(content: String) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = content,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    MaterialTheme.shapes.small
                )
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/**
 * Animated three-dot indicator shown while opencode is computing a response.
 * Styled like an ASSISTANT bubble (left-aligned, surfaceVariant background) so it visually
 * signals that a reply is on the way.
 */
@Composable
private fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    val dot1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(500, easing = LinearEasing), RepeatMode.Reverse,
            initialStartOffset = StartOffset(0)
        ),
        label = "dot1"
    )
    val dot2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(500, easing = LinearEasing), RepeatMode.Reverse,
            initialStartOffset = StartOffset(150)
        ),
        label = "dot2"
    )
    val dot3Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(500, easing = LinearEasing), RepeatMode.Reverse,
            initialStartOffset = StartOffset(300)
        ),
        label = "dot3"
    )

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (alpha in listOf(dot1Alpha, dot2Alpha, dot3Alpha)) {
                    Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha), CircleShape))
                }
            }
        }
    }
}

/**
 * Bubble-style message for USER prompts, ASSISTANT (opencode) responses, and ERROR messages.
 */
@Composable
private fun ChatBubble(message: ChatMessage) {
    val isUser = message.type == MessageType.USER
    val isError = message.type == MessageType.ERROR
    // Single when expression that yields both colors together, eliminating duplicate maintenance.
    val (bgColor, textColor) = when {
        isUser -> TerminalGreen to MaterialTheme.colorScheme.surface  // dark text on bright green
        isError -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant  // ASSISTANT
    }
    val alignment = if (isUser) Alignment.End else Alignment.Start

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
                fontFamily = if (message.type == MessageType.ASSISTANT) FontFamily.Monospace else FontFamily.Default,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
