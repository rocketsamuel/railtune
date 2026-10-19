package com.example.railtune.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.railtune.model.BleDevice
import com.example.railtune.model.ConnectionState
import com.example.railtune.model.DiscoveredGattCharacteristic
import com.example.railtune.model.DiscoveredGattService
import com.example.railtune.model.LiveDataPacket
import com.example.railtune.viewmodel.BleViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: BleViewModel) {
    val context = LocalContext.current

    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val liveDataPacket by viewModel.liveDataStream.collectAsState()
    val dataHistory by viewModel.dataHistory.collectAsState()
    val discoveredGattServices by viewModel.discoveredGattServices.collectAsState()
    val activeCharacteristic by viewModel.activeCharacteristic.collectAsState()

    val lastConnectedAddress by viewModel.lastConnectedAddress.collectAsState()
    val lastConnectedName by viewModel.lastConnectedName.collectAsState()
    val isAutoConnectEnabled by viewModel.isAutoConnectEnabled.collectAsState()

    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }

    var hasPermissions by remember {
        mutableStateOf(
            requiredPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasPermissions = results.values.all { it }
        if (hasPermissions) {
            viewModel.startScan()
        }
    }

    // Auto-start scan on launch when permissions are available
    LaunchedEffect(hasPermissions) {
        if (hasPermissions && connectionState is ConnectionState.Disconnected && !isScanning) {
            viewModel.startScan()
        }
    }

    var showEsp32CodeDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Railtune",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    IconButton(onClick = { showEsp32CodeDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = "ESP32 Code Beispiel"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!hasPermissions) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Bluetooth Berechtigungen erforderlich",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Um nach deinem ESP32 zu suchen und Daten zu empfangen, werden Bluetooth- bzw. Standortberechtigungen benötigt.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(
                            onClick = { permissionLauncher.launch(requiredPermissions) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Berechtigungen erteilen")
                        }
                    }
                }
            }

            ConnectionStatusCard(
                connectionState = connectionState,
                isScanning = isScanning,
                lastConnectedName = lastConnectedName,
                isAutoConnectEnabled = isAutoConnectEnabled,
                onStartScan = {
                    if (hasPermissions) viewModel.startScan()
                    else permissionLauncher.launch(requiredPermissions)
                },
                onStopScan = { viewModel.stopScan() },
                onDisconnect = { viewModel.disconnect() }
            )

            if (connectionState !is ConnectionState.Connected && !lastConnectedAddress.isNullOrEmpty()) {
                AutoConnectBanner(
                    lastConnectedName = lastConnectedName ?: "ESP32",
                    lastConnectedAddress = lastConnectedAddress ?: "",
                    isAutoConnectEnabled = isAutoConnectEnabled,
                    onToggleAutoConnect = { viewModel.setAutoConnectEnabled(it) },
                    onForgetDevice = { viewModel.forgetLastDevice() }
                )
            }

            when (val state = connectionState) {
                is ConnectionState.Connected -> {
                    LiveDashboardView(
                        deviceName = state.deviceName,
                        deviceAddress = state.address,
                        latestPacket = liveDataPacket,
                        history = dataHistory,
                        activeCharacteristic = activeCharacteristic,
                        discoveredGattServices = discoveredGattServices,
                        onSelectCharacteristic = { serviceUuid, charUuid ->
                            viewModel.selectCharacteristic(serviceUuid, charUuid)
                        },
                        onSetCustomUuids = { serviceUuidStr, charUuidStr ->
                            viewModel.setCustomUuids(serviceUuidStr, charUuidStr)
                        },
                        onClearHistory = { viewModel.clearDataHistory() }
                    )
                }
                else -> {
                    DeviceScanListView(
                        devices = discoveredDevices,
                        isScanning = isScanning,
                        lastConnectedAddress = lastConnectedAddress,
                        onConnect = { viewModel.connectDevice(it) }
                    )
                }
            }
        }
    }

    if (showEsp32CodeDialog) {
        Esp32CodeExampleDialog(onDismiss = { showEsp32CodeDialog = false })
    }
}

@Composable
fun ConnectionStatusCard(
    connectionState: ConnectionState,
    isScanning: Boolean,
    lastConnectedName: String?,
    isAutoConnectEnabled: Boolean,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusColor = when (connectionState) {
                        is ConnectionState.Connected -> Color(0xFF00E676)
                        is ConnectionState.Connecting -> Color(0xFFFFB300)
                        is ConnectionState.Scanning -> Color(0xFF03A9F4)
                        is ConnectionState.Error -> Color(0xFFFF1744)
                        is ConnectionState.Disconnected -> Color.Gray
                    }
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (connectionState) {
                            is ConnectionState.Connected -> "Verbunden: ${connectionState.deviceName}"
                            is ConnectionState.Connecting -> "Verbinde mit ${connectionState.deviceName}..."
                            is ConnectionState.Scanning -> {
                                if (isAutoConnectEnabled && !lastConnectedName.isNullOrEmpty()) {
                                    "Suche nach $lastConnectedName..."
                                } else {
                                    "Suche nach BLE-Geräten..."
                                }
                            }
                            is ConnectionState.Error -> "Fehler: ${connectionState.message}"
                            is ConnectionState.Disconnected -> "Getrennt"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            when {
                connectionState is ConnectionState.Connected || connectionState is ConnectionState.Connecting -> {
                    OutlinedButton(onClick = onDisconnect) {
                        Icon(Icons.Default.BluetoothDisabled, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Trennen")
                    }
                }
                isScanning -> {
                    Button(
                        onClick = onStopScan,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Stopp")
                    }
                }
                else -> {
                    Button(onClick = onStartScan) {
                        Icon(Icons.Default.Search, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Messwagen suchen")
                    }
                }
            }
        }
    }
}

@Composable
fun AutoConnectBanner(
    lastConnectedName: String,
    lastConnectedAddress: String,
    isAutoConnectEnabled: Boolean,
    onToggleAutoConnect: (Boolean) -> Unit,
    onForgetDevice: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Autorenew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Auto-Connect: $lastConnectedName",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Text(
                    text = lastConnectedAddress,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = isAutoConnectEnabled,
                    onCheckedChange = onToggleAutoConnect,
                    modifier = Modifier.padding(end = 8.dp)
                )
                IconButton(
                    onClick = onForgetDevice,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Gespeichertes Gerät vergessen",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun DeviceScanListView(
    devices: List<BleDevice>,
    isScanning: Boolean,
    lastConnectedAddress: String?,
    onConnect: (BleDevice) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Gefundene BLE Geräte (${devices.size})",
                style = MaterialTheme.typography.titleMedium
            )
            if (isScanning) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }

        if (devices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isScanning) "Suche läuft... ESP32 einschalten" else "Klicke auf 'ESP32 Suchen', um den Scan zu starten.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxHeight()
            ) {
                items(devices, key = { it.address }) { device ->
                    val isSavedDevice = !lastConnectedAddress.isNullOrEmpty() &&
                            device.address.equals(lastConnectedAddress, ignoreCase = true)
                    DeviceItemCard(
                        device = device,
                        isSavedDevice = isSavedDevice,
                        onConnect = { onConnect(device) }
                    )
                }
            }
        }
    }
}

@Composable
fun DeviceItemCard(
    device: BleDevice,
    isSavedDevice: Boolean,
    onConnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSavedDevice) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (isSavedDevice) {
                        Spacer(modifier = Modifier.width(8.dp))
                        SuggestionChip(
                            onClick = {},
                            label = { Text("Auto-Connect", fontSize = 10.sp) },
                            modifier = Modifier.height(24.dp)
                        )
                    }
                }
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Signal: ${device.rssi} dBm",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (device.rssi > -70) Color(0xFF00E676) else Color.Gray
                )
            }
            Button(onClick = onConnect) {
                Text("Verbinden")
            }
        }
    }
}

@Composable
fun LiveDashboardView(
    deviceName: String,
    deviceAddress: String,
    latestPacket: LiveDataPacket?,
    history: List<LiveDataPacket>,
    activeCharacteristic: DiscoveredGattCharacteristic?,
    discoveredGattServices: List<DiscoveredGattService>,
    onSelectCharacteristic: (UUID, UUID) -> Unit,
    onSetCustomUuids: (String, String) -> Unit,
    onClearHistory: () -> Unit
) {
    var showGattDialog by remember { mutableStateOf(false) }
    var showHexView by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Live Data Display
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "LIVEDATEN ($deviceName / $deviceAddress)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        letterSpacing = 1.5.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showGattDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Dienste / UUIDs",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        FilterChip(
                            selected = showHexView,
                            onClick = { showHexView = !showHexView },
                            label = { Text(if (showHexView) "HEX" else "Text") }
                        )
                    }
                }

                val displayText = if (latestPacket == null) {
                    "--"
                } else if (showHexView) {
                    latestPacket.hexValue
                } else if (latestPacket.textValue.toFloatOrNull() != null) {
                    "${latestPacket.textValue} km/h"
                } else {
                    latestPacket.textValue
                }

                Text(
                    text = displayText,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontFamily = FontFamily.Monospace
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    latestPacket?.let {
                        val sdf = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
                        val formattedTime = sdf.format(Date(it.timestampMs))
                        Text(
                            text = "Letztes Paket: $formattedTime (${it.rawBytes.size} Bytes)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    } ?: Spacer(modifier = Modifier.width(1.dp))

                }
            }
        }

        // History
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Datenverlauf (Stream)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onClearHistory) {
                Icon(Icons.Default.Delete, contentDescription = "Verlauf leeren")
            }
        }

        if (history.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Warte auf Livedaten vom ESP32...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            val sdf = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(history) { packet ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val packetDisplayText = if (showHexView) {
                                packet.hexValue
                            } else if (packet.textValue.toFloatOrNull() != null) {
                                "${packet.textValue} km/h"
                            } else {
                                packet.textValue
                            }
                            Text(
                                text = packetDisplayText,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = sdf.format(Date(packet.timestampMs)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (showGattDialog) {
        GattInspectorDialog(
            discoveredServices = discoveredGattServices,
            activeCharacteristic = activeCharacteristic,
            onSelectCharacteristic = { serviceUuid, charUuid ->
                onSelectCharacteristic(serviceUuid, charUuid)
                showGattDialog = false
            },
            onSetCustomUuids = { serviceUuidStr, charUuidStr ->
                onSetCustomUuids(serviceUuidStr, charUuidStr)
                showGattDialog = false
            },
            onDismiss = { showGattDialog = false }
        )
    }
}

@Composable
fun GattInspectorDialog(
    discoveredServices: List<DiscoveredGattService>,
    activeCharacteristic: DiscoveredGattCharacteristic?,
    onSelectCharacteristic: (UUID, UUID) -> Unit,
    onSetCustomUuids: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var customServiceUuid by remember { mutableStateOf("") }
    var customCharUuid by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("GATT Dienste & Charakteristiken") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 450.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Gefundene GATT-Dienste auf deinem ESP32 (${discoveredServices.size}):",
                    style = MaterialTheme.typography.bodySmall
                )

                if (discoveredServices.isEmpty()) {
                    Text(
                        text = "Keine Dienste entdeckt.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(discoveredServices) { service ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "Service: ${service.uuid}",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.primary
                                    )

                                    service.characteristics.forEach { char ->
                                        val isSelected = activeCharacteristic?.uuid == char.uuid
                                        val props = buildList {
                                            if (char.isNotifiable) add("NOTIFY")
                                            if (char.isIndicatable) add("INDICATE")
                                            if (char.isReadable) add("READ")
                                            if (char.isWritable) add("WRITE")
                                        }.joinToString(", ")

                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    onSelectCharacteristic(service.uuid, char.uuid)
                                                },
                                            shape = RoundedCornerShape(6.dp),
                                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                                        ) {
                                            Column(modifier = Modifier.padding(6.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = char.uuid.toString(),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                    if (isSelected) {
                                                        Text("Aktiv", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                                    }
                                                }
                                                Text(
                                                    text = "Eigenschaften: $props",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()

                Text("Oder manuelle UUIDs eingeben:", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = customServiceUuid,
                    onValueChange = { customServiceUuid = it },
                    label = { Text("Service UUID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = customCharUuid,
                    onValueChange = { customCharUuid = it },
                    label = { Text("Characteristic UUID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            if (customServiceUuid.isNotBlank() && customCharUuid.isNotBlank()) {
                Button(
                    onClick = { onSetCustomUuids(customServiceUuid, customCharUuid) }
                ) {
                    Text("Anwenden")
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Schließen")
                }
            }
        },
        dismissButton = {
            if (customServiceUuid.isNotBlank() && customCharUuid.isNotBlank()) {
                TextButton(onClick = onDismiss) {
                    Text("Abbrechen")
                }
            }
        }
    )
}

@Composable
fun Esp32CodeExampleDialog(onDismiss: () -> Unit) {
    val exampleCode = """
        // ESP32 Arduino BLE Server Beispiel für Livedaten
        #include <BLEDevice.h>
        #include <BLEServer.h>
        #include <BLEUtils.h>
        #include <BLE2902.h>

        #define SERVICE_UUID        "4fa1c001-0401-4051-b02c-35804611243c"
        #define CHARACTERISTIC_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a8"

        BLECharacteristic *pCharacteristic;
        bool deviceConnected = false;
        int counter = 0;

        class MyServerCallbacks: public BLEServerCallbacks {
            void onConnect(BLEServer* pServer) { deviceConnected = true; }
            void onDisconnect(BLEServer* pServer) {
                deviceConnected = false;
                BLEDevice::startAdvertising();
            }
        };

        void setup() {
            Serial.begin(115200);
            BLEDevice::init("ESP32_LiveSensor");

            BLEServer *pServer = BLEDevice::createServer();
            pServer->setCallbacks(new MyServerCallbacks());

            BLEService *pService = pServer->createService(SERVICE_UUID);
            pCharacteristic = pService->createCharacteristic(
                                CHARACTERISTIC_UUID,
                                BLECharacteristic::PROPERTY_READ |
                                BLECharacteristic::PROPERTY_NOTIFY
                              );

            pCharacteristic->addDescriptor(new BLE2902());
            pService->start();

            BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
            pAdvertising->addServiceUUID(SERVICE_UUID);
            BLEDevice::startAdvertising();
            Serial.println("ESP32 BLE Bereit!");
        }

        void loop() {
            if (deviceConnected) {
                String payload = "Wert: " + String(counter++);
                pCharacteristic->setValue(payload.c_str());
                pCharacteristic->notify(); // Livedaten senden!
                delay(1000); // Jede Sekunde Livedaten
            }
        }
    """.trimIndent()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ESP32 Arduino BLE Beispiel") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Flash diesen Beispielcode auf deinen ESP32, damit er automatisch erkannt wird:")
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = exampleCode,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Schließen")
            }
        }
    )
}
