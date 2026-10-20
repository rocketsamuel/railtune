package com.example.railtune.z21

import android.content.Context
import android.util.Log
import com.example.railtune.model.CvProgrammingMode
import com.example.railtune.model.CvResult
import com.example.railtune.model.Loco
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID

class Z21Manager(private val context: Context) {

    companion object {
        private const val TAG = "Z21Manager"
        private const val DEFAULT_Z21_IP = "192.168.178.223"
        private const val Z21_PORT = 21105
        private const val PREFS_NAME = "railtune_z21_prefs"
        private const val KEY_LOCO_DATA = "saved_locos_data"
        private const val KEY_Z21_IP = "z21_ip_address"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _z21Ip = MutableStateFlow(prefs.getString(KEY_Z21_IP, DEFAULT_Z21_IP) ?: DEFAULT_Z21_IP)
    val z21Ip: StateFlow<String> = _z21Ip.asStateFlow()

    private val _z21Status = MutableStateFlow("Bereit (${_z21Ip.value})")
    val z21Status: StateFlow<String> = _z21Status.asStateFlow()

    private val _cvResult = MutableStateFlow<CvResult?>(null)
    val cvResult: StateFlow<CvResult?> = _cvResult.asStateFlow()

    private val _isCvBusy = MutableStateFlow(false)
    val isCvBusy: StateFlow<Boolean> = _isCvBusy.asStateFlow()

    private val _savedLocos = MutableStateFlow<List<Loco>>(emptyList())
    val savedLocos: StateFlow<List<Loco>> = _savedLocos.asStateFlow()

    private val _railComSpeedsKmH = MutableStateFlow<Map<Int, Float>>(emptyMap())
    val railComSpeedsKmH: StateFlow<Map<Int, Float>> = _railComSpeedsKmH.asStateFlow()

    private var udpSocket: DatagramSocket? = null
    private var receiveJob: Job? = null
    private var broadcastSubscriptionJob: Job? = null

    init {
        loadSavedLocos()
        initUdpSocket()
    }

    fun setZ21Ip(ip: String) {
        val trimmed = ip.trim()
        if (trimmed.isNotBlank()) {
            _z21Ip.value = trimmed
            prefs.edit().putString(KEY_Z21_IP, trimmed).apply()
            _z21Status.value = "Bereit ($trimmed)"
            pingZ21()
            subscribeBroadcastFlags()
        }
    }

    private fun initUdpSocket() {
        scope.launch {
            try {
                udpSocket?.close()
                udpSocket = DatagramSocket()
                startListening()
                pingZ21()
                subscribeBroadcastFlags()
                startBroadcastKeepAlive()
            } catch (e: Exception) {
                Log.e(TAG, "Fehler beim Initialisieren des UDP Sockets", e)
                _z21Status.value = "UDP Fehler: ${e.localizedMessage}"
            }
        }
    }

    fun pingZ21() {
        scope.launch {
            _z21Status.value = "Sende Ping an z21 (${_z21Ip.value})..."
            // LAN_GET_SERIAL_NUMBER: 0x08 0x00 0x10 0x00
            val packetData = byteArrayOf(0x08.toByte(), 0x00.toByte(), 0x10.toByte(), 0x00.toByte())
            sendUdpPacket(packetData)
        }
    }

    fun subscribeBroadcastFlags() {
        scope.launch {
            // LAN_SET_BROADCAST_FLAGS: 0x08 0x00 0x50 0x00 0x0D 0x01 0x01 0x00
            val packetData = byteArrayOf(
                0x08.toByte(), 0x00.toByte(),
                0x50.toByte(), 0x00.toByte(),
                0x0D.toByte(), 0x01.toByte(), 0x01.toByte(), 0x00.toByte()
            )
            sendUdpPacket(packetData)
            Log.d(TAG, "z21 Broadcast Flags abonniert!")
        }
    }

    private fun startBroadcastKeepAlive() {
        broadcastSubscriptionJob?.cancel()
        broadcastSubscriptionJob = scope.launch {
            while (isActive) {
                delay(2000)
                subscribeBroadcastFlags()
            }
        }
    }

    fun getLocoInfo(locoAddress: Int) {
        scope.launch {
            val addrHigh = if (locoAddress > 127) ((locoAddress shr 8) or 0xC0) else 0x00
            val addrLow = if (locoAddress > 127) (locoAddress and 0xFF) else locoAddress

            // LAN_X_GET_LOCO_INFO (0x09 0x00 0x40 0x00 0xE3 0xF0 addrHigh addrLow XOR)
            val packetData = byteArrayOf(
                0x09.toByte(), 0x00.toByte(),
                0x40.toByte(), 0x00.toByte(),
                0xE3.toByte(), 0xF0.toByte(),
                addrHigh.toByte(),
                addrLow.toByte(),
                0x00.toByte()
            )

            var xor = 0
            for (i in 4..7) {
                xor = xor xor (packetData[i].toInt() and 0xFF)
            }
            packetData[8] = xor.toByte()

            sendUdpPacket(packetData)
        }
    }

    private fun startListening() {
        receiveJob?.cancel()
        receiveJob = scope.launch {
            val buffer = ByteArray(512)
            val packet = DatagramPacket(buffer, buffer.size)

            while (isActive) {
                try {
                    val socket = udpSocket ?: break
                    socket.receive(packet)
                    val receivedBytes = packet.data.copyOf(packet.length)
                    parseZ21Packet(receivedBytes)
                } catch (e: Exception) {
                    if (!isActive) break
                    Log.w(TAG, "UDP Empfangsfehler", e)
                    delay(200)
                }
            }
        }
    }

    private fun parseZ21Packet(bytes: ByteArray) {
        if (bytes.size < 4) return
        val length = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
        val headerId = (bytes[2].toInt() and 0xFF) or ((bytes[3].toInt() and 0xFF) shl 8)

        Log.d(TAG, "Z21 UDP Paket empfangen: Len=$length, Header=0x${headerId.toString(16)} Hex: ${bytes.joinToString(" ") { "%02X".format(it) }}")

        // LAN_GET_SERIAL_NUMBER Response (0x0010)
        if (headerId == 0x0010 && bytes.size >= 8) {
            val serial = (bytes[4].toInt() and 0xFF) or
                    ((bytes[5].toInt() and 0xFF) shl 8) or
                    ((bytes[6].toInt() and 0xFF) shl 16) or
                    ((bytes[7].toInt() and 0xFF) shl 24)
            Log.d(TAG, "z21 Seriennummer empfangen: $serial")
            _z21Status.value = "Verbunden mit z21 (SN: $serial)"
            return
        }

        // LAN_RAILCOM_DATACHANGED Response (0x0088)
        if (headerId == 0x0088 && length >= 17 && bytes.size >= 17) {
            val locoAddr = (bytes[4].toInt() and 0xFF) or ((bytes[5].toInt() and 0xFF) shl 8)
            val options = bytes[13].toInt() and 0xFF
            if ((options and 0x03) == 0) return

            val rawSpeed = bytes[14].toInt() and 0xFF

            val targetLoco = _savedLocos.value.find { it.address == locoAddr }
            val maxSpd = (targetLoco?.maxSpeedKmH ?: 160).toFloat()
            val speedKmH = (rawSpeed.coerceIn(0, 127) / 127f) * maxSpd

            Log.d(TAG, "RailCom Lok $locoAddr: speed=$rawSpeed -> $speedKmH km/h")
            _railComSpeedsKmH.value = _railComSpeedsKmH.value + (locoAddr to speedKmH)
            return
        }

        // X-Header check (0x0040 = LAN_X_HEADER)
        if (headerId == 0x0040 && bytes.size >= 5) {
            val xHeader = bytes[4].toInt() and 0xFF

            // LAN_X_CV_RESULT (0x64 0x14)
            if (xHeader == 0x64 && bytes.size >= 9) {
                val cvHigh = bytes[5].toInt() and 0xFF
                val cvLow = bytes[6].toInt() and 0xFF
                val cvValue = bytes[7].toInt() and 0xFF
                val cvNum = ((cvHigh shl 8) or cvLow) + 1

                _isCvBusy.value = false
                _cvResult.value = CvResult(
                    cvNumber = cvNum,
                    cvValue = cvValue,
                    isSuccess = true,
                    message = "CV $cvNum erfolgreich gelesen: $cvValue"
                )
                _z21Status.value = "CV $cvNum gelesen: $cvValue"
            } else if (xHeader == 0x61) {
                // LAN_X_CV_NACK (Fehler beim CV-Lesen/Schreiben)
                val errCode = bytes[5].toInt() and 0xFF
                _isCvBusy.value = false
                val msg = if (errCode == 0x12) "Kein Decoder / Kurzschluss" else "CV Lesen fehlgeschlagen (NACK $errCode)"
                _cvResult.value = CvResult(
                    cvNumber = _cvResult.value?.cvNumber ?: 0,
                    cvValue = 0,
                    isSuccess = false,
                    message = msg
                )
                _z21Status.value = msg
            }
        }
    }

    fun readCv(cvNumber: Int, mode: CvProgrammingMode = CvProgrammingMode.DIRECT_MODE) {
        if (cvNumber < 1 || cvNumber > 1024) {
            _cvResult.value = CvResult(cvNumber, 0, isSuccess = false, message = "Ungültige CV Nummer")
            return
        }

        _isCvBusy.value = true
        _z21Status.value = "Lese CV $cvNumber über z21..."

        scope.launch {
            val cvZeroBased = cvNumber - 1
            val cvHigh = (cvZeroBased shr 8) and 0xFF
            val cvLow = cvZeroBased and 0xFF

            // LAN_X_CV_READ (0x09 0x00 0x40 0x00 0x23 0x11 cvHigh cvLow XOR)
            val packetData = byteArrayOf(
                0x09.toByte(), 0x00.toByte(), // Length = 9
                0x40.toByte(), 0x00.toByte(), // Header = 0x0040
                0x23.toByte(), 0x11.toByte(), // X-Header = LAN_X_CV_READ
                cvHigh.toByte(),
                cvLow.toByte(),
                0x00.toByte() // Placeholder for XOR
            )

            var xor = 0
            for (i in 4..7) {
                xor = xor xor (packetData[i].toInt() and 0xFF)
            }
            packetData[8] = xor.toByte()

            sendUdpPacket(packetData)

            delay(5000)
            if (_isCvBusy.value) {
                _isCvBusy.value = false
                _z21Status.value = "Timeout beim Lesen von CV $cvNumber"
                _cvResult.value = CvResult(cvNumber, 0, isSuccess = false, message = "z21 Antwort-Timeout")
            }
        }
    }

    fun writeCv(cvNumber: Int, value: Int, mode: CvProgrammingMode = CvProgrammingMode.DIRECT_MODE) {
        if (cvNumber < 1 || cvNumber > 1024 || value < 0 || value > 255) {
            _cvResult.value = CvResult(cvNumber, value, isSuccess = false, message = "Ungültige CV/Wert Parameter")
            return
        }

        _isCvBusy.value = true
        _z21Status.value = "Schreibe CV $cvNumber = $value..."

        scope.launch {
            val cvZeroBased = cvNumber - 1
            val cvHigh = (cvZeroBased shr 8) and 0xFF
            val cvLow = cvZeroBased and 0xFF

            // LAN_X_CV_WRITE (0x0A 0x00 0x40 0x00 0x23 0x12 cvHigh cvLow value XOR)
            val packetData = byteArrayOf(
                0x0A.toByte(), 0x00.toByte(), // Length = 10
                0x40.toByte(), 0x00.toByte(), // Header = 0x0040
                0x23.toByte(), 0x12.toByte(), // X-Header = LAN_X_CV_WRITE
                cvHigh.toByte(),
                cvLow.toByte(),
                (value and 0xFF).toByte(),
                0x00.toByte() // XOR
            )

            var xor = 0
            for (i in 4..8) {
                xor = xor xor (packetData[i].toInt() and 0xFF)
            }
            packetData[9] = xor.toByte()

            sendUdpPacket(packetData)

            delay(3000)
            _isCvBusy.value = false
            _z21Status.value = "CV $cvNumber = $value geschrieben"
            _cvResult.value = CvResult(cvNumber, value, isSuccess = true, message = "CV $cvNumber = $value gesendet")
        }
    }

    fun setLocoSpeed(locoAddress: Int, speedPercent: Float, isForward: Boolean) {
        scope.launch {
            val speedSteps128 = when {
                speedPercent <= 0f -> 0
                else -> (2 + (speedPercent / 100f * 125f)).toInt().coerceIn(2, 127)
            }

            val speedByte = (if (isForward) 0x80 else 0x00) or (speedSteps128 and 0x7F)

            val addrHigh = if (locoAddress > 127) ((locoAddress shr 8) or 0xC0) else 0x00
            val addrLow = if (locoAddress > 127) (locoAddress and 0xFF) else locoAddress

            // LAN_X_SET_LOCO_DRIVE (0x0A 0x00 0x40 0x00 0xE4 0x13 addrHigh addrLow speedByte XOR)
            val packetData = byteArrayOf(
                0x0A.toByte(), 0x00.toByte(),
                0x40.toByte(), 0x00.toByte(),
                0xE4.toByte(), 0x13.toByte(), // 128 Fahrstufen
                addrHigh.toByte(),
                addrLow.toByte(),
                speedByte.toByte(),
                0x00.toByte()
            )

            var xor = 0
            for (i in 4..8) {
                xor = xor xor (packetData[i].toInt() and 0xFF)
            }
            packetData[9] = xor.toByte()

            sendUdpPacket(packetData)
            getLocoInfo(locoAddress)
        }
    }

    fun setLocoFunction(locoAddress: Int, functionNum: Int, state: Boolean) {
        scope.launch {
            val addrHigh = if (locoAddress > 127) ((locoAddress shr 8) or 0xC0) else 0x00
            val addrLow = if (locoAddress > 127) (locoAddress and 0xFF) else locoAddress
            val stateByte = if (state) 0x01 else 0x00

            // LAN_X_SET_LOCO_FUNCTION (0x0A 0x00 0x40 0x00 0xE4 0xF8 addrHigh addrLow funcByte XOR)
            val packetData = byteArrayOf(
                0x0A.toByte(), 0x00.toByte(),
                0x40.toByte(), 0x00.toByte(),
                0xE4.toByte(), 0xF8.toByte(),
                addrHigh.toByte(),
                addrLow.toByte(),
                (functionNum and 0x3F or (stateByte shl 6)).toByte(),
                0x00.toByte()
            )

            var xor = 0
            for (i in 4..8) {
                xor = xor xor (packetData[i].toInt() and 0xFF)
            }
            packetData[9] = xor.toByte()

            sendUdpPacket(packetData)
        }
    }

    fun setTrackPower(on: Boolean) {
        scope.launch {
            val packetData = if (on) {
                byteArrayOf(0x07, 0x00, 0x40, 0x00, 0x21, 0x81.toByte(), 0xA0.toByte())
            } else {
                byteArrayOf(0x07, 0x00, 0x40, 0x00, 0x21, 0x80.toByte(), 0xA1.toByte())
            }
            sendUdpPacket(packetData)
            _z21Status.value = if (on) "Gleisspannung EIN" else "Gleisspannung AUS / NOT-AUS"
        }
    }

    private fun sendUdpPacket(bytes: ByteArray) {
        try {
            val address = InetAddress.getByName(_z21Ip.value)
            val packet = DatagramPacket(bytes, bytes.size, address, Z21_PORT)
            udpSocket?.send(packet)
            Log.d(TAG, "z21 UDP Paket gesendet an ${_z21Ip.value}:$Z21_PORT -> ${bytes.joinToString(" ") { "%02X".format(it) }}")
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Senden des UDP Pakets an z21", e)
            _z21Status.value = "Sende-Fehler: ${e.localizedMessage}"
        }
    }

    // Loco Storage Management
    fun addLoco(
        name: String,
        address: Int,
        maxSpeedKmH: Int = 160,
        decoderManufacturer: String = ""
    ): Loco {
        val newLoco = Loco(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "Lok $address" },
            address = address.coerceIn(1, 9999),
            maxSpeedKmH = maxSpeedKmH,
            decoderManufacturer = decoderManufacturer
        )
        val list = _savedLocos.value.toMutableList()
        list.add(0, newLoco)
        saveLocosToPrefs(list)
        return newLoco
    }

    fun removeLoco(locoId: String) {
        val list = _savedLocos.value.filter { it.id != locoId }
        saveLocosToPrefs(list)
    }

    fun updateLoco(loco: Loco) {
        val list = _savedLocos.value.map { existing ->
            if (existing.id == loco.id) {
                loco.copy(
                    name = loco.name.ifBlank { "Lok ${loco.address}" },
                    address = loco.address.coerceIn(1, 9999),
                    maxSpeedKmH = loco.maxSpeedKmH.coerceAtLeast(1)
                )
            } else {
                existing
            }
        }
        saveLocosToPrefs(list)
    }

    private fun saveLocosToPrefs(locos: List<Loco>) {
        _savedLocos.value = locos
        val serialized = locos.joinToString(";") {
            "${it.id}|${it.name}|${it.address}|${it.maxSpeedKmH}|${it.category}|${it.decoderManufacturer}"
        }
        prefs.edit().putString(KEY_LOCO_DATA, serialized).apply()
    }

    private fun loadSavedLocos() {
        val raw = prefs.getString(KEY_LOCO_DATA, null)
        if (raw.isNullOrEmpty()) {
            val defaultLocos = listOf(
                Loco(name = "BR 101", address = 3, maxSpeedKmH = 220, category = "Elektro"),
                Loco(name = "ICE 3", address = 8, maxSpeedKmH = 330, category = "Triebwagen"),
                Loco(name = "BR 218", address = 12, maxSpeedKmH = 140, category = "Diesel")
            )
            saveLocosToPrefs(defaultLocos)
        } else {
            try {
                val parsed = raw.split(";").mapNotNull { entry ->
                    val parts = entry.split("|")
                    if (parts.size >= 5) {
                        Loco(
                            id = parts[0],
                            name = parts[1],
                            address = parts[2].toIntOrNull() ?: 3,
                            maxSpeedKmH = parts[3].toIntOrNull() ?: 160,
                            category = parts[4],
                            decoderManufacturer = parts.getOrNull(5).orEmpty()
                        )
                    } else null
                }
                _savedLocos.value = parsed
            } catch (e: Exception) {
                Log.e(TAG, "Fehler beim Laden gespeicherter Loks", e)
            }
        }
    }
}
