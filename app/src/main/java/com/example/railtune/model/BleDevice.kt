package com.example.railtune.model

import android.bluetooth.BluetoothDevice

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
    val textValue: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as LiveDataPacket

        if (timestampMs != other.timestampMs) return false
        if (!rawBytes.contentEquals(other.rawBytes)) return false
        if (textValue != other.textValue) return false

        return true
    }

    override fun hashCode(): Int {
        var result = timestampMs.hashCode()
        result = 31 * result + rawBytes.contentHashCode()
        result = 31 * result + textValue.hashCode()
        return result
    }
}
