package com.example.railtune.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.railtune.ble.BleManager
import com.example.railtune.model.BleDevice
import com.example.railtune.model.ConnectionState
import com.example.railtune.model.DiscoveredGattCharacteristic
import com.example.railtune.model.DiscoveredGattService
import com.example.railtune.model.LiveDataPacket
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

class BleViewModel(application: Application) : AndroidViewModel(application) {

    val bleManager = BleManager(application.applicationContext)

    val discoveredDevices: StateFlow<List<BleDevice>> = bleManager.discoveredDevices
    val isScanning: StateFlow<Boolean> = bleManager.isScanning
    val connectionState: StateFlow<ConnectionState> = bleManager.connectionState
    val liveDataStream: StateFlow<LiveDataPacket?> = bleManager.liveDataStream
    val dataHistory: StateFlow<List<LiveDataPacket>> = bleManager.dataHistory
    val discoveredGattServices: StateFlow<List<DiscoveredGattService>> = bleManager.discoveredGattServices
    val activeCharacteristic: StateFlow<DiscoveredGattCharacteristic?> = bleManager.activeCharacteristic

    val lastConnectedAddress: StateFlow<String?> = bleManager.lastConnectedAddress
    val lastConnectedName: StateFlow<String?> = bleManager.lastConnectedName
    val isAutoConnectEnabled: StateFlow<Boolean> = bleManager.isAutoConnectEnabled

    fun startScan() {
        bleManager.startScan()
    }

    fun stopScan() {
        bleManager.stopScan()
    }

    fun connectDevice(bleDevice: BleDevice) {
        bleManager.connect(bleDevice)
    }

    fun disconnect() {
        bleManager.disconnect()
    }

    fun forgetLastDevice() {
        bleManager.forgetLastDevice()
    }

    fun setAutoConnectEnabled(enabled: Boolean) {
        bleManager.setAutoConnectEnabled(enabled)
    }

    fun setCustomUuids(serviceUuidStr: String, charUuidStr: String) {
        try {
            val serviceUuid = if (serviceUuidStr.isNotBlank()) UUID.fromString(serviceUuidStr.trim()) else null
            val charUuid = if (charUuidStr.isNotBlank()) UUID.fromString(charUuidStr.trim()) else null

            bleManager.serviceUuidFilter = serviceUuid
            bleManager.characteristicUuidFilter = charUuid

            if (serviceUuid != null && charUuid != null) {
                bleManager.selectCharacteristic(serviceUuid, charUuid)
            }
        } catch (_: Exception) {
            // Ungültige UUIDs ignorieren
        }
    }

    fun selectCharacteristic(serviceUuid: UUID, charUuid: UUID) {
        bleManager.selectCharacteristic(serviceUuid, charUuid)
    }

    fun readCurrentData() {
        bleManager.readCharacteristic()
    }

    fun clearDataHistory() {
        bleManager.clearHistory()
    }

    override fun onCleared() {
        super.onCleared()
        bleManager.disconnect()
    }
}
