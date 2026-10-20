package com.example.railtune.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.railtune.model.BleDevice
import com.example.railtune.model.ConnectionState
import com.example.railtune.model.CvResult
import com.example.railtune.model.DiscoveredGattCharacteristic
import com.example.railtune.model.DiscoveredGattService
import com.example.railtune.model.LiveDataPacket
import com.example.railtune.model.Loco
import com.example.railtune.viewmodel.BleViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

fun parseSpeedFromText(text: String?): Float? {
    if (text.isNullOrBlank()) return null
    val cleaned = text.replace(',', '.')
    cleaned.toFloatOrNull()?.let { return it }
    val match = Regex("""\d+(\.\d+)?""").find(cleaned)
    return match?.value?.toFloatOrNull()
}

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

    // z21 & CV States
    val z21Ip by viewModel.z21Ip.collectAsState()
    val z21Status by viewModel.z21Status.collectAsState()
    val cvResult by viewModel.cvResult.collectAsState()
    val isCvBusy by viewModel.isCvBusy.collectAsState()
    val savedLocos by viewModel.savedLocos.collectAsState()

    var selectedTabIndex by remember { mutableIntStateOf(0) }

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

    val topTitleText = when (val state = connectionState) {
        is ConnectionState.Connected -> "Railtune • ${state.deviceName}"
        is ConnectionState.Connecting -> "Railtune • Verbinde..."
        else -> "Railtune"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (connectionState is ConnectionState.Connected) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF00E676))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = topTitleText,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
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
        ) {
            // Tab Row Navigation
            PrimaryTabRow(selectedTabIndex = selectedTabIndex) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    text = { Text("Messen") },
                    icon = { Icon(Icons.Default.Speed, contentDescription = null) }
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    text = { Text("Programmieren") },
                    icon = { Icon(Icons.Default.Code, contentDescription = null) }
                )
                Tab(
                    selected = selectedTabIndex == 2,
                    onClick = { selectedTabIndex = 2 },
                    text = { Text("Fahren") },
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) }
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
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

                when (selectedTabIndex) {
                    0 -> {
                        // Reiter 1: Geschwindigkeit messen
                        SpeedMeasurementScreen(
                            connectionState = connectionState,
                            isScanning = isScanning,
                            lastConnectedName = lastConnectedName,
                            lastConnectedAddress = lastConnectedAddress,
                            isAutoConnectEnabled = isAutoConnectEnabled,
                            discoveredDevices = discoveredDevices,
                            liveDataPacket = liveDataPacket,
                            dataHistory = dataHistory,
                            activeCharacteristic = activeCharacteristic,
                            discoveredGattServices = discoveredGattServices,
                            onStartScan = {
                                if (hasPermissions) viewModel.startScan()
                                else permissionLauncher.launch(requiredPermissions)
                            },
                            onStopScan = { viewModel.stopScan() },
                            onDisconnect = { viewModel.disconnect() },
                            onToggleAutoConnect = { viewModel.setAutoConnectEnabled(it) },
                            onForgetDevice = { viewModel.forgetLastDevice() },
                            onConnectDevice = { viewModel.connectDevice(it) },
                            onSelectCharacteristic = { sUuid, cUuid -> viewModel.selectCharacteristic(sUuid, cUuid) },
                            onSetCustomUuids = { sUuid, cUuid -> viewModel.setCustomUuids(sUuid, cUuid) },
                            onClearHistory = { viewModel.clearDataHistory() }
                        )
                    }
                    1 -> {
                        // Reiter 2: Programmieren (CV Lesen / Schreiben & z21)
                        ProgrammingScreen(
                            z21Ip = z21Ip,
                            z21Status = z21Status,
                            cvResult = cvResult,
                            isCvBusy = isCvBusy,
                            onSetZ21Ip = { viewModel.setZ21Ip(it) },
                            onReadCv = { viewModel.readCv(it) },
                            onWriteCv = { cv, valNum -> viewModel.writeCv(cv, valNum) },
                            onShowCodeDialog = { showEsp32CodeDialog = true }
                        )
                    }
                    2 -> {
                        // Reiter 3: Fahren (Lokliste & Steuerung)
                        DrivingScreen(
                            savedLocos = savedLocos,
                            latestPacket = liveDataPacket,
                            onAddLoco = { name, addr, maxSpd, cat -> viewModel.addLoco(name, addr, maxSpd, cat) },
                            onRemoveLoco = { viewModel.removeLoco(it) },
                            onSetLocoSpeed = { addr, spd, fwd -> viewModel.setLocoSpeed(addr, spd, fwd) },
                            onSetLocoFunction = { addr, func, state -> viewModel.setLocoFunction(addr, func, state) },
                            onGetLocoInfo = { addr -> viewModel.getLocoInfo(addr) }
                        )
                    }
                }
            }
        }
    }

    if (showEsp32CodeDialog) {
        Esp32CodeExampleDialog(onDismiss = { showEsp32CodeDialog = false })
    }
}

@Composable
fun CircularSpeedometerGauge(
    speed: Float,
    maxSpeed: Float = 220f,
    unit: String = "km/h",
    modifier: Modifier = Modifier.size(220.dp)
) {
    val animatedSpeed by animateFloatAsState(
        targetValue = speed.coerceIn(0f, maxSpeed),
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "speedAnimation"
    )

    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val primaryContainerColor = MaterialTheme.colorScheme.onPrimaryContainer

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val center = Offset(width / 2f, height / 2f)
            val radius = (minOf(width, height) / 2f) * 0.82f
            val strokeWidth = 14.dp.toPx()

            val startAngle = 140f
            val sweepAngle = 260f

            // 1. Background Arc
            drawArc(
                color = trackColor,
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // 2. Active Speed Arc
            val currentSweep = (animatedSpeed / maxSpeed) * sweepAngle
            val arcGradient = Brush.sweepGradient(
                colors = listOf(
                    Color(0xFF00E676), // Green
                    Color(0xFFFFB300), // Amber
                    Color(0xFFFF1744)  // Red
                ),
                center = center
            )

            drawArc(
                brush = arcGradient,
                startAngle = startAngle,
                sweepAngle = currentSweep.coerceAtLeast(1f),
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // 3. Tick Marks
            val numTicks = 11
            for (i in 0..numTicks) {
                val fraction = i.toFloat() / numTicks
                val angleDeg = startAngle + fraction * sweepAngle
                val angleRad = Math.toRadians(angleDeg.toDouble())

                val innerR = radius - strokeWidth / 2f - 4.dp.toPx()
                val outerR = radius - strokeWidth / 2f - 12.dp.toPx()

                val startPt = Offset(
                    x = center.x + innerR * cos(angleRad).toFloat(),
                    y = center.y + innerR * sin(angleRad).toFloat()
                )
                val endPt = Offset(
                    x = center.x + outerR * cos(angleRad).toFloat(),
                    y = center.y + outerR * sin(angleRad).toFloat()
                )

                drawLine(
                    color = onSurfaceColor.copy(alpha = 0.5f),
                    start = startPt,
                    end = endPt,
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // 4. Shortened Needle (Gekürzter Zeiger - verdeckt die Zahl in der Mitte nicht)
            val needleAngleDeg = startAngle + (animatedSpeed / maxSpeed) * sweepAngle
            val needleAngleRad = Math.toRadians(needleAngleDeg.toDouble())

            val innerNeedleR = radius * 0.48f // Nach innen gekürzt, verdeckt die Zahl nicht!
            val outerNeedleR = radius * 0.74f

            val needleStart = Offset(
                x = center.x + innerNeedleR * cos(needleAngleRad).toFloat(),
                y = center.y + innerNeedleR * sin(needleAngleRad).toFloat()
            )

            val needleEnd = Offset(
                x = center.x + outerNeedleR * cos(needleAngleRad).toFloat(),
                y = center.y + outerNeedleR * sin(needleAngleRad).toFloat()
            )

            drawLine(
                color = Color(0xFFFF1744),
                start = needleStart,
                end = needleEnd,
                strokeWidth = 5.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        // Center Digital Display
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 12.dp)
        ) {
            val speedInt = animatedSpeed.toInt()
            Text(
                text = "$speedInt",
                fontSize = 38.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = primaryContainerColor
            )
            Text(
                text = unit,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = primaryContainerColor.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
fun SpeedMeasurementScreen(
    connectionState: ConnectionState,
    isScanning: Boolean,
    lastConnectedName: String?,
    lastConnectedAddress: String?,
    isAutoConnectEnabled: Boolean,
    discoveredDevices: List<BleDevice>,
    liveDataPacket: LiveDataPacket?,
    dataHistory: List<LiveDataPacket>,
    activeCharacteristic: DiscoveredGattCharacteristic?,
    discoveredGattServices: List<DiscoveredGattService>,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onDisconnect: () -> Unit,
    onToggleAutoConnect: (Boolean) -> Unit,
    onForgetDevice: () -> Unit,
    onConnectDevice: (BleDevice) -> Unit,
    onSelectCharacteristic: (UUID, UUID) -> Unit,
    onSetCustomUuids: (String, String) -> Unit,
    onClearHistory: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ConnectionStatusCard(
            connectionState = connectionState,
            isScanning = isScanning,
            lastConnectedName = lastConnectedName,
            isAutoConnectEnabled = isAutoConnectEnabled,
            onStartScan = onStartScan,
            onStopScan = onStopScan,
            onDisconnect = onDisconnect
        )

        if (connectionState !is ConnectionState.Connected && !lastConnectedAddress.isNullOrEmpty()) {
            AutoConnectBanner(
                lastConnectedName = lastConnectedName ?: "ESP32",
                isAutoConnectEnabled = isAutoConnectEnabled,
                onToggleAutoConnect = onToggleAutoConnect,
                onForgetDevice = onForgetDevice
            )
        }

        when (val state = connectionState) {
            is ConnectionState.Connected -> {
                LiveDashboardView(
                    deviceName = state.deviceName,
                    latestPacket = liveDataPacket,
                    history = dataHistory,
                    activeCharacteristic = activeCharacteristic,
                    discoveredGattServices = discoveredGattServices,
                    onSelectCharacteristic = onSelectCharacteristic,
                    onSetCustomUuids = onSetCustomUuids,
                    onClearHistory = onClearHistory
                )
            }
            else -> {
                DeviceScanListView(
                    devices = discoveredDevices,
                    isScanning = isScanning,
                    lastConnectedAddress = lastConnectedAddress,
                    onConnect = onConnectDevice
                )
            }
        }
    }
}

@Composable
fun ProgrammingScreen(
    z21Ip: String,
    z21Status: String,
    cvResult: CvResult?,
    isCvBusy: Boolean,
    onSetZ21Ip: (String) -> Unit,
    onReadCv: (Int) -> Unit,
    onWriteCv: (Int, Int) -> Unit,
    onShowCodeDialog: () -> Unit
) {
    var cvNumInput by remember { mutableStateOf("1") }
    var cvValueInput by remember { mutableStateOf("3") }
    var ipInput by remember { mutableStateOf(z21Ip) }
    var showIpDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // z21 Status Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "z21 ZENTRALE",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "IP: $z21Ip (Port 21105)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = z21Status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                        )
                    }
                    IconButton(onClick = { showIpDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "z21 IP ändern")
                    }
                }
            }
        }

        // CV Programmieren Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "DCC DECODER CV PROGRAMMIEREN",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = cvNumInput,
                            onValueChange = { cvNumInput = it },
                            label = { Text("CV Nummer (1-1024)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = cvValueInput,
                            onValueChange = { cvValueInput = it },
                            label = { Text("CV Wert (0-255)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Preset Quick CV Buttons
                    Text("Schnellauswahl gängiger CVs:", style = MaterialTheme.typography.labelSmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = cvNumInput == "1",
                            onClick = { cvNumInput = "1" },
                            label = { Text("CV 1 (Adresse)") }
                        )
                        FilterChip(
                            selected = cvNumInput == "3",
                            onClick = { cvNumInput = "3" },
                            label = { Text("CV 3 (Anfahrt)") }
                        )
                        FilterChip(
                            selected = cvNumInput == "4",
                            onClick = { cvNumInput = "4" },
                            label = { Text("CV 4 (Bremse)") }
                        )
                        FilterChip(
                            selected = cvNumInput == "29",
                            onClick = { cvNumInput = "29" },
                            label = { Text("CV 29 (Config)") }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            enabled = !isCvBusy && cvNumInput.toIntOrNull() != null,
                            onClick = {
                                val cvNum = cvNumInput.toIntOrNull() ?: 1
                                onReadCv(cvNum)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isCvBusy) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text("CV Lesen")
                        }

                        Button(
                            enabled = !isCvBusy && cvNumInput.toIntOrNull() != null && cvValueInput.toIntOrNull() != null,
                            onClick = {
                                val cvNum = cvNumInput.toIntOrNull() ?: 1
                                val valNum = cvValueInput.toIntOrNull() ?: 0
                                onWriteCv(cvNum, valNum)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("CV Schreiben")
                        }
                    }

                    // CV Result Display
                    cvResult?.let { res ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (res.isSuccess) Color(0xFF1B5E20).copy(alpha = 0.15f)
                                else MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "ERGEBNIS: CV ${res.cvNumber}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (res.isSuccess) Color(0xFF00E676) else MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = "Wert: ${res.cvValue} (Dezimal) | 0x${res.cvValue.toString(16).uppercase()} (Hex)",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = res.message,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            OutlinedButton(
                onClick = onShowCodeDialog,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Code, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("ESP32 C++ Quellcode anzeigen")
            }
        }
    }

    if (showIpDialog) {
        AlertDialog(
            onDismissRequest = { showIpDialog = false },
            title = { Text("z21 Zentrale IP Adresse") },
            text = {
                OutlinedTextField(
                    value = ipInput,
                    onValueChange = { ipInput = it },
                    label = { Text("IP Adresse") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    onSetZ21Ip(ipInput)
                    showIpDialog = false
                }) {
                    Text("Speichern")
                }
            },
            dismissButton = {
                TextButton(onClick = { showIpDialog = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }
}

@Composable
fun DrivingScreen(
    savedLocos: List<Loco>,
    latestPacket: LiveDataPacket?,
    onAddLoco: (String, Int, Int, String) -> Loco,
    onRemoveLoco: (String) -> Unit,
    onSetLocoSpeed: (Int, Float, Boolean) -> Unit,
    onSetLocoFunction: (Int, Int, Boolean) -> Unit,
    onGetLocoInfo: (Int) -> Unit
) {
    var selectedLocoId by remember { mutableStateOf(savedLocos.firstOrNull()?.id ?: "") }
    var speedSliderValue by remember { mutableFloatStateOf(0f) }
    var isForwardDirection by remember { mutableStateOf(true) }
    var isLightOn by remember { mutableStateOf(false) }
    var isSoundOn by remember { mutableStateOf(false) }
    var isWhistleOn by remember { mutableStateOf(false) }

    var showAddLocoDialog by remember { mutableStateOf(false) }

    val activeLoco = savedLocos.find { it.id == selectedLocoId } ?: savedLocos.firstOrNull()

    LaunchedEffect(savedLocos) {
        if (selectedLocoId.isEmpty() && savedLocos.isNotEmpty()) {
            selectedLocoId = savedLocos.first().id
        }
    }

    // Abfragen der z21 RailCom Ist-Geschwindigkeit mit hoher Aktualisierungsrate (150ms)
    LaunchedEffect(activeLoco?.address) {
        activeLoco?.let { loco ->
            while (isActive) {
                onGetLocoInfo(loco.address)
                delay(150)
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Lokverwaltung Header & Selector
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "LOKOMOTIVEN (${savedLocos.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Button(
                            onClick = { showAddLocoDialog = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Lok anlegen", fontSize = 12.sp)
                        }
                    }

                    if (savedLocos.isEmpty()) {
                        Text("Keine Loks angelegt. Klicke auf 'Lok anlegen'.")
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            savedLocos.forEach { loco ->
                                val isSelected = loco.id == activeLoco?.id
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        selectedLocoId = loco.id
                                        speedSliderValue = 0f
                                    },
                                    label = { Text("${loco.name} (DCC ${loco.address})") }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Active Loco Fahrpult Header & Row (Links Tacho, Rechts vertikaler Fahrregler)
        activeLoco?.let { loco ->
            val maxLocoSpeed = loco.maxSpeedKmH.toFloat()
            val measuredSpeed = parseSpeedFromText(latestPacket?.textValue)

            // Tacho zeigt AUSSENDRÜCKLICH NUR die echte ausgelesene RailCom / Sensor Ist-Geschwindigkeit!
            val displaySpeedKmH = measuredSpeed ?: 0f
            val isUsingRealSensor = measuredSpeed != null && measuredSpeed > 0f

            item {
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
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Header: Lok Name & Adresse
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = loco.name,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "DCC Adresse: ${loco.address} | Max: ${loco.maxSpeedKmH} km/h",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                            }
                            IconButton(onClick = { onRemoveLoco(loco.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Lok löschen", tint = MaterialTheme.colorScheme.error)
                            }
                        }

                        // Row: Links runde Tachogauge | Rechts vertikaler Fahrregler
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Linke Seite: Tacho
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.weight(1.1f)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(bottom = 2.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isUsingRealSensor) Icons.Default.Sensors else Icons.Default.Speed,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = if (isUsingRealSensor) Color(0xFF00E676) else MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isUsingRealSensor) "RailCom Ist-Speed" else "Warte auf RailCom",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }

                                CircularSpeedometerGauge(
                                    speed = displaySpeedKmH,
                                    maxSpeed = maxLocoSpeed,
                                    unit = "km/h",
                                    modifier = Modifier.size(185.dp)
                                )
                            }

                            // Rechte Seite: Vertikaler Fahrregler (Slider im Hochformat)
                            Column(
                                modifier = Modifier
                                    .weight(0.9f)
                                    .background(
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                                        RoundedCornerShape(14.dp)
                                    )
                                    .padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "FAHRREGLER",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    letterSpacing = 1.2.sp
                                )

                                Text(
                                    text = "${speedSliderValue.toInt()}%",
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )

                                // Vertical Slider im Hochformat
                                Box(
                                    modifier = Modifier
                                        .height(160.dp)
                                        .width(50.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Slider(
                                        value = speedSliderValue,
                                        onValueChange = { newValue ->
                                            speedSliderValue = newValue
                                            onSetLocoSpeed(loco.address, newValue, isForwardDirection)
                                        },
                                        valueRange = 0f..100f,
                                        modifier = Modifier
                                            .requiredWidth(160.dp)
                                            .graphicsLayer {
                                                rotationZ = -90f
                                            }
                                    )
                                }

                                // Fahrtrichtung (Vorwärts / Rückwärts)
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    FilterChip(
                                        selected = isForwardDirection,
                                        onClick = {
                                            isForwardDirection = true
                                            onSetLocoSpeed(loco.address, speedSliderValue, true)
                                        },
                                        label = { Text("Vorwärts", fontSize = 10.sp) },
                                        modifier = Modifier.height(28.dp)
                                    )

                                    FilterChip(
                                        selected = !isForwardDirection,
                                        onClick = {
                                            isForwardDirection = false
                                            onSetLocoSpeed(loco.address, speedSliderValue, false)
                                        },
                                        label = { Text("Rückwärts", fontSize = 10.sp) },
                                        modifier = Modifier.height(28.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Lok Funktionen Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "LOK FUNKTIONEN (DCC)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            FilterChip(
                                selected = isLightOn,
                                onClick = {
                                    isLightOn = !isLightOn
                                    onSetLocoFunction(loco.address, 0, isLightOn)
                                },
                                label = { Text("F0 Licht") }
                            )

                            FilterChip(
                                selected = isSoundOn,
                                onClick = {
                                    isSoundOn = !isSoundOn
                                    onSetLocoFunction(loco.address, 1, isSoundOn)
                                },
                                label = { Text("F1 Sound") }
                            )

                            FilterChip(
                                selected = isWhistleOn,
                                onClick = {
                                    isWhistleOn = !isWhistleOn
                                    onSetLocoFunction(loco.address, 2, isWhistleOn)
                                },
                                label = { Text("F2 Pfeife") }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddLocoDialog) {
        var newName by remember { mutableStateOf("") }
        var newAddr by remember { mutableStateOf("3") }
        var newMaxSpd by remember { mutableStateOf("160") }

        AlertDialog(
            onDismissRequest = { showAddLocoDialog = false },
            title = { Text("Neue Lokomotive anlegen") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Lok Name (z.B. BR 103)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newAddr,
                        onValueChange = { newAddr = it },
                        label = { Text("DCC / CV Adresse (1-9999)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newMaxSpd,
                        onValueChange = { newMaxSpd = it },
                        label = { Text("Max. Geschwindigkeit (km/h)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val addr = newAddr.toIntOrNull() ?: 3
                        val spd = newMaxSpd.toIntOrNull() ?: 160
                        onAddLoco(newName, addr, spd, "Elektro")
                        showAddLocoDialog = false
                    }
                ) {
                    Text("Speichern")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddLocoDialog = false }) {
                    Text("Abbrechen")
                }
            }
        )
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

    val liveSpeedKmH = parseSpeedFromText(latestPacket?.textValue) ?: 0f

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Live Speedometer Card
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
                        text = "LIVEDATEN ($deviceName)",
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

                // Runder Tacho Gauge
                CircularSpeedometerGauge(
                    speed = liveSpeedKmH,
                    maxSpeed = 220f,
                    unit = "km/h",
                    modifier = Modifier.size(210.dp)
                )

                latestPacket?.let {
                    val sdf = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
                    val formattedTime = sdf.format(Date(it.timestampMs))
                    Text(
                        text = "Letztes Paket: $formattedTime (${it.rawBytes.size} Bytes)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
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
                            } else if (parseSpeedFromText(packet.textValue) != null) {
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
                TextButton(onClick = { onDismiss() }) {
                    Text("Schließen")
                }
            }
        },
        dismissButton = {
            if (customServiceUuid.isNotBlank() && customCharUuid.isNotBlank()) {
                TextButton(onClick = { onDismiss() }) {
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
