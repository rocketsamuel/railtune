package com.example.railtune.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.example.railtune.R
import com.example.railtune.model.BleDevice
import com.example.railtune.model.DccFunctions
import com.example.railtune.model.ConnectionState
import com.example.railtune.model.CvResult
import com.example.railtune.model.DiscoveredGattCharacteristic
import com.example.railtune.model.DiscoveredGattService
import com.example.railtune.model.LiveDataPacket
import com.example.railtune.model.Loco
import com.example.railtune.model.LocoTransfer
import com.example.railtune.model.RailComReading
import com.example.railtune.model.Z21LocoState
import com.example.railtune.viewmodel.BleViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
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

private fun parseRevolutionsFromText(text: String?): Float? {
    if (text.isNullOrBlank()) return null
    return text.split(',').getOrNull(1)?.trim()?.replace(',', '.')?.toFloatOrNull()
}

private data class BatteryReading(val estimatedPercent: Int)

private fun parseBatteryReading(text: String?): BatteryReading? {
    val fields = text?.split(',') ?: return null
    val millivolts = fields.getOrNull(2)?.toFloatOrNull() ?: return null
    val percent = fields.getOrNull(3)?.toIntOrNull() ?: return null
    if (millivolts <= 0f) return null
    return BatteryReading(percent.coerceIn(0, 100))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: BleViewModel) {
    val context = LocalContext.current
    val isWideDisplay = LocalConfiguration.current.screenWidthDp >= 600

    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val liveDataPacket by viewModel.liveDataStream.collectAsState()
    val dataHistory by viewModel.dataHistory.collectAsState()
    val lastConnectedAddress by viewModel.lastConnectedAddress.collectAsState()
    val lastConnectedName by viewModel.lastConnectedName.collectAsState()
    val isAutoConnectEnabled by viewModel.isAutoConnectEnabled.collectAsState()

    // z21 & CV States
    val z21Ip by viewModel.z21Ip.collectAsState()
    val z21Status by viewModel.z21Status.collectAsState()
    val cvResult by viewModel.cvResult.collectAsState()
    val isCvBusy by viewModel.isCvBusy.collectAsState()
    val savedLocos by viewModel.savedLocos.collectAsState()
    val railComReadings by viewModel.railComReadings.collectAsState()
    val locoStates by viewModel.locoStates.collectAsState()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var selectedMeasurementLocoId by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(savedLocos) {
        if (savedLocos.none { it.id == selectedMeasurementLocoId }) {
            selectedMeasurementLocoId = savedLocos.firstOrNull()?.id.orEmpty()
        }
    }

    LaunchedEffect(isWideDisplay) {
        if (!isWideDisplay && selectedTabIndex == 3) {
            selectedTabIndex = 0
        }
    }

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

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = Color.Black,
                contentColor = Color.White
            ) {
                NavigationBarItem(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                    label = { Text("Fahren") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = Color.White,
                        indicatorColor = Color(0xFF424242),
                        unselectedIconColor = Color.LightGray,
                        unselectedTextColor = Color.LightGray
                    )
                )
                NavigationBarItem(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    icon = { Icon(Icons.Default.Speed, contentDescription = null) },
                    label = { Text("Messen") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = Color.White,
                        indicatorColor = Color(0xFF424242),
                        unselectedIconColor = Color.LightGray,
                        unselectedTextColor = Color.LightGray
                    )
                )
                NavigationBarItem(
                    selected = selectedTabIndex == 2,
                    onClick = { selectedTabIndex = 2 },
                    icon = { Icon(Icons.Default.Code, contentDescription = null) },
                    label = { Text("Programmieren") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = Color.White,
                        indicatorColor = Color(0xFF424242),
                        unselectedIconColor = Color.LightGray,
                        unselectedTextColor = Color.LightGray
                    )
                )
                if (isWideDisplay) {
                    NavigationBarItem(
                        selected = selectedTabIndex == 3,
                        onClick = { selectedTabIndex = 3 },
                        icon = { Icon(Icons.Default.TabletAndroid, contentDescription = null) },
                        label = { Text("Tablet") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = Color.White,
                            indicatorColor = Color(0xFF424242),
                            unselectedIconColor = Color.LightGray,
                            unselectedTextColor = Color.LightGray
                        )
                    )
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
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
                        // Reiter 1: Fahren
                        DrivingScreen(
                            savedLocos = savedLocos,
                            railComReadings = railComReadings,
                            locoStates = locoStates,
                            onAddLoco = { name, addr, maxSpd, manufacturer, illustrationId, note, dccFunctions, railcomMultiplier ->
                                viewModel.addLoco(name, addr, maxSpd, manufacturer, illustrationId, note, dccFunctions, railcomMultiplier)
                            },
                            onUpdateLoco = { viewModel.updateLoco(it) },
                            onRemoveLoco = { viewModel.removeLoco(it) },
                            onSetLocoSpeed = { addr, spd, fwd -> viewModel.setLocoSpeed(addr, spd, fwd) },
                            onSetLocoFunction = { addr, func, state -> viewModel.setLocoFunction(addr, func, state) },
                            onGetLocoInfo = { addr -> viewModel.getLocoInfo(addr) },
                            onImportLocos = { viewModel.importLocos(it) }
                        )
                    }
                    1 -> {
                        // Reiter 2: Geschwindigkeit messen
                        SpeedMeasurementScreen(
                            selectedLocoId = selectedMeasurementLocoId,
                            onSelectedLocoChange = { selectedMeasurementLocoId = it },
                            savedLocos = savedLocos,
                            railComReadings = railComReadings,
                            connectionState = connectionState,
                            isScanning = isScanning,
                            lastConnectedName = lastConnectedName,
                            lastConnectedAddress = lastConnectedAddress,
                            isAutoConnectEnabled = isAutoConnectEnabled,
                            discoveredDevices = discoveredDevices,
                            liveDataPacket = liveDataPacket,
                            dataHistory = dataHistory,
                            onStartScan = {
                                if (hasPermissions) viewModel.startScan()
                                else permissionLauncher.launch(requiredPermissions)
                            },
                            onStopScan = { viewModel.stopScan() },
                            onDisconnect = { viewModel.disconnect() },
                            onToggleAutoConnect = { viewModel.setAutoConnectEnabled(it) },
                            onForgetDevice = { viewModel.forgetLastDevice() },
                            onConnectDevice = { viewModel.connectDevice(it) },
                            onClearHistory = { viewModel.clearDataHistory() }
                        )
                    }
                    2 -> {
                        // Reiter 3: Programmieren (CV Lesen / Schreiben & z21)
                        ProgrammingScreen(
                            z21Ip = z21Ip,
                            z21Status = z21Status,
                            cvResult = cvResult,
                            isCvBusy = isCvBusy,
                            onSetZ21Ip = { viewModel.setZ21Ip(it) },
                            onReadCv = { viewModel.readCv(it) },
                            onWriteCv = { cv, valNum -> viewModel.writeCv(cv, valNum) }
                        )
                    }
                    3 -> if (isWideDisplay) {
                        TabletModeScreen(
                            savedLocos = savedLocos,
                            locoStates = locoStates,
                            railComReadings = railComReadings,
                            onGetLocoInfo = { address -> viewModel.getLocoInfo(address) }
                        )
                    }
                }
            }
        }
    }

}

@Composable
private fun TabletModeScreen(
    savedLocos: List<Loco>,
    locoStates: Map<Int, Z21LocoState>,
    railComReadings: Map<Int, RailComReading>,
    onGetLocoInfo: (Int) -> Unit
) {
    val locoAddresses = remember(savedLocos) { savedLocos.map { it.address }.distinct() }

    LaunchedEffect(locoAddresses) {
        if (locoAddresses.isNotEmpty()) {
            while (isActive) {
                locoAddresses.forEach { address ->
                    onGetLocoInfo(address)
                    delay(150)
                }
            }
        }
    }

    val movingLocos = savedLocos
        .filter { (locoStates[it.address]?.speedPercent ?: 0f) > 0f }
        .sortedBy { it.name.trim().lowercase(Locale.ROOT) }

    if (movingLocos.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("Keine Fahrzeuge in Bewegung", style = MaterialTheme.typography.titleMedium)
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 260.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(movingLocos, key = { it.id }) { loco ->
                val locoState = locoStates[loco.address] ?: return@items
                val maxSpeed = loco.maxSpeedKmH.coerceAtLeast(1).toFloat()
                val railComReading = railComReadings[loco.address]
                val usesTargetSpeed = loco.decoderManufacturer.contains("Minitrix", ignoreCase = true)
                val isDoehlerHaass = loco.decoderManufacturer.equals("Doehler & Haass", ignoreCase = true) ||
                    loco.decoderManufacturer.equals("D&H", ignoreCase = true)
                val speed = when {
                    usesTargetSpeed -> maxSpeed * locoState.speedPercent / 100f
                    railComReading == null -> maxSpeed * locoState.speedPercent / 100f
                    isDoehlerHaass -> railComReading.rawSpeed.toFloat()
                    loco.decoderManufacturer.equals("Custom", ignoreCase = true) ->
                        railComReading.rawSpeed * loco.railcomMultiplier
                    else -> (railComReading.rawSpeed.coerceIn(0, 255) / 255f) * maxSpeed
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = loco.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1
                        )
                        CircularSpeedometerGauge(
                            speed = speed,
                            maxSpeed = maxSpeed,
                            unit = "km/h",
                            modifier = Modifier.size(220.dp)
                        )
                        Text(
                            text = "RailCom-Rohwert: ${railComReading?.rawSpeed?.toString() ?: "keine Rückmeldung"}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
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
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow
        ),
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
                colorStops = arrayOf(
                    0f to Color(0xFFFF1744),
                    (40f / 360f) to Color(0xFFFF1744),
                    (140f / 360f) to Color(0xFF00E676),
                    (270f / 360f) to Color(0xFFFFB300),
                    1f to Color(0xFFFF1744)
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
            Text(
                text = animatedSpeed.toInt().toString(),
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
    selectedLocoId: String,
    onSelectedLocoChange: (String) -> Unit,
    savedLocos: List<Loco>,
    railComReadings: Map<Int, RailComReading>,
    connectionState: ConnectionState,
    isScanning: Boolean,
    lastConnectedName: String?,
    lastConnectedAddress: String?,
    isAutoConnectEnabled: Boolean,
    discoveredDevices: List<BleDevice>,
    liveDataPacket: LiveDataPacket?,
    dataHistory: List<LiveDataPacket>,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onDisconnect: () -> Unit,
    onToggleAutoConnect: (Boolean) -> Unit,
    onForgetDevice: () -> Unit,
    onConnectDevice: (BleDevice) -> Unit,
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
                    selectedLocoId = selectedLocoId,
                    onSelectedLocoChange = onSelectedLocoChange,
                    savedLocos = savedLocos,
                    railComReadings = railComReadings,
                    latestPacket = liveDataPacket,
                    history = dataHistory,
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
    onWriteCv: (Int, Int) -> Unit
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
private fun DecoderManufacturerDropdown(
    selectedManufacturer: String,
    onManufacturerSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val manufacturers = listOf("Zimo", "Zimo MS 590N18", "Doehler & Haass", "Minitrix", "Custom")

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(selectedManufacturer.ifBlank { "Decoder-Hersteller auswählen" })
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            manufacturers.forEach { manufacturer ->
                DropdownMenuItem(
                    text = { Text(manufacturer) },
                    onClick = {
                        onManufacturerSelected(manufacturer)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun LocoIllustrationDropdown(
    selectedIllustrationId: String,
    onIllustrationSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val illustrations = listOf(
        LocoIllustrationChoice("", "Kein Bild", null),
        LocoIllustrationChoice("br101", "BR 101", R.drawable.br101),
        LocoIllustrationChoice("br101_50jahre", "BR 101 – 50 Jahre", R.drawable.br101_50jahre),
        LocoIllustrationChoice("br103", "BR 103", R.drawable.br103),
        LocoIllustrationChoice("br110", "BR 110", R.drawable.br110),
        LocoIllustrationChoice("br111", "BR 111", R.drawable.br111),
        LocoIllustrationChoice("br111_ob", "BR 111 – o/b", R.drawable.br111_ob),
        LocoIllustrationChoice("br112", "BR 112", R.drawable.br112),
        LocoIllustrationChoice("br120", "BR 120", R.drawable.br120),
        LocoIllustrationChoice("br023", "BR 023", R.drawable.br023),
        LocoIllustrationChoice("br139", "BR 139", R.drawable.br139),
        LocoIllustrationChoice("br140", "BR 140", R.drawable.br140),
        LocoIllustrationChoice("br141", "BR 141", R.drawable.br141),
        LocoIllustrationChoice("br146", "BR 146", R.drawable.br146),
        LocoIllustrationChoice("br147", "BR 147", R.drawable.br147),
        LocoIllustrationChoice("br152", "BR 152", R.drawable.br152),
        LocoIllustrationChoice("br185", "BR 185", R.drawable.br185),
        LocoIllustrationChoice("br185_lokomotion", "BR 185 – Lokomotion", R.drawable.br185_lokomotion),
        LocoIllustrationChoice("br187", "BR 187", R.drawable.br187),
        LocoIllustrationChoice("br193_climate", "BR 193 – ClimateHero", R.drawable.br193_climate),
        LocoIllustrationChoice("br193_backbone", "BR 193 – Backbone", R.drawable.br193_backbone),
        LocoIllustrationChoice("br194", "BR 194", R.drawable.br194),
        LocoIllustrationChoice("br363", "BR 363", R.drawable.br363),
        LocoIllustrationChoice("re620", "Re 620", R.drawable.re620),
        LocoIllustrationChoice("re482", "Re 482 – Cargo", R.drawable.re482),
        LocoIllustrationChoice("sbb_186_nightpiercer", "SBB 186 – Nightpiercer", R.drawable.sbb_186_nightpiercer),
        LocoIllustrationChoice("rh1293", "Rh 1293", R.drawable.rh1293),
        LocoIllustrationChoice("rh1116", "Rh 1116 – ÖBB", R.drawable.rh1116),
        LocoIllustrationChoice("rh1116_nj", "Rh 1116 – Nightjet", R.drawable.rh1116_nj),
        LocoIllustrationChoice("rh1116_rj", "Rh 1116 – Railjet", R.drawable.rh1116_rj),
        LocoIllustrationChoice("br211", "BR 211", R.drawable.br211),
        LocoIllustrationChoice("br212", "BR 212 – Orientrot", R.drawable.br212),
        LocoIllustrationChoice("br212_standard", "BR 212 – Rot", R.drawable.br212_standard),
        LocoIllustrationChoice("br218_or", "BR 218 – Orientrot", R.drawable.br218_or),
        LocoIllustrationChoice("br218_tee", "BR 218 – TEE", R.drawable.br218_tee),
        LocoIllustrationChoice("br401", "ICE 1 (BR 401)", R.drawable.br401),
        LocoIllustrationChoice("br402", "ICE 2 (BR 402)", R.drawable.br402),
        LocoIllustrationChoice("br411", "ICE T (BR 411)", R.drawable.br411),
        LocoIllustrationChoice("br412", "ICE 4 (BR 412)", R.drawable.br412)
    )
    val selectedIllustration = illustrations.firstOrNull { it.id == selectedIllustrationId }
    val selectedName = selectedIllustration?.label ?: "Kein Bild"
    val selectedImage = selectedIllustration?.imageRes

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selectedImage != null) {
                        Image(
                            painter = painterResource(selectedImage),
                            contentDescription = null,
                            modifier = Modifier.width(48.dp).height(20.dp),
                            contentScale = ContentScale.Fit
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(selectedName)
                }
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            illustrations.forEach { illustration ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (illustration.imageRes != null) {
                                Image(
                                    painter = painterResource(illustration.imageRes),
                                    contentDescription = null,
                                    modifier = Modifier.width(48.dp).height(20.dp),
                                    contentScale = ContentScale.Fit
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(illustration.label)
                        }
                    },
                    onClick = {
                        onIllustrationSelected(illustration.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

private data class LocoIllustrationChoice(
    val id: String,
    val label: String,
    val imageRes: Int?
)

private data class DccFunctionInputs(
    val light: String = "",
    val sound: String = "",
    val hornHigh: String = "",
    val hornLow: String = "",
    val cabLight: String = "",
    val zg12Fst1: String = "",
    val zg12Fst2: String = ""
) {
    fun isValid(): Boolean = listOf(light, sound, hornHigh, hornLow, cabLight, zg12Fst1, zg12Fst2)
        .all { value -> value.isBlank() || value.toIntOrNull()?.let { it in 0..63 } == true }

    fun toDccFunctions() = DccFunctions(
        light = light.toIntOrNull(),
        sound = sound.toIntOrNull(),
        hornHigh = hornHigh.toIntOrNull(),
        hornLow = hornLow.toIntOrNull(),
        cabLight = cabLight.toIntOrNull(),
        zg12Fst1 = zg12Fst1.toIntOrNull(),
        zg12Fst2 = zg12Fst2.toIntOrNull()
    )
}

private fun DccFunctions.toInputs() = DccFunctionInputs(
    light = light?.toString().orEmpty(),
    sound = sound?.toString().orEmpty(),
    hornHigh = hornHigh?.toString().orEmpty(),
    hornLow = hornLow?.toString().orEmpty(),
    cabLight = cabLight?.toString().orEmpty(),
    zg12Fst1 = zg12Fst1?.toString().orEmpty(),
    zg12Fst2 = zg12Fst2?.toString().orEmpty()
)

private data class DccFunctionDefinition(
    val label: String,
    val icon: ImageVector,
    val functionNumber: Int?,
    val momentary: Boolean = false
)

@Composable
private fun DccFunctionNumberField(
    label: String,
    icon: ImageVector,
    value: String,
    onValueChange: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = value,
            onValueChange = { input ->
                if (input.all(Char::isDigit) && input.length <= 2) onValueChange(input)
            },
            label = { Text("F-Taste") },
            placeholder = { Text("–") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(104.dp)
        )
    }
}

@Composable
private fun DccFunctionAssignmentsEditor(
    values: DccFunctionInputs,
    onValuesChange: (DccFunctionInputs) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("F-Taste eintragen (0–63); leere Felder blenden die Funktion aus.", style = MaterialTheme.typography.bodySmall)
        DccFunctionNumberField("Licht", Icons.Default.Lightbulb, values.light) { onValuesChange(values.copy(light = it)) }
        DccFunctionNumberField("Sound", Icons.Default.VolumeUp, values.sound) { onValuesChange(values.copy(sound = it)) }
        DccFunctionNumberField("Horn hoch", Icons.Default.Campaign, values.hornHigh) { onValuesChange(values.copy(hornHigh = it)) }
        DccFunctionNumberField("Horn tief", Icons.Default.Campaign, values.hornLow) { onValuesChange(values.copy(hornLow = it)) }
        DccFunctionNumberField("Führerstand Beleuchtung", Icons.Default.WbIncandescent, values.cabLight) { onValuesChange(values.copy(cabLight = it)) }
        DccFunctionNumberField("Zg1/2 aus Fst 1", Icons.Default.LooksOne, values.zg12Fst1) { onValuesChange(values.copy(zg12Fst1 = it)) }
        DccFunctionNumberField("Zg1/2 aus Fst 2", Icons.Default.LooksTwo, values.zg12Fst2) { onValuesChange(values.copy(zg12Fst2 = it)) }
    }
}

private fun locoIllustrationResource(id: String): Int? = when (id) {
    "br101" -> R.drawable.br101
    "br101_50jahre" -> R.drawable.br101_50jahre
    "br103" -> R.drawable.br103
    "br110" -> R.drawable.br110
    "br111" -> R.drawable.br111
    "br111_ob" -> R.drawable.br111_ob
    "br112" -> R.drawable.br112
    "br120" -> R.drawable.br120
    "br023" -> R.drawable.br023
    "br139" -> R.drawable.br139
    "br140" -> R.drawable.br140
    "br141" -> R.drawable.br141
    "br146" -> R.drawable.br146
    "br147" -> R.drawable.br147
    "br152" -> R.drawable.br152
    "br185" -> R.drawable.br185
    "br185_lokomotion" -> R.drawable.br185_lokomotion
    "br187" -> R.drawable.br187
    "br193_climate" -> R.drawable.br193_climate
    "br193_backbone" -> R.drawable.br193_backbone
    "br194" -> R.drawable.br194
    "br363" -> R.drawable.br363
    "re620" -> R.drawable.re620
    "re482" -> R.drawable.re482
    "sbb_186_nightpiercer" -> R.drawable.sbb_186_nightpiercer
    "rh1293" -> R.drawable.rh1293
    "rh1116" -> R.drawable.rh1116
    "rh1116_nj" -> R.drawable.rh1116_nj
    "rh1116_rj" -> R.drawable.rh1116_rj
    "br211" -> R.drawable.br211
    "br212" -> R.drawable.br212
    "br212_standard" -> R.drawable.br212_standard
    "br218_or" -> R.drawable.br218_or
    "br218_tee" -> R.drawable.br218_tee
    "br401" -> R.drawable.br401
    "br402" -> R.drawable.br402
    "br411" -> R.drawable.br411
    "br412" -> R.drawable.br412
    else -> null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DrivingScreen(
    savedLocos: List<Loco>,
    railComReadings: Map<Int, RailComReading>,
    locoStates: Map<Int, Z21LocoState>,
    onAddLoco: (String, Int, Int, String, String, String, DccFunctions, Float) -> Loco,
    onUpdateLoco: (Loco) -> Unit,
    onRemoveLoco: (String) -> Unit,
    onSetLocoSpeed: (Int, Float, Boolean) -> Unit,
    onSetLocoFunction: (Int, Int, Boolean) -> Unit,
    onGetLocoInfo: (Int) -> Unit,
    onImportLocos: (List<Loco>) -> Unit
) {
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(LocoTransfer.encode(savedLocos)) }
                ?: error("Datei konnte nicht geschrieben werden.")
        }.onSuccess {
            Toast.makeText(context, "Fahrzeuge exportiert", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, "Export fehlgeschlagen: ${it.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Datei konnte nicht gelesen werden.")
            LocoTransfer.decode(json)
        }.onSuccess { imported ->
            onImportLocos(imported)
            Toast.makeText(context, "${imported.size} Fahrzeuge importiert (gleiche DCC-Adressen ersetzt)", Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(context, "Import fehlgeschlagen: ${it.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }
    val drivingPreferences = remember(context) {
        context.getSharedPreferences("railtune_preferences", 0)
    }
    var selectedLocoId by remember {
        mutableStateOf(
            drivingPreferences.getString("selected_driving_loco_id", null)
                ?: savedLocos.firstOrNull()?.id.orEmpty()
        )
    }
    var targetSpeedKmH by remember { mutableFloatStateOf(0f) }
    var isForwardDirection by remember { mutableStateOf(true) }

    var showAddLocoDialog by remember { mutableStateOf(false) }
    var editingLoco by remember { mutableStateOf<Loco?>(null) }

    val activeLoco = savedLocos.find { it.id == selectedLocoId } ?: savedLocos.firstOrNull()
    var activeDccFunctionNumbers by remember(activeLoco?.id) { mutableStateOf(emptySet<Int>()) }
    val activeZ21State = activeLoco?.let { locoStates[it.address] }

    LaunchedEffect(savedLocos) {
        if (savedLocos.isNotEmpty() && savedLocos.none { it.id == selectedLocoId }) {
            selectedLocoId = savedLocos.first().id
        }
    }

    LaunchedEffect(selectedLocoId) {
        drivingPreferences.edit()
            .putString("selected_driving_loco_id", selectedLocoId)
            .apply()
    }

    LaunchedEffect(activeLoco?.id, activeZ21State) {
        activeZ21State?.let { state ->
            activeDccFunctionNumbers = state.activeFunctions
            isForwardDirection = state.isForward
            activeLoco?.let { loco ->
                targetSpeedKmH = loco.maxSpeedKmH.coerceAtLeast(1) * state.speedPercent / 100f
            }
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
                            text = "FAHRZEUGE (${savedLocos.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(onClick = { exportLauncher.launch("RailTune-Fahrzeuge.json") }, enabled = savedLocos.isNotEmpty()) {
                                Icon(Icons.Default.FileUpload, contentDescription = "Fahrzeuge exportieren")
                            }
                            IconButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/*")) }) {
                                Icon(Icons.Default.FileDownload, contentDescription = "Fahrzeuge importieren")
                            }
                            FilterChip(
                                selected = false,
                                onClick = { showAddLocoDialog = true },
                                label = { Text("Neu", maxLines = 1) },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (savedLocos.isEmpty()) {
                                Text("Keine Fahrzeuge angelegt", maxLines = 1)
                            }
                            savedLocos.sortedBy { it.name.trim().lowercase(Locale.ROOT) }.forEach { loco ->
                                val isSelected = loco.id == activeLoco?.id
                                val illustrationRes = locoIllustrationResource(loco.illustrationId)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        selectedLocoId = loco.id
                                        targetSpeedKmH = 0f
                                    },
                                    label = {
                                        if (illustrationRes != null) {
                                            Column(
                                                modifier = Modifier.padding(vertical = 4.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                Text(text = loco.name, maxLines = 1)
                                                Image(
                                                    painter = painterResource(illustrationRes),
                                                    contentDescription = null,
                                                    modifier = Modifier.width(64.dp).height(20.dp),
                                                    contentScale = ContentScale.Fit
                                                )
                                            }
                                        } else {
                                            Text(
                                                text = loco.name,
                                                maxLines = 1,
                                                modifier = Modifier.padding(vertical = 4.dp)
                                            )
                                        }
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // Active Loco Fahrpult Header & Row (Links Tacho, Rechts vertikaler Fahrregler)
        activeLoco?.let { loco ->
            val maxLocoSpeed = loco.maxSpeedKmH.coerceAtLeast(1).toFloat()
            val displayedTargetSpeedKmH = targetSpeedKmH.coerceIn(0f, maxLocoSpeed)
            val targetSpeedPercent = (displayedTargetSpeedKmH / maxLocoSpeed * 100f)
            val railComReading = railComReadings[loco.address]
            val usesTargetSpeed = loco.decoderManufacturer.contains("Minitrix", ignoreCase = true)
            val isDoehlerHaass = loco.decoderManufacturer.equals("Doehler & Haass", ignoreCase = true) ||
                    loco.decoderManufacturer.equals("D&H", ignoreCase = true)
            // D&H's RailCom value is calibrated and transmitted in km/h;
            // Zimo reports the 0..255 speed scale used by this app's setup.
            val displaySpeedKmH = when {
                usesTargetSpeed -> displayedTargetSpeedKmH
                railComReading == null -> 0f
                isDoehlerHaass -> railComReading.rawSpeed.toFloat()
                loco.decoderManufacturer.equals("Custom", ignoreCase = true) ->
                    railComReading.rawSpeed * loco.railcomMultiplier
                else -> (railComReading.rawSpeed.coerceIn(0, 255) / 255f) * maxLocoSpeed
            }
            val hasRailComSpeed = railComReading != null && !usesTargetSpeed

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
                                if (loco.note.isNotBlank()) {
                                    Text(
                                        text = loco.note,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                    )
                                }
                            }
                            IconButton(onClick = { editingLoco = loco }) {
                                Icon(Icons.Default.Edit, contentDescription = "Lok bearbeiten")
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
                                    modifier = Modifier.padding(bottom = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Speed,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = if (hasRailComSpeed) Color(0xFF00E676) else MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = when {
                                            usesTargetSpeed -> "Soll-Geschwindigkeit"
                                            hasRailComSpeed -> "RailCom Ist-Speed"
                                            else -> "Warte auf RailCom"
                                        },
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
                                    .weight(0.8f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "SOLLWERT",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    letterSpacing = 1.2.sp
                                )

                                Text(
                                    text = "${displayedTargetSpeedKmH.toInt()} km/h",
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )

                                // Vertical Slider im Hochformat
                                Box(
                                    modifier = Modifier
                                        .height(150.dp)
                                        .width(44.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Slider(
                                        value = displayedTargetSpeedKmH,
                                        onValueChange = { newValue ->
                                            targetSpeedKmH = newValue
                                            val speedPercent = (newValue / maxLocoSpeed * 100f)
                                                .coerceIn(0f, 100f)
                                            onSetLocoSpeed(loco.address, speedPercent, isForwardDirection)
                                        },
                                        valueRange = 0f..maxLocoSpeed,
                                        modifier = Modifier
                                            .requiredWidth(150.dp)
                                            .graphicsLayer {
                                                rotationZ = -90f
                                            }
                                    )
                                }

                            }
                        }

                        // Fahrtrichtung unter dem Tacho und Stop unter dem Sollwertregler zentrieren.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1.1f),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FilledTonalIconButton(
                                    onClick = {
                                        isForwardDirection = false
                                        onSetLocoSpeed(loco.address, targetSpeedPercent, false)
                                    },
                                    modifier = Modifier.size(40.dp),
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = if (!isForwardDirection) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = if (!isForwardDirection) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                ) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = "Rückwärts")
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                FilledTonalIconButton(
                                    onClick = {
                                        isForwardDirection = true
                                        onSetLocoSpeed(loco.address, targetSpeedPercent, true)
                                    },
                                    modifier = Modifier.size(40.dp),
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = if (isForwardDirection) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = if (isForwardDirection) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                ) {
                                    Icon(Icons.Default.ArrowForward, contentDescription = "Vorwärts")
                                }
                            }

                            Box(
                                modifier = Modifier.weight(0.8f),
                                contentAlignment = Alignment.Center
                            ) {
                                FilledIconButton(
                                    onClick = {
                                        targetSpeedKmH = 0f
                                        onSetLocoSpeed(loco.address, 0f, isForwardDirection)
                                    },
                                    modifier = Modifier.size(40.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError
                                    )
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = "Stop")
                                }
                            }
                        }
                    }
                }
            }

            // Lok Funktionen Card
            item {
                val functions = listOf(
                    DccFunctionDefinition("Licht", Icons.Default.Lightbulb, loco.dccFunctions.light),
                    DccFunctionDefinition("Sound", Icons.Default.VolumeUp, loco.dccFunctions.sound),
                    DccFunctionDefinition("Horn hoch", Icons.Default.Campaign, loco.dccFunctions.hornHigh, momentary = true),
                    DccFunctionDefinition("Horn tief", Icons.Default.Campaign, loco.dccFunctions.hornLow, momentary = true),
                    DccFunctionDefinition("Führerstand Beleuchtung", Icons.Default.WbIncandescent, loco.dccFunctions.cabLight),
                    DccFunctionDefinition("Zg1/2 aus Fst 1", Icons.Default.LooksOne, loco.dccFunctions.zg12Fst1),
                    DccFunctionDefinition("Zg1/2 aus Fst 2", Icons.Default.LooksTwo, loco.dccFunctions.zg12Fst2)
                ).filter { it.functionNumber != null }
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

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            functions.forEach { function ->
                                val functionNumber = function.functionNumber ?: return@forEach
                                val isOn = functionNumber in activeDccFunctionNumbers
                                if (function.momentary) {
                                    val interactionSource = remember(loco.address, functionNumber) {
                                        MutableInteractionSource()
                                    }
                                    LaunchedEffect(loco.address, functionNumber, interactionSource) {
                                        interactionSource.interactions.collect { interaction ->
                                            when (interaction) {
                                                is PressInteraction.Press -> onSetLocoFunction(loco.address, functionNumber, true)
                                                is PressInteraction.Release,
                                                is PressInteraction.Cancel -> onSetLocoFunction(loco.address, functionNumber, false)
                                                else -> Unit
                                            }
                                        }
                                    }
                                    FilterChip(
                                        selected = isOn,
                                        onClick = {},
                                        interactionSource = interactionSource,
                                        label = { Icon(function.icon, contentDescription = function.label) }
                                    )
                                } else {
                                    FilterChip(
                                        selected = isOn,
                                        onClick = {
                                            val nextState = !isOn
                                            activeDccFunctionNumbers = if (nextState) {
                                                activeDccFunctionNumbers + functionNumber
                                            } else {
                                                activeDccFunctionNumbers - functionNumber
                                            }
                                            onSetLocoFunction(loco.address, functionNumber, nextState)
                                        },
                                        label = { Icon(function.icon, contentDescription = function.label) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddLocoDialog) {
        var addDialogTab by remember { mutableIntStateOf(0) }
        var newName by remember { mutableStateOf("") }
        var newAddr by remember { mutableStateOf("3") }
        var newMaxSpd by remember { mutableStateOf("160") }
        var newDecoderManufacturer by remember { mutableStateOf("") }
        var newRailcomMultiplier by remember { mutableStateOf("1.0") }
        var newIllustrationId by remember { mutableStateOf("") }
        var newNote by remember { mutableStateOf("") }
        var newDccFunctionInputs by remember { mutableStateOf(DccFunctionInputs()) }

        Dialog(onDismissRequest = { showAddLocoDialog = false }) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TabRow(selectedTabIndex = addDialogTab) {
                        Tab(selected = addDialogTab == 0, onClick = { addDialogTab = 0 }, text = { Text("Allgemein") })
                        Tab(selected = addDialogTab == 1, onClick = { addDialogTab = 1 }, text = { Text("DCC") })
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 560.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (addDialogTab == 0) {
                            OutlinedTextField(
                                value = newName,
                                onValueChange = { newName = it },
                                label = { Text("Lok Name (z.B. BR 103)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = newNote,
                                onValueChange = { newNote = it },
                                label = { Text("Bemerkung") },
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
                            DecoderManufacturerDropdown(
                                selectedManufacturer = newDecoderManufacturer,
                                onManufacturerSelected = { newDecoderManufacturer = it }
                            )
                            if (newDecoderManufacturer.equals("Custom", ignoreCase = true)) {
                                OutlinedTextField(
                                    value = newRailcomMultiplier,
                                    onValueChange = { newRailcomMultiplier = it },
                                    label = { Text("Railcom Multiplikator") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            LocoIllustrationDropdown(
                                selectedIllustrationId = newIllustrationId,
                                onIllustrationSelected = { newIllustrationId = it }
                            )
                        } else {
                            DccFunctionAssignmentsEditor(
                                values = newDccFunctionInputs,
                                onValuesChange = { newDccFunctionInputs = it }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showAddLocoDialog = false }) {
                            Text("Abbrechen")
                        }
                        Button(
                            enabled = newDecoderManufacturer.isNotBlank() && newDccFunctionInputs.isValid() &&
                                (!newDecoderManufacturer.equals("Custom", ignoreCase = true) ||
                                    (newRailcomMultiplier.replace(',', '.').toFloatOrNull()?.let { it >= 0f } == true)),
                            onClick = {
                                val addr = newAddr.toIntOrNull() ?: 3
                                val spd = newMaxSpd.toIntOrNull() ?: 160
                                onAddLoco(
                                    newName,
                                    addr,
                                    spd,
                                    newDecoderManufacturer,
                                    newIllustrationId,
                                    newNote,
                                    newDccFunctionInputs.toDccFunctions(),
                                    if (newDecoderManufacturer.equals("Custom", ignoreCase = true))
                                        newRailcomMultiplier.replace(',', '.').toFloat() else 1f
                                )
                                showAddLocoDialog = false
                            }
                        ) {
                            Text("Speichern")
                        }
                    }
                }
            }
        }
    }

    editingLoco?.let { loco: Loco ->
        var editDialogTab by remember(loco.id) { mutableIntStateOf(0) }
        var editName by remember(loco.id) { mutableStateOf(loco.name) }
        var editAddress by remember(loco.id) { mutableStateOf(loco.address.toString()) }
        var editMaxSpeed by remember(loco.id) { mutableStateOf(loco.maxSpeedKmH.toString()) }
        var editDecoderManufacturer by remember(loco.id) { mutableStateOf(loco.decoderManufacturer) }
        var editRailcomMultiplier by remember(loco.id) { mutableStateOf(loco.railcomMultiplier.toString()) }
        var editIllustrationId by remember(loco.id) { mutableStateOf(loco.illustrationId) }
        var editNote by remember(loco.id) { mutableStateOf(loco.note) }
        var editDccFunctionInputs by remember(loco.id) { mutableStateOf(loco.dccFunctions.toInputs()) }

        Dialog(onDismissRequest = { editingLoco = null }) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TabRow(selectedTabIndex = editDialogTab) {
                        Tab(selected = editDialogTab == 0, onClick = { editDialogTab = 0 }, text = { Text("Allgemein") })
                        Tab(selected = editDialogTab == 1, onClick = { editDialogTab = 1 }, text = { Text("DCC") })
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 560.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (editDialogTab == 0) {
                            OutlinedTextField(
                                value = editName,
                                onValueChange = { editName = it },
                                label = { Text("Lokname") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = editNote,
                                onValueChange = { editNote = it },
                                label = { Text("Bemerkung") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = editAddress,
                                onValueChange = { editAddress = it },
                                label = { Text("DCC-Adresse (1–9999)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = editMaxSpeed,
                                onValueChange = { editMaxSpeed = it },
                                label = { Text("Max. Geschwindigkeit (km/h)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            DecoderManufacturerDropdown(
                                selectedManufacturer = editDecoderManufacturer,
                                onManufacturerSelected = { editDecoderManufacturer = it }
                            )
                            if (editDecoderManufacturer.equals("Custom", ignoreCase = true)) {
                                OutlinedTextField(
                                    value = editRailcomMultiplier,
                                    onValueChange = { editRailcomMultiplier = it },
                                    label = { Text("Railcom Multiplikator") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            LocoIllustrationDropdown(
                                selectedIllustrationId = editIllustrationId,
                                onIllustrationSelected = { editIllustrationId = it }
                            )
                            TextButton(
                                onClick = {
                                    onRemoveLoco(loco.id)
                                    if (selectedLocoId == loco.id) {
                                        selectedLocoId = savedLocos.firstOrNull { it.id != loco.id }?.id.orEmpty()
                                    }
                                    editingLoco = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Fahrzeug löschen", color = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            DccFunctionAssignmentsEditor(
                                values = editDccFunctionInputs,
                                onValuesChange = { editDccFunctionInputs = it }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { editingLoco = null }) {
                            Text("Abbrechen")
                        }
                        Button(
                            enabled = (editAddress.toIntOrNull()?.let { it in 1..9999 } == true) &&
                                (editMaxSpeed.toIntOrNull() ?: 0) > 0 &&
                                editDecoderManufacturer.isNotBlank() && editDccFunctionInputs.isValid() &&
                                (!editDecoderManufacturer.equals("Custom", ignoreCase = true) ||
                                    (editRailcomMultiplier.replace(',', '.').toFloatOrNull()?.let { it >= 0f } == true)),
                            onClick = {
                                onUpdateLoco(
                                    loco.copy(
                                        name = editName,
                                        address = editAddress.toInt(),
                                        maxSpeedKmH = editMaxSpeed.toInt(),
                                        decoderManufacturer = editDecoderManufacturer,
                                        railcomMultiplier = if (editDecoderManufacturer.equals("Custom", ignoreCase = true))
                                            editRailcomMultiplier.replace(',', '.').toFloat() else 1f,
                                        illustrationId = editIllustrationId,
                                        note = editNote,
                                        dccFunctions = editDccFunctionInputs.toDccFunctions()
                                    )
                                )
                                editingLoco = null
                            }
                        ) {
                            Text("Speichern")
                        }
                    }
                }
            }
        }
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
private fun ComparisonSpeedRow(label: String, speed: Float, maxSpeed: Float, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text("${"%.1f".format(Locale.getDefault(), speed)} km/h", fontWeight = FontWeight.Bold)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth((speed / maxSpeed).coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(color)
            )
        }
    }
}

@Composable
fun LiveDashboardView(
    selectedLocoId: String,
    onSelectedLocoChange: (String) -> Unit,
    savedLocos: List<Loco>,
    railComReadings: Map<Int, RailComReading>,
    latestPacket: LiveDataPacket?,
    history: List<LiveDataPacket>,
    onClearHistory: () -> Unit
) {
    var locoMenuExpanded by remember { mutableStateOf(false) }

    val liveSpeedKmH = parseSpeedFromText(latestPacket?.textValue) ?: 0f
    val measuredRevolutions = parseRevolutionsFromText(latestPacket?.textValue)
    val batteryReading = parseBatteryReading(latestPacket?.textValue)
    val selectedLoco = savedLocos.firstOrNull { it.id == selectedLocoId }
    val selectedRailCom = selectedLoco?.let { railComReadings[it.address] }
    val railComSpeedKmH = selectedLoco?.let { loco ->
        val isMinitrix = loco.decoderManufacturer.contains("Minitrix", ignoreCase = true)
        val isDoehlerHaass = loco.decoderManufacturer.equals("Doehler & Haass", ignoreCase = true) ||
            loco.decoderManufacturer.equals("D&H", ignoreCase = true)
        when {
            isMinitrix || selectedRailCom == null -> null
            isDoehlerHaass -> selectedRailCom.rawSpeed.toFloat()
            loco.decoderManufacturer.equals("Custom", ignoreCase = true) ->
                selectedRailCom.rawSpeed * loco.railcomMultiplier
            else -> (selectedRailCom.rawSpeed.coerceIn(0, 255) / 255f) * loco.maxSpeedKmH.coerceAtLeast(1)
        }
    }

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
                        text = "LIVEDATEN",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        letterSpacing = 1.5.sp
                    )
                    batteryReading?.let { battery ->
                        val batteryIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                        val crossedBatteryColor = MaterialTheme.colorScheme.error
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (battery.estimatedPercent == 0) Icons.Default.Battery0Bar else Icons.Default.BatteryFull,
                                    contentDescription = if (battery.estimatedPercent == 0) "Akku leer" else "Akku",
                                    tint = batteryIconColor
                                )
                                if (battery.estimatedPercent == 0) {
                                    Canvas(modifier = Modifier.size(24.dp)) {
                                        drawLine(
                                            color = crossedBatteryColor,
                                            start = Offset(size.width * 0.18f, size.height * 0.82f),
                                            end = Offset(size.width * 0.82f, size.height * 0.18f),
                                            strokeWidth = 2.dp.toPx(),
                                            cap = StrokeCap.Round
                                        )
                                    }
                                }
                            }
                            Text(
                                text = "${battery.estimatedPercent}%",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = batteryIconColor
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Triebfahrzeug", style = MaterialTheme.typography.labelLarge)
                    Box {
                        OutlinedButton(
                            onClick = { locoMenuExpanded = true },
                            enabled = savedLocos.isNotEmpty()
                        ) {
                            Text(selectedLoco?.name ?: "Lok auswählen")
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = locoMenuExpanded,
                            onDismissRequest = { locoMenuExpanded = false }
                        ) {
                            savedLocos.forEach { loco ->
                                DropdownMenuItem(
                                    text = { Text(loco.name) },
                                    onClick = {
                                        onSelectedLocoChange(loco.id)
                                        locoMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                if (selectedLoco != null) {
                    val comparisonMax = maxOf(
                        selectedLoco.maxSpeedKmH.coerceAtLeast(1).toFloat(),
                        liveSpeedKmH,
                        railComSpeedKmH ?: 0f
                    )
                    ComparisonSpeedRow("Messwagen", liveSpeedKmH, comparisonMax, MaterialTheme.colorScheme.primary)
                    if (railComSpeedKmH != null) {
                        ComparisonSpeedRow("${selectedLoco.name}", railComSpeedKmH, comparisonMax, Color(0xFFFF9800))
                        val difference = liveSpeedKmH - railComSpeedKmH
                        Text(
                            text = "Differenz (Messwagen − Lok): ${if (difference > 0) "+" else ""}${"%.1f".format(Locale.getDefault(), difference)} km/h",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (kotlin.math.abs(difference) < 0.1f) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    } else {
                        Text(
                            text = if (selectedLoco.decoderManufacturer.contains("Minitrix", ignoreCase = true))
                                "Für diesen Minitrix-Decoder liegt keine RailCom-Geschwindigkeit vor."
                            else "Noch keine RailCom-Daten für Adresse ${selectedLoco.address}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                        )
                    }
                } else {
                    ComparisonSpeedRow("Messwagen", liveSpeedKmH, maxOf(220f, liveSpeedKmH), MaterialTheme.colorScheme.primary)
                    Text(
                        "Wähle eine Lok aus, um die zweite Geschwindigkeit zu vergleichen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                    )
                }

                Text(
                    text = measuredRevolutions?.let {
                        "Umdrehungen (letzte 3s): ${"%.1f".format(Locale.getDefault(), it)}"
                    } ?: "Umdrehungen (letzte 3ss): –",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
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
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = sdf.format(Date(packet.timestampMs)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val packetDisplayText = if (parseSpeedFromText(packet.textValue) != null) {
                                val speedValue = requireNotNull(parseSpeedFromText(packet.textValue))
                                val speedText = "${"%.1f".format(Locale.getDefault(), speedValue)} km/h"
                                val revolutions = parseRevolutionsFromText(packet.textValue)
                                if (revolutions != null) {
                                    "$speedText · ${"%.1f".format(Locale.getDefault(), revolutions/3)} Umdrehungen/s"
                                } else {
                                    speedText
                                }
                            } else {
                                packet.textValue
                            }
                            Text(
                                text = packetDisplayText,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
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
