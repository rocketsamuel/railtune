package com.example.railtune.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.railtune.ble.BleManager
import com.example.railtune.model.BleDevice
import com.example.railtune.model.ConnectionState
import com.example.railtune.model.CvResult
import com.example.railtune.model.DiscoveredGattCharacteristic
import com.example.railtune.model.DiscoveredGattService
import com.example.railtune.model.LiveDataPacket
import com.example.railtune.model.Loco
import com.example.railtune.z21.Z21Manager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.UUID

class BleViewModel(application: Application) : AndroidViewModel(application) {

    val bleManager = BleManager(application.applicationContext)
    val z21Manager = Z21Manager(application.applicationContext)

    val discoveredDevices: StateFlow<List<BleDevice>> = bleManager.discoveredDevices
    val isScanning: StateFlow<Boolean> = bleManager.isScanning
    val connectionState: StateFlow<ConnectionState> = bleManager.connectionState

    // Kombiniert BLE Sensordaten und z21 UDP RailCom Daten
    val liveDataStream: StateFlow<LiveDataPacket?> = combine(
        bleManager.liveDataStream,
        z21Manager.z21LiveDataStream
    ) { blePacket, z21Packet ->
        z21Packet ?: blePacket
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val dataHistory: StateFlow<List<LiveDataPacket>> = bleManager.dataHistory
    val discoveredGattServices: StateFlow<List<DiscoveredGattService>> = bleManager.discoveredGattServices
    val activeCharacteristic: StateFlow<DiscoveredGattCharacteristic?> = bleManager.activeCharacteristic

    val lastConnectedAddress: StateFlow<String?> = bleManager.lastConnectedAddress
    val lastConnectedName: StateFlow<String?> = bleManager.lastConnectedName
    val isAutoConnectEnabled: StateFlow<Boolean> = bleManager.isAutoConnectEnabled

    // z21 & CV States
    val z21Ip: StateFlow<String> = z21Manager.z21Ip
    val z21Status: StateFlow<String> = z21Manager.z21Status
    val cvResult: StateFlow<CvResult?> = z21Manager.cvResult
    val isCvBusy: StateFlow<Boolean> = z21Manager.isCvBusy
    val savedLocos: StateFlow<List<Loco>> = z21Manager.savedLocos

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

    fun sendCommand(command: String): Boolean {
        return bleManager.writeText(command)
    }

    fun clearDataHistory() {
        bleManager.clearHistory()
    }

    // z21 & CV Functions
    fun setZ21Ip(ip: String) {
        z21Manager.setZ21Ip(ip)
    }

    fun readCv(cvNum: Int) {
        z21Manager.readCv(cvNum)
    }

    fun writeCv(cvNum: Int, value: Int) {
        z21Manager.writeCv(cvNum, value)
    }

    fun addLoco(name: String, address: Int, maxSpeedKmH: Int = 160, category: String = "Elektro"): Loco {
        return z21Manager.addLoco(name, address, maxSpeedKmH, category)
    }

    fun removeLoco(locoId: String) {
        z21Manager.removeLoco(locoId)
    }

    fun getLocoInfo(locoAddress: Int) {
        z21Manager.getLocoInfo(locoAddress)
    }

    fun setLocoSpeed(locoAddress: Int, speedPercent: Float, isForward: Boolean) {
        z21Manager.setLocoSpeed(locoAddress, speedPercent, isForward)
        if (connectionState.value is ConnectionState.Connected) {
            bleManager.writeText("LOCO:$locoAddress;SPD:${speedPercent.toInt()};DIR:${if (isForward) "FWD" else "REV"}")
        }
    }

    fun setLocoFunction(locoAddress: Int, functionNum: Int, state: Boolean) {
        z21Manager.setLocoFunction(locoAddress, functionNum, state)
        if (connectionState.value is ConnectionState.Connected) {
            bleManager.writeText("LOCO:$locoAddress;FUNC:F$functionNum:${if (state) "ON" else "OFF"}")
        }
    }

    fun setTrackPower(on: Boolean) {
        z21Manager.setTrackPower(on)
        if (connectionState.value is ConnectionState.Connected) {
            bleManager.writeText("POWER:${if (on) "ON" else "OFF"}")
        }
    }

    override fun onCleared() {
        super.onCleared()
        bleManager.disconnect()
    }
}
