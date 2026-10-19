package com.example.railtune.model

import android.bluetooth.BluetoothDevice
import java.util.UUID

data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val device: BluetoothDevice
)

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Scanning : ConnectionState
    data class Connecting(val deviceName: String) : ConnectionState
    data class Connected(val deviceName: String, val address: String) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

data class LiveDataPacket(
    val timestampMs: Long = System.currentTimeMillis(),
    val rawBytes: ByteArray,
    val textValue: String,
    val hexValue: String = rawBytes.joinToString(" ") { "%02X".format(it) },
    val characteristicUuid: String = ""
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as LiveDataPacket

        if (timestampMs != other.timestampMs) return false
        if (!rawBytes.contentEquals(other.rawBytes)) return false
        if (textValue != other.textValue) return false
        if (hexValue != other.hexValue) return false
        if (characteristicUuid != other.characteristicUuid) return false

        return true
    }

    override fun hashCode(): Int {
        var result = timestampMs.hashCode()
        result = 31 * result + rawBytes.contentHashCode()
        result = 31 * result + textValue.hashCode()
        result = 31 * result + hexValue.hashCode()
        result = 31 * result + characteristicUuid.hashCode()
        return result
    }
}

data class DiscoveredGattService(
    val uuid: UUID,
    val characteristics: List<DiscoveredGattCharacteristic>
)

data class DiscoveredGattCharacteristic(
    val uuid: UUID,
    val serviceUuid: UUID,
    val isNotifiable: Boolean,
    val isIndicatable: Boolean,
    val isReadable: Boolean,
    val isWritable: Boolean
)
