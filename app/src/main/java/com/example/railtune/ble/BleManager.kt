package com.example.railtune.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.railtune.model.BleDevice
import com.example.railtune.model.ConnectionState
import com.example.railtune.model.DiscoveredGattCharacteristic
import com.example.railtune.model.DiscoveredGattService
import com.example.railtune.model.LiveDataPacket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
        private const val PREFS_NAME = "railtune_ble_prefs"
        private const val KEY_LAST_ADDRESS = "last_connected_address"
        private const val KEY_LAST_NAME = "last_connected_name"
        private const val KEY_AUTO_CONNECT = "auto_connect_enabled"

        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val DEFAULT_SERVICE_UUID: UUID = UUID.fromString("4fa1c001-0401-4051-b02c-35804611243c")
        val DEFAULT_CHAR_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var activeGatt: BluetoothGatt? = null
    private var isManualDisconnect = false

    private val _discoveredDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<BleDevice>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _liveDataStream = MutableStateFlow<LiveDataPacket?>(null)
    val liveDataStream: StateFlow<LiveDataPacket?> = _liveDataStream.asStateFlow()

    private val _dataHistory = MutableStateFlow<List<LiveDataPacket>>(emptyList())
    val dataHistory: StateFlow<List<LiveDataPacket>> = _dataHistory.asStateFlow()

    private val _discoveredGattServices = MutableStateFlow<List<DiscoveredGattService>>(emptyList())
    val discoveredGattServices: StateFlow<List<DiscoveredGattService>> = _discoveredGattServices.asStateFlow()

    private val _activeCharacteristic = MutableStateFlow<DiscoveredGattCharacteristic?>(null)
    val activeCharacteristic: StateFlow<DiscoveredGattCharacteristic?> = _activeCharacteristic.asStateFlow()

    private val _lastConnectedAddress = MutableStateFlow<String?>(prefs.getString(KEY_LAST_ADDRESS, null))
    val lastConnectedAddress: StateFlow<String?> = _lastConnectedAddress.asStateFlow()

    private val _lastConnectedName = MutableStateFlow<String?>(prefs.getString(KEY_LAST_NAME, null))
    val lastConnectedName: StateFlow<String?> = _lastConnectedName.asStateFlow()

    private val _isAutoConnectEnabled = MutableStateFlow(prefs.getBoolean(KEY_AUTO_CONNECT, true))
    val isAutoConnectEnabled: StateFlow<Boolean> = _isAutoConnectEnabled.asStateFlow()

    var serviceUuidFilter: UUID? = DEFAULT_SERVICE_UUID
    var characteristicUuidFilter: UUID? = DEFAULT_CHAR_UUID

    val isBluetoothEnabled: Boolean
        get() = bluetoothAdapter?.isEnabled == true

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { scanResult ->
                val device = scanResult.device
                val name = device.name ?: scanResult.scanRecord?.deviceName ?: "Unbekanntes ESP32/BLE Gerät"
                val address = device.address
                val rssi = scanResult.rssi

                val newBleDevice = BleDevice(name = name, address = address, rssi = rssi, device = device)

                val currentList = _discoveredDevices.value.toMutableList()
                val existingIndex = currentList.indexOfFirst { it.address == address }
                if (existingIndex >= 0) {
                    currentList[existingIndex] = newBleDevice
                } else {
                    currentList.add(newBleDevice)
                }
                _discoveredDevices.value = currentList.sortedByDescending { it.rssi }

                // Auto-Connect check
                val targetAddress = _lastConnectedAddress.value
                val autoConnect = _isAutoConnectEnabled.value
                if (autoConnect && !targetAddress.isNullOrEmpty() && address.equals(targetAddress, ignoreCase = true)) {
                    if (_connectionState.value is ConnectionState.Scanning || _connectionState.value is ConnectionState.Disconnected) {
                        Log.i(TAG, "Auto-Connect: Gespeichertes Gerät $name ($address) entdeckt! Verbinde automatisch...")
                        connect(newBleDevice)
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE Scan fehlgeschlagen mit Fehlercode: $errorCode")
            _isScanning.value = false
            _connectionState.value = ConnectionState.Error("Scan fehlgeschlagen (Code: $errorCode)")
        }
    }

    fun startScan() {
        if (!isBluetoothEnabled) {
            _connectionState.value = ConnectionState.Error("Bluetooth ist ausgeschaltet")
            return
        }

        stopScan() // Vorherigen Scan sauber beenden

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            _connectionState.value = ConnectionState.Error("BLE Scanner nicht verfügbar")
            return
        }

        _discoveredDevices.value = emptyList()
        _isScanning.value = true
        _connectionState.value = ConnectionState.Scanning

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .build()

        try {
            scanner.startScan(emptyList<ScanFilter>(), scanSettings, scanCallback)
            Log.d(TAG, "BLE Scan mit Low-Latency gestartet")
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Starten des Scans", e)
            _isScanning.value = false
            _connectionState.value = ConnectionState.Error("Scan Startfehler: ${e.localizedMessage}")
        }
    }

    fun stopScan() {
        if (_isScanning.value) {
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (e: Exception) {
                Log.e(TAG, "Fehler beim Stoppen des Scans", e)
            }
            _isScanning.value = false
            if (_connectionState.value is ConnectionState.Scanning) {
                _connectionState.value = ConnectionState.Disconnected
            }
        }
    }

    fun connect(bleDevice: BleDevice) {
        isManualDisconnect = false
        stopScan()
        disconnectInternal()
        _connectionState.value = ConnectionState.Connecting(bleDevice.name)

        try {
            activeGatt = bleDevice.device.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        } catch (e: Exception) {
            Log.e(TAG, "Verbindungsfehler bei GATT Connect", e)
            _connectionState.value = ConnectionState.Error("Verbindung fehlgeschlagen: ${e.localizedMessage}")
        }
    }

    fun disconnect() {
        isManualDisconnect = true
        disconnectInternal()
    }

    private fun disconnectInternal() {
        val gatt = activeGatt
        activeGatt = null
        if (gatt != null) {
            try {
                gatt.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Fehler beim Trennen der GATT-Verbindung", e)
                try { gatt.close() } catch (_: Exception) {}
            }
        }
        _discoveredGattServices.value = emptyList()
        _activeCharacteristic.value = null
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun saveLastConnectedDevice(address: String, name: String) {
        prefs.edit()
            .putString(KEY_LAST_ADDRESS, address)
            .putString(KEY_LAST_NAME, name)
            .apply()
        _lastConnectedAddress.value = address
        _lastConnectedName.value = name
        Log.d(TAG, "Letztes verbundenes Gerät gespeichert: $name ($address)")
    }

    fun forgetLastDevice() {
        prefs.edit()
            .remove(KEY_LAST_ADDRESS)
            .remove(KEY_LAST_NAME)
            .apply()
        _lastConnectedAddress.value = null
        _lastConnectedName.value = null
        Log.d(TAG, "Gespeichertes Gerät gelöscht")
    }

    fun setAutoConnectEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CONNECT, enabled).apply()
        _isAutoConnectEnabled.value = enabled
        Log.d(TAG, "Auto-Connect auf $enabled gesetzt")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (gatt == null) return
            val deviceName = gatt.device.name ?: "ESP32"

            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (gatt === activeGatt) {
                    Log.d(TAG, "GATT Verbunden mit $deviceName. MTU & Dienst-Discovery werden gestartet...")
                    saveLastConnectedDevice(gatt.device.address, deviceName)
                    _connectionState.value = ConnectionState.Connecting("$deviceName (Dienste werden gelesen)")
                    val mtuRequested = gatt.requestMtu(512)
                    if (!mtuRequested) {
                        gatt.discoverServices()
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "GATT Getrennt von $deviceName (Status: $status)")
                try {
                    gatt.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Fehler beim Schließen von GATT", e)
                }

                if (gatt === activeGatt) {
                    activeGatt = null
                    _discoveredGattServices.value = emptyList()
                    _activeCharacteristic.value = null
                    _connectionState.value = ConnectionState.Disconnected
                }

                if (!isManualDisconnect && _isAutoConnectEnabled.value && !_lastConnectedAddress.value.isNullOrEmpty()) {
                    Log.d(TAG, "Automatische Re-Scan-Suche wird gestartet...")
                    startScan()
                }
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "GATT Fehlerstatus $status bei $deviceName")
                try {
                    gatt.close()
                } catch (_: Exception) {}
                if (gatt === activeGatt) {
                    activeGatt = null
                    _connectionState.value = ConnectionState.Error("GATT Fehler (Status: $status)")
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            Log.d(TAG, "MTU geändert auf $mtu (Status: $status)")
            if (gatt === activeGatt) {
                gatt?.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (gatt == null || gatt !== activeGatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val deviceName = gatt.device.name ?: "ESP32"
                Log.d(TAG, "GATT Dienste entdeckt für $deviceName")

                val servicesList = gatt.services.map { service ->
                    DiscoveredGattService(
                        uuid = service.uuid,
                        characteristics = service.characteristics.map { char ->
                            DiscoveredGattCharacteristic(
                                uuid = char.uuid,
                                serviceUuid = service.uuid,
                                isNotifiable = (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0,
                                isIndicatable = (char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0,
                                isReadable = (char.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0,
                                isWritable = (char.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
                            )
                        }
                    )
                }
                _discoveredGattServices.value = servicesList

                _connectionState.value = ConnectionState.Connecting("$deviceName (Datenabo wird aktiviert)")
                subscribeToCharacteristic(gatt)
            } else {
                Log.e(TAG, "Dienst-Discovery fehlgeschlagen mit Status $status")
                _connectionState.value = ConnectionState.Error("Dienste konnten nicht gelesen werden ($status)")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (gatt !== activeGatt || descriptor.uuid != CCCD_UUID) return
            Log.i(TAG, "CCCD callback: device=${gatt.device.address}, descriptor=${descriptor.uuid}, status=$status")

            val deviceName = gatt.device.name ?: "ESP32"
            _connectionState.value = ConnectionState.Connected(deviceName, gatt.device.address)

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "CCCD-Abo Status $status, aber Verbindung bleibt aufrecht")
            } else {
                Log.d(TAG, "BLE-Notifications erfolgreich abonniert")
            }

            val characteristic = descriptor.characteristic
            readCharacteristic(characteristic)
        }

        @Deprecated("Used on Android 12 and below")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (gatt === activeGatt) {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: byteArrayOf()
                processReceivedData(value, characteristic)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (gatt === activeGatt) {
                processReceivedData(value, characteristic)
            }
        }

        @Deprecated("Used on Android 12 and below")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && gatt === activeGatt) {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: byteArrayOf()
                processReceivedData(value, characteristic)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && gatt === activeGatt) {
                processReceivedData(value, characteristic)
            }
        }
    }

    fun selectCharacteristic(serviceUuid: UUID, charUuid: UUID) {
        val gatt = activeGatt ?: return
        val service = gatt.getService(serviceUuid) ?: return
        val characteristic = service.getCharacteristic(charUuid) ?: return
        serviceUuidFilter = serviceUuid
        characteristicUuidFilter = charUuid
        subscribeToCharacteristic(gatt, characteristic)
    }

    fun subscribeToCharacteristic(
        gatt: BluetoothGatt? = null,
        targetChar: BluetoothGattCharacteristic? = null
    ) {
        val targetGatt = gatt ?: activeGatt ?: return
        var charToSubscribe = targetChar

        if (charToSubscribe == null) {
            val targetServiceUuid = serviceUuidFilter
            val targetCharUuid = characteristicUuidFilter

            if (targetServiceUuid != null && targetCharUuid != null) {
                val service = targetGatt.getService(targetServiceUuid)
                charToSubscribe = service?.getCharacteristic(targetCharUuid)
            }
        }

        if (charToSubscribe == null) {
            Log.w(TAG, "Ziel-Service/Charakteristik nicht gefunden. Durchsuche Custom GATT-Dienste...")
            // Pass 1: Custom non-system services (skipping standard 000018xx-0000-1000-8000-00805f9b34fb system services)
            for (service in targetGatt.services) {
                val serviceUuidStr = service.uuid.toString().lowercase()
                if (serviceUuidStr.startsWith("000018")) {
                    continue
                }
                for (char in service.characteristics) {
                    val props = char.properties
                    if ((props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                        (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    ) {
                        charToSubscribe = char
                        Log.d(TAG, "Fallback: Custom-Charakteristik gefunden: ${char.uuid} in Service ${service.uuid}")
                        break
                    }
                }
                if (charToSubscribe != null) break
            }
        }

        if (charToSubscribe == null) {
            Log.w(TAG, "Keine Custom-Charakteristik mit Notify/Indicate. Durchsuche alle Dienste...")
            // Pass 2: Search any readable/notifiable characteristic across all services
            for (service in targetGatt.services) {
                for (char in service.characteristics) {
                    val props = char.properties
                    if ((props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                        (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 ||
                        (props and BluetoothGattCharacteristic.PROPERTY_READ) != 0
                    ) {
                        charToSubscribe = char
                        Log.d(TAG, "Fallback: Allgemeine Charakteristik gefunden: ${char.uuid} in Service ${service.uuid}")
                        break
                    }
                }
                if (charToSubscribe != null) break
            }
        }

        if (charToSubscribe == null) {
            Log.e(TAG, "Keine passende BLE Charakteristik am ESP32 gefunden.")
            _connectionState.value = ConnectionState.Error("Keine Daten-Charakteristik am ESP32 gefunden")
            return
        }

        _activeCharacteristic.value = DiscoveredGattCharacteristic(
            uuid = charToSubscribe.uuid,
            serviceUuid = charToSubscribe.service.uuid,
            isNotifiable = (charToSubscribe.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0,
            isIndicatable = (charToSubscribe.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0,
            isReadable = (charToSubscribe.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0,
            isWritable = (charToSubscribe.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
        )

        val deviceName = targetGatt.device.name ?: "ESP32"
        _connectionState.value = ConnectionState.Connected(deviceName, targetGatt.device.address)

        val supportsNotify = (charToSubscribe.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
        val supportsIndicate = (charToSubscribe.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

        if (supportsNotify || supportsIndicate) {
            val success = targetGatt.setCharacteristicNotification(charToSubscribe, true)
            if (!success) {
                Log.w(TAG, "setCharacteristicNotification meldet false für ${charToSubscribe.uuid}")
            }

            val descriptor = charToSubscribe.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                val payload = if (supportsNotify) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val result = targetGatt.writeDescriptor(descriptor, payload)
                    Log.d(TAG, "CCCD write result: $result")
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = payload
                    @Suppress("DEPRECATION")
                    targetGatt.writeDescriptor(descriptor)
                }
            } else {
                Log.w(TAG, "CCCD Descriptor (0x2902) fehlt an Charakteristik ${charToSubscribe.uuid}")
            }
        }

        if ((charToSubscribe.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
            readCharacteristic(charToSubscribe)
        }
    }

    fun readCharacteristic(characteristic: BluetoothGattCharacteristic? = null) {
        val gatt = activeGatt ?: return
        var charToRead = characteristic
        if (charToRead == null) {
            val activeChar = _activeCharacteristic.value ?: return
            val service = gatt.getService(activeChar.serviceUuid)
            charToRead = service?.getCharacteristic(activeChar.uuid)
        }
        if (charToRead == null) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.readCharacteristic(charToRead)
            } else {
                @Suppress("DEPRECATION")
                gatt.readCharacteristic(charToRead)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Lesen der Charakteristik", e)
        }
    }

    private fun processReceivedData(bytes: ByteArray, characteristic: BluetoothGattCharacteristic? = null) {
        if (bytes.isEmpty()) return
        val textValue = bytes.toString(Charsets.UTF_8).trim()
        val hexValue = bytes.joinToString(" ") { "%02X".format(it) }
        val charUuid = characteristic?.uuid?.toString() ?: _activeCharacteristic.value?.uuid?.toString() ?: ""

        val packet = LiveDataPacket(
            rawBytes = bytes,
            textValue = textValue,
            hexValue = hexValue,
            characteristicUuid = charUuid
        )

        Log.d(TAG, "Livedaten empfangen ($charUuid): '$textValue' [Hex: $hexValue] (${bytes.size} Bytes)")

        _liveDataStream.value = packet

        val currentHistory = _dataHistory.value.toMutableList()
        currentHistory.add(0, packet)
        if (currentHistory.size > 50) {
            currentHistory.removeAt(currentHistory.size - 1)
        }
        _dataHistory.value = currentHistory
    }

    fun clearHistory() {
        _dataHistory.value = emptyList()
        _liveDataStream.value = null
    }
}
