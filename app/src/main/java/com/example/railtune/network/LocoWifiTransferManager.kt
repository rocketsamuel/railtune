package com.example.railtune.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.railtune.model.Loco
import com.example.railtune.model.LocoTransfer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID

private const val LOCO_TRANSFER_SERVICE_TYPE = "_railtune._tcp."
private const val LOCO_TRANSFER_PORT = 43871
private const val MAX_TRANSFER_BYTES = 1_000_000

data class WifiTransferDevice(
    val serviceName: String,
    val host: InetAddress,
    val port: Int
) {
    val displayName: String
        get() = serviceName.removePrefix("RailTune-").replace('-', ' ')
}

data class IncomingWifiTransfer internal constructor(
    val id: String,
    val senderName: String,
    val locos: List<Loco>,
    internal val answer: CompletableDeferred<Boolean>
)

class LocoWifiTransferManager(context: Context) {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val localServiceName = "RailTune-${Build.MODEL.orEmpty().replace(Regex("[^A-Za-z0-9_-]"), "-").take(32)}"

    var availableDevices by mutableStateOf<List<WifiTransferDevice>>(emptyList())
        private set
    var incomingTransfer by mutableStateOf<IncomingWifiTransfer?>(null)
        private set

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registeredServiceName: String? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        serverJob = scope.launch {
            try {
                val server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(LOCO_TRANSFER_PORT))
                }
                serverSocket = server
                registerService()
                discoverServices()
                while (true) {
                    val socket = server.accept()
                    launch { handleIncoming(socket) }
                }
            } catch (_: Exception) {
                if (started) stop()
            }
        }
    }

    suspend fun sendLocos(device: WifiTransferDevice, locos: List<Loco>): Boolean {
        val payload = LocoTransfer.encode(locos).toByteArray(StandardCharsets.UTF_8)
        require(payload.size <= MAX_TRANSFER_BYTES) { "Der Fahrzeugexport ist zu groß." }
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(device.host, device.port), 8_000)
                socket.soTimeout = 75_000
                val output = DataOutputStream(socket.getOutputStream())
                output.writeUTF(localServiceName)
                output.writeInt(payload.size)
                output.write(payload)
                output.flush()
                DataInputStream(socket.getInputStream()).readBoolean()
            }
        }
    }

    fun answerIncoming(id: String, accepted: Boolean) {
        incomingTransfer?.takeIf { it.id == id }?.let { request ->
            request.answer.complete(accepted)
            incomingTransfer = null
        }
    }

    fun stop() {
        if (!started) return
        started = false
        incomingTransfer?.answer?.complete(false)
        incomingTransfer = null
        serverSocket?.close()
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
        discoveryListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        discoveryListener = null
        registrationListener?.let { runCatching { nsdManager.unregisterService(it) } }
        registrationListener = null
        registeredServiceName = null
        availableDevices = emptyList()
    }

    private fun registerService() {
        val info = NsdServiceInfo().apply {
            serviceName = localServiceName
            serviceType = LOCO_TRANSFER_SERVICE_TYPE
            port = LOCO_TRANSFER_PORT
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                registeredServiceName = serviceInfo.serviceName
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        runCatching { nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private fun discoverServices() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.trimEnd('.') != LOCO_TRANSFER_SERVICE_TYPE.trimEnd('.') ||
                    serviceInfo.serviceName == registeredServiceName ||
                    serviceInfo.serviceName == localServiceName
                ) return

                runCatching {
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host ?: return
                            val device = WifiTransferDevice(info.serviceName, host, info.port)
                            availableDevices = (availableDevices.filterNot { it.serviceName == info.serviceName } + device)
                                .sortedBy { it.displayName.lowercase() }
                        }
                    })
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                availableDevices = availableDevices.filterNot { it.serviceName == serviceInfo.serviceName }
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                runCatching { nsdManager.stopServiceDiscovery(this) }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                runCatching { nsdManager.stopServiceDiscovery(this) }
            }
        }
        discoveryListener = listener
        runCatching { nsdManager.discoverServices(LOCO_TRANSFER_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private suspend fun handleIncoming(socket: Socket) {
        socket.use { connection ->
            runCatching {
                connection.soTimeout = 15_000
                val input = DataInputStream(connection.getInputStream())
                val senderName = input.readUTF().take(80)
                val size = input.readInt()
                require(size in 1..MAX_TRANSFER_BYTES) { "Ungültige Übertragungsgröße." }
                val payload = ByteArray(size)
                input.readFully(payload)
                val locos = LocoTransfer.decode(String(payload, StandardCharsets.UTF_8))
                val pending = IncomingWifiTransfer(UUID.randomUUID().toString(), senderName, locos, CompletableDeferred())
                if (incomingTransfer != null) {
                    DataOutputStream(connection.getOutputStream()).apply { writeBoolean(false); flush() }
                    return
                }
                incomingTransfer = pending
                val accepted = withTimeoutOrNull(60_000) { pending.answer.await() } ?: false
                if (incomingTransfer?.id == pending.id) incomingTransfer = null
                DataOutputStream(connection.getOutputStream()).apply { writeBoolean(accepted); flush() }
            }
        }
    }
}
