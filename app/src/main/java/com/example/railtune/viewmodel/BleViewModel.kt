package com.example.railtune.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.railtune.ble.BleManager
import com.example.railtune.model.BleDevice
import com.example.railtune.model.ConnectionState
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

    fun setCustomUuids(serviceUuidStr: String, charUuidStr: String) {
        try {
            bleManager.serviceUuidFilter = if (serviceUuidStr.isNotBlank()) UUID.fromString(serviceUuidStr.trim()) else null
            bleManager.characteristicUuidFilter = if (charUuidStr.isNotBlank()) UUID.fromString(charUuidStr.trim()) else null
        } catch (e: Exception) {
            // Ungültige UUIDs ignorieren
        }
    }

    fun clearDataHistory() {
        bleManager.clearHistory()
    }

    override fun onCleared() {
        super.onCleared()
        bleManager.disconnect()
    }
}
