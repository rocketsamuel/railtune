package com.example.railtune.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.railtune.model.BleDevice
import com.example.railtune.model.ConnectionState
import com.example.railtune.model.LiveDataPacket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        val DEFAULT_SERVICE_UUID: UUID = UUID.fromString("4fa1c001-0401-4051-b02c-35804611243c")
        val DEFAULT_CHAR_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var activeGatt: BluetoothGatt? = null

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

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            _connectionState.value = ConnectionState.Error("BLE Scanner nicht verfügbar")
            return
        }

        _discoveredDevices.value = emptyList()
        _isScanning.value = true
        _connectionState.value = ConnectionState.Scanning

        try {
            scanner.startScan(scanCallback)
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
        stopScan()
        _connectionState.value = ConnectionState.Connecting(bleDevice.name)

        disconnect()

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
        activeGatt?.let { gatt ->
            try {
                gatt.disconnect()
                gatt.close()
            } catch (e: Exception) {
                Log.e(TAG, "Fehler beim Trennen von GATT", e)
            }
        }
        activeGatt = null
        _connectionState.value = ConnectionState.Disconnected
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            val deviceName = gatt?.device?.name ?: "ESP32"
            val address = gatt?.device?.address ?: ""

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "GATT Verbunden mit $deviceName. Dienst-Discovery wird gestartet...")
                    _connectionState.value = ConnectionState.Connecting("$deviceName (Dienste werden gelesen)")
                    gatt?.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d(TAG, "GATT Getrennt von $deviceName")
                    _connectionState.value = ConnectionState.Disconnected
                    gatt?.close()
                    activeGatt = null
                }
            } else {
                Log.e(TAG, "GATT Fehlerstatus $status bei $deviceName")
                _connectionState.value = ConnectionState.Error("GATT Fehler (Status: $status)")
                gatt?.close()
                activeGatt = null
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                val deviceName = gatt.device.name ?: "ESP32"
                val address = gatt.device.address
                Log.d(TAG, "GATT Dienste entdeckt für $deviceName")

                _connectionState.value = ConnectionState.Connected(deviceName, address)

                subscribeToCharacteristic(gatt)
            } else {
                Log.e(TAG, "Dienst-Discovery fehlgeschlagen mit Status $status")
                _connectionState.value = ConnectionState.Error("Dienste konnten nicht gelesen werden ($status)")
            }
        }

        @Deprecated("Used on Android 12 and below")
        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            characteristic?.let { char ->
                @Suppress("DEPRECATION")
                val value = char.value ?: byteArrayOf()
                processReceivedData(value)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            processReceivedData(value)
        }
    }

    private fun subscribeToCharacteristic(gatt: BluetoothGatt) {
        var targetChar: BluetoothGattCharacteristic? = null

        val targetServiceUuid = serviceUuidFilter
        val targetCharUuid = characteristicUuidFilter

        if (targetServiceUuid != null && targetCharUuid != null) {
            val service = gatt.getService(targetServiceUuid)
            targetChar = service?.getCharacteristic(targetCharUuid)
        }

        if (targetChar == null) {
            Log.w(TAG, "Ziel-Service/Charakteristik nicht gefunden. Durchsuche alle gefundenen GATT-Dienste...")
            for (service in gatt.services) {
                for (char in service.characteristics) {
                    val props = char.properties
                    if ((props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                        (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    ) {
                        targetChar = char
                        Log.d(TAG, "Fallback-Charakteristik gefunden: ${char.uuid} in Service ${service.uuid}")
                        break
                    }
                }
                if (targetChar != null) break
            }
        }

        if (targetChar == null) {
            Log.e(TAG, "Keine benachrichtigungsfähige BLE Charakteristik am ESP32 gefunden.")
            _connectionState.value = ConnectionState.Error("Keine Daten-Charakteristik am ESP32 gefunden")
            return
        }

        val success = gatt.setCharacteristicNotification(targetChar, true)
        if (!success) {
            Log.e(TAG, "setCharacteristicNotification fehlgeschlagen für ${targetChar.uuid}")
            return
        }

        val descriptor = targetChar.getDescriptor(CCCD_UUID)
        if (descriptor != null) {
            val isIndicate = (targetChar.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            val payload = if (isIndicate) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, payload)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = payload
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
            Log.d(TAG, "Subscribed auf Livedaten-Notifications für UUID: ${targetChar.uuid}")
        } else {
            Log.w(TAG, "CCCD Descriptor 2902 nicht vorhanden an Charakteristik ${targetChar.uuid}")
        }
    }

    private fun processReceivedData(bytes: ByteArray) {
        val textValue = bytes.toString(Charsets.UTF_8).trim()
        val packet = LiveDataPacket(
            rawBytes = bytes,
            textValue = textValue
        )

        Log.d(TAG, "Livedaten empfangen: '$textValue' (${bytes.size} Bytes)")

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
