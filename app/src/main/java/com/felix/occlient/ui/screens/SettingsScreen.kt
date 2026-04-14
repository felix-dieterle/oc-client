package com.felix.occlient.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.felix.occlient.viewmodel.ConnectionTestResult
import com.felix.occlient.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    val saved by viewModel.saved.collectAsState()
    val testResult by viewModel.connectionTestResult.collectAsState()
    var host by remember(settings.host) { mutableStateOf(settings.host) }
    var port by remember(settings.port) { mutableStateOf(settings.port.toString()) }
    var username by remember(settings.username) { mutableStateOf(settings.username) }
    var password by remember(settings.password) { mutableStateOf(settings.password) }
    var privateKey by remember(settings.privateKey) { mutableStateOf(settings.privateKey) }
    var protocolDebugLogs by remember(settings.protocolDebugLogs) { mutableStateOf(settings.protocolDebugLogs) }
    var showKey by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val keyFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            coroutineScope.launch {
                try {
                    val content = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            stream.bufferedReader().readText()
                        }.orEmpty()
                    }
                    privateKey = content
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("Failed to read key file")
                }
            }
        }
    }

    LaunchedEffect(saved) {
        if (saved) {
            snackbarHostState.showSnackbar("Settings saved")
            viewModel.clearSaved()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SSH Settings", fontFamily = FontFamily.Monospace) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("SSH Connection", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("SSH Host") },
                placeholder = { Text("192.168.1.100 or hostname") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )

            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter { c -> c.isDigit() } },
                label = { Text("Port") },
                placeholder = { Text("22") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                placeholder = { Text("user") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider()
            Text("Authentication", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                placeholder = { Text("SSH password or key passphrase") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(if (showPassword) "Hide" else "Show")
                    }
                }
            )

            OutlinedTextField(
                value = privateKey,
                onValueChange = { privateKey = it },
                label = { Text("Private Key (PEM)") },
                placeholder = { Text("-----BEGIN RSA PRIVATE KEY-----\n...") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp),
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                maxLines = 10
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showKey = !showKey }) {
                    Text(if (showKey) "Hide Key" else "Show Key")
                }
                OutlinedButton(onClick = { keyFileLauncher.launch(arrayOf("text/plain", "application/x-pem-file", "application/octet-stream")) }) {
                    Icon(Icons.Default.FolderOpen, contentDescription = "Select SSH key file", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Select File")
                }
            }

            HorizontalDivider()
            Text("Debug", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Protocol trace logs")
                Switch(
                    checked = protocolDebugLogs,
                    onCheckedChange = { protocolDebugLogs = it }
                )
            }

            HorizontalDivider()

            // Test Connection button
            val isTesting = testResult == ConnectionTestResult.Testing
            OutlinedButton(
                onClick = {
                    viewModel.testConnection(
                        host = host.trim(),
                        port = port.toIntOrNull() ?: 22,
                        username = username.trim(),
                        password = password,
                        privateKey = privateKey.trim()
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isTesting && host.isNotBlank()
            ) {
                if (isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Testing…")
                } else {
                    Text("Test SSH Connection")
                }
            }

            Button(
                onClick = {
                    viewModel.saveSettings(
                        host = host.trim(),
                        port = port.toIntOrNull() ?: 22,
                        username = username.trim(),
                        password = password,
                        privateKey = privateKey.trim(),
                        protocolDebugLogs = protocolDebugLogs
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Settings")
            }

            // Inline connection test feedback card
            when (val r = testResult) {
                is ConnectionTestResult.Success -> ConnectionResultCard(
                    message = r.message,
                    isSuccess = true
                )
                is ConnectionTestResult.Failure -> ConnectionResultCard(
                    message = r.message,
                    isSuccess = false
                )
                else -> Unit
            }
        }
    }
}

@Composable
private fun ConnectionResultCard(message: String, isSuccess: Boolean) {
    val containerColor = if (isSuccess)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.errorContainer
    val contentColor = if (isSuccess)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onErrorContainer

    Surface(
        color = containerColor,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (isSuccess) "✓" else "✗",
                color = contentColor,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = message,
                color = contentColor,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

