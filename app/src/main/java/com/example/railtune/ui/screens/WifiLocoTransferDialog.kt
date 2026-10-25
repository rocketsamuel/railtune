package com.example.railtune.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.railtune.model.Loco
import com.example.railtune.network.WifiTransferDevice
import kotlinx.coroutines.launch

@Composable
fun WifiLocoTransferDialog(
    devices: List<WifiTransferDevice>,
    locos: List<Loco>,
    onSend: suspend (WifiTransferDevice, List<Loco>) -> Boolean,
    onTransferAccepted: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var isSending by remember { mutableStateOf(false) }
    var sendingTo by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isSending) onDismiss() },
        title = { Text("Gerät für Export auswählen") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("RailTune sucht nach geöffneten Geräten im selben WLAN.")
                if (devices.isEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator()
                        Text("Suche nach Geräten…")
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(devices, key = { it.serviceName }) { device ->
                            OutlinedButton(
                                enabled = !isSending,
                                onClick = {
                                    sendingTo = device.displayName
                                    statusMessage = "Anfrage gesendet. Warte auf Bestätigung von ${device.displayName}…"
                                    scope.launch {
                                        isSending = true
                                        runCatching { onSend(device, locos) }
                                            .onSuccess { accepted ->
                                                if (accepted) onTransferAccepted(device.displayName)
                                                else statusMessage = "Der Empfänger hat die Übertragung abgelehnt oder nicht bestätigt."
                                            }
                                            .onFailure { error ->
                                                statusMessage = "Senden fehlgeschlagen: ${error.localizedMessage ?: "Netzwerkfehler"}"
                                            }
                                        isSending = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                androidx.compose.material3.Icon(Icons.Default.Wifi, contentDescription = null)
                                Text(device.displayName, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
                if (isSending) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator()
                        Text("Warte auf $sendingTo…", style = MaterialTheme.typography.bodySmall)
                    }
                } else if (statusMessage.isNotBlank()) {
                    Text(statusMessage, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSending) { Text("Schließen") }
        }
    )
}
