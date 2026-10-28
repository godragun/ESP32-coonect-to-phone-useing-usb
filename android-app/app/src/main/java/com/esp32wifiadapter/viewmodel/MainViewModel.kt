package com.esp32wifiadapter.viewmodel

import android.app.Application
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.VpnService
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.esp32wifiadapter.protocol.Cmd
import com.esp32wifiadapter.protocol.FirmwareEvent
import com.esp32wifiadapter.protocol.Slip
import com.esp32wifiadapter.protocol.WifiNetwork
import com.esp32wifiadapter.protocol.parseEvent
import com.esp32wifiadapter.usb.UsbSerialManager
import com.esp32wifiadapter.vpn.Esp32VpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "MainViewModel"

// ─── UI State ─────────────────────────────────────────────────────────────────
data class UiState(
    val usbStatus:        UsbStatus         = UsbStatus.Disconnected,
    val scanStatus:       ScanStatus        = ScanStatus.Idle,
    val networks:         List<WifiNetwork> = emptyList(),
    val connectionState:  ConnectionState   = ConnectionState.Idle,
    val connectedSsid:    String            = "",
    val connectedIp:      String            = "",
    val internetReady:    Boolean           = false,
    val vpnActive:        Boolean           = false,
    val signalRssi:       Int               = 0,
    val freeHeap:         Long              = 0L,
    val fwVersion:        String            = "",
    val pendingVpnIntent: Intent?           = null,
    val errorMessage:     String?           = null
)

enum class UsbStatus   { Disconnected, Connecting, Connected, Error }
enum class ScanStatus  { Idle, Scanning, Done, Error }
enum class ConnectionState {
    Idle, Connecting, Associated, GettingIp, Online,
    AuthFail, NoAp, Disconnected, UsbError
}

fun String.toConnectionState() = when (this) {
    "connecting"   -> ConnectionState.Connecting
    "associated"   -> ConnectionState.Associated
    "got_ip"       -> ConnectionState.GettingIp
    "online"       -> ConnectionState.Online
    "auth_fail"    -> ConnectionState.AuthFail
    "no_ap"        -> ConnectionState.NoAp
    "disconnected" -> ConnectionState.Disconnected
    else           -> ConnectionState.Idle
}

// ─── ViewModel ────────────────────────────────────────────────────────────────
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val usbSerial = UsbSerialManager(app)
    private var eventLoopJob: Job? = null
    private var usbPollingJob: Job? = null

    init {
        startUsbPolling()
    }

    // ── USB device polling ────────────────────────────────────────────────────
    private fun startUsbPolling() {
        usbPollingJob = viewModelScope.launch {
            while (isActive) {
                val device = usbSerial.findCompatibleDevice()
                if (device != null && !usbSerial.isOpen) {
                    connectUsb(device)
                } else if (device == null && usbSerial.isOpen) {
                    handleUsbDisconnected()
                }
                delay(2000)
            }
        }
    }

    fun handleUsbIntent(intent: Intent) {
        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
        if (!usbSerial.isOpen) {
            viewModelScope.launch { connectUsb(device) }
        }
    }

    private suspend fun connectUsb(device: UsbDevice) {
        _uiState.update { it.copy(usbStatus = UsbStatus.Connecting) }

        if (!usbSerial.hasPermission(device)) {
            usbSerial.requestPermission(device) { granted ->
                if (granted) {
                    viewModelScope.launch { openUsb(device) }
                } else {
                    _uiState.update { it.copy(
                        usbStatus    = UsbStatus.Error,
                        errorMessage = "USB permission denied. Plug in the ESP32 and try again."
                    ) }
                }
            }
        } else {
            openUsb(device)
        }
    }

    private suspend fun openUsb(device: UsbDevice) {
        val ok = usbSerial.open(device, viewModelScope)
        if (ok) {
            _uiState.update { it.copy(usbStatus = UsbStatus.Connected, errorMessage = null) }
            startEventLoop()
            // Request firmware version
            sendCommand(Cmd.version())
        } else {
            _uiState.update { it.copy(
                usbStatus    = UsbStatus.Error,
                errorMessage = "Could not open USB port. Check cable and OTG adapter."
            ) }
        }
    }

    private fun handleUsbDisconnected() {
        usbSerial.close()
        eventLoopJob?.cancel()
        eventLoopJob = null

        // If VPN was active, stop it
        if (_uiState.value.vpnActive) stopVpn()

        _uiState.update { it.copy(
            usbStatus       = UsbStatus.Disconnected,
            connectionState = ConnectionState.UsbError,
            vpnActive       = false,
            internetReady   = false,
            errorMessage    = "ESP32 disconnected. Reconnect USB cable."
        ) }
    }

    // ── Event loop ────────────────────────────────────────────────────────────
    private fun startEventLoop() {
        eventLoopJob = viewModelScope.launch(Dispatchers.IO) {
            for (frame in usbSerial.incomingControl) {
                if (frame.isEmpty()) continue
                val typeB = frame[0]
                if (typeB == Slip.TYPE_RESP && frame.size > 1) {
                    val event = parseEvent(frame.copyOfRange(1, frame.size))
                    event?.let { handleEvent(it) }
                }
            }
        }
    }

    private fun handleEvent(event: FirmwareEvent) {
        when (event) {
            is FirmwareEvent.Ready -> {
                _uiState.update { it.copy(fwVersion = event.fw) }
                // Auto-scan on connect
                requestScan()
            }
            is FirmwareEvent.Status -> {
                val cs = event.state.toConnectionState()
                val errorMsg = when (cs) {
                    ConnectionState.AuthFail     -> "Authentication failed. Incorrect password."
                    ConnectionState.NoAp         -> "Wi-Fi network not found. Ensure it is in range."
                    ConnectionState.Disconnected -> if (event.ssid.isNotBlank()) "Disconnected from ${event.ssid}" else null
                    else -> null
                }
                _uiState.update { it.copy(
                    connectionState = cs,
                    connectedSsid   = if (event.ssid.isNotBlank()) event.ssid else it.connectedSsid,
                    connectedIp     = event.ip,
                    internetReady   = cs == ConnectionState.Online,
                    errorMessage    = errorMsg ?: (if (cs == ConnectionState.Online) null else it.errorMessage)
                ) }
                if (cs == ConnectionState.Online && !_uiState.value.vpnActive) {
                    startVpn()
                }
            }
            is FirmwareEvent.ScanResult -> {
                _uiState.update { it.copy(
                    networks   = event.networks.sortedByDescending { it.rssi },
                    scanStatus = ScanStatus.Done
                ) }
            }
            is FirmwareEvent.Heartbeat -> {
                _uiState.update { current ->
                    val newConnState = if (event.state == "online") {
                        ConnectionState.Online
                    } else if (current.connectionState == ConnectionState.Connecting) {
                        current.connectionState // DO NOT overwrite while connecting
                    } else {
                        event.state.toConnectionState()
                    }
                    current.copy(
                        signalRssi      = event.rssi,
                        freeHeap        = event.heap,
                        connectionState = newConnState
                    )
                }
            }
            is FirmwareEvent.Version -> {
                _uiState.update { it.copy(fwVersion = "${event.fw} (${event.chip})") }
            }
            is FirmwareEvent.Error -> {
                Log.w(TAG, "Firmware error: ${event.code}")
                _uiState.update { it.copy(errorMessage = "Device error: ${event.code}") }
            }
            else -> {}
        }
    }

    // ── Public actions ────────────────────────────────────────────────────────
    fun requestScan() {
        _uiState.update { it.copy(scanStatus = ScanStatus.Scanning) }
        sendCommand(Cmd.scan())
    }

    fun connectToNetwork(ssid: String, password: String) {
        _uiState.update { it.copy(
            connectionState = ConnectionState.Connecting,
            connectedSsid   = ssid,
            errorMessage    = null
        ) }
        sendCommand(Cmd.connect(ssid, password))
    }

    fun disconnect() {
        sendCommand(Cmd.disconnect())
        stopVpn()
        _uiState.update { it.copy(
            connectionState = ConnectionState.Idle,
            vpnActive       = false,
            internetReady   = false,
            connectedSsid   = "",
            connectedIp     = ""
        ) }
    }

    fun onVpnPermissionGranted() {
        launchVpnService()
    }

    fun onVpnIntentLaunched() {
        _uiState.update { it.copy(pendingVpnIntent = null) }
    }

    // ── VPN lifecycle ─────────────────────────────────────────────────────────
    private fun startVpn() {
        val ctx = getApplication<Application>()
        val vpnIntent = VpnService.prepare(ctx)
        if (vpnIntent != null) {
            // Need user consent
            _uiState.update { it.copy(pendingVpnIntent = vpnIntent) }
        } else {
            launchVpnService()
        }
    }

    private fun launchVpnService() {
        val ctx = getApplication<Application>()
        Esp32VpnService.usbManager = usbSerial
        val intent = Intent(ctx, Esp32VpnService::class.java)
            .setAction(Esp32VpnService.ACTION_START)
        ctx.startService(intent)
        _uiState.update { it.copy(vpnActive = true) }
        Log.i(TAG, "VPN service started")
    }

    private fun stopVpn() {
        val ctx = getApplication<Application>()
        ctx.startService(
            Intent(ctx, Esp32VpnService::class.java)
                .setAction(Esp32VpnService.ACTION_STOP)
        )
        _uiState.update { it.copy(vpnActive = false, internetReady = false) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun sendCommand(payload: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            // Prepend control type byte 0x01
            val frame = ByteArray(payload.size + 1)
            frame[0] = Slip.TYPE_CMD
            System.arraycopy(payload, 0, frame, 1, payload.size)
            usbSerial.sendFrame(frame)
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopVpn()
        usbSerial.close()
    }

    companion object {
        fun factory(app: Application) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>) =
                MainViewModel(app) as T
        }
    }
}
