package com.openclaw.assistant.ui.backend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openclaw.assistant.R
import com.openclaw.assistant.backend.AgentBackendConfig
import com.openclaw.assistant.backend.AgentClientFactory
import com.openclaw.assistant.backend.BackendManager
import com.openclaw.assistant.backend.BackendType
import com.openclaw.assistant.backend.ConnectionTestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PrimaryBackendCard(
    openClawConnected: Boolean = false,
    openClawStatusText: String = "",
    onOpenClawTest: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val manager = remember { BackendManager.getInstance(context) }
    val backends by manager.backends.collectAsState()
    val openClaw = remember(backends) { backends.preferredOpenClawBackend() }
    val hermes = remember(backends) { backends.preferredHermesBackend() }
    val zetro = remember(backends) { backends.preferredZetroBackend() }
    var hermesTest by remember { mutableStateOf<ConnectionTestResult?>(null) }
    var zetroTest by remember { mutableStateOf<ConnectionTestResult?>(null) }
    val scope = rememberCoroutineScope()
    val hasConfiguredBackend = openClaw != null || hermes != null || zetro != null
    val connectionTestingText = stringResource(R.string.av_connection_testing)

    LaunchedEffect(hermes?.id, hermes?.updatedAt) {
        val config = hermes
        if (config == null) {
            hermesTest = null
        } else {
            hermesTest = ConnectionTestResult(false, connectionTestingText)
            hermesTest = withContext(Dispatchers.IO) { AgentClientFactory.create(config).testConnection() }
        }
    }

    LaunchedEffect(zetro?.id, zetro?.updatedAt) {
        val config = zetro
        if (config == null) {
            zetroTest = null
        } else {
            zetroTest = ConnectionTestResult(false, connectionTestingText)
            zetroTest = withContext(Dispatchers.IO) { AgentClientFactory.create(config).testConnection() }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.av_home_connections_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BackendProductTile(
                    name = "OpenClaw",
                    configured = openClaw != null,
                    connected = openClawConnected,
                    statusText = when {
                        openClawConnected -> stringResource(R.string.av_home_connected)
                        openClaw != null -> stringResource(R.string.av_home_disconnected)
                        else -> stringResource(R.string.av_home_not_configured)
                    },
                    modifier = Modifier.weight(1f),
                )
                BackendProductTile(
                    name = "Hermes Agent",
                    configured = hermes != null,
                    connected = hermesTest?.ok == true,
                    testing = hermes != null && hermesTest?.message == connectionTestingText,
                    statusText = when {
                        hermesTest?.ok == true -> stringResource(R.string.av_home_connected)
                        hermesTest != null -> hermesTest?.message ?: stringResource(R.string.av_home_disconnected)
                        hermes != null -> connectionTestingText
                        else -> stringResource(R.string.av_home_not_configured)
                    },
                    modifier = Modifier.weight(1f),
                )
                BackendProductTile(
                    name = "ZETRO",
                    configured = zetro != null,
                    connected = zetroTest?.ok == true,
                    testing = zetro != null && zetroTest?.message == connectionTestingText,
                    statusText = when {
                        zetroTest?.ok == true -> stringResource(R.string.av_home_connected)
                        zetroTest != null -> zetroTest?.message ?: stringResource(R.string.av_home_disconnected)
                        zetro != null -> connectionTestingText
                        else -> stringResource(R.string.av_home_not_configured)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            if (hasConfiguredBackend) {
                Button(
                    onClick = {
                        if (openClaw != null) {
                            onOpenClawTest?.invoke()
                        }
                        hermes?.let { config ->
                            scope.launch {
                                hermesTest = ConnectionTestResult(false, connectionTestingText)
                                hermesTest = withContext(Dispatchers.IO) { AgentClientFactory.create(config).testConnection() }
                            }
                        }
                        zetro?.let { config ->
                            scope.launch {
                                zetroTest = ConnectionTestResult(false, connectionTestingText)
                                zetroTest = withContext(Dispatchers.IO) { AgentClientFactory.create(config).testConnection() }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.av_home_test_connection))
                }
            }
        }
    }
}

@Composable
private fun BackendProductTile(
    name: String,
    configured: Boolean,
    connected: Boolean,
    testing: Boolean = false,
    statusText: String,
    modifier: Modifier = Modifier,
) {
    val statusColor = when {
        connected -> Color(0xFF34C759)
        testing -> Color(0xFFFFA000)
        else -> Color(0xFFE53935)
    }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Card(shape = CircleShape, colors = CardDefaults.cardColors(containerColor = statusColor), modifier = Modifier.size(10.dp)) {}
                Spacer(modifier = Modifier.size(8.dp))
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = statusColor,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun List<AgentBackendConfig>.preferredOpenClawBackend(): AgentBackendConfig? {
    val enabled = filter { it.enabled && (it.type == BackendType.OPENCLAW_GATEWAY || it.type == BackendType.OPENCLAW_HTTP) }
    return enabled.firstOrNull { it.isPrimary }
        ?: enabled.firstOrNull { it.type == BackendType.OPENCLAW_GATEWAY }
        ?: enabled.firstOrNull()
}

private fun List<AgentBackendConfig>.preferredHermesBackend(): AgentBackendConfig? {
    val enabled = filter { it.enabled && it.type == BackendType.HERMES_API_SERVER }
    return enabled.firstOrNull { it.isPrimary } ?: enabled.firstOrNull()
}

private fun List<AgentBackendConfig>.preferredZetroBackend(): AgentBackendConfig? {
    val enabled = filter { it.enabled && it.type == BackendType.ZETRO_GATEWAY }
    return enabled.firstOrNull { it.isPrimary } ?: enabled.firstOrNull()
}
