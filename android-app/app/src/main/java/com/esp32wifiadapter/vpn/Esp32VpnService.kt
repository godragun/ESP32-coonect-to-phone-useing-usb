package com.esp32wifiadapter.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.esp32wifiadapter.protocol.Slip
import com.esp32wifiadapter.usb.UsbSerialManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

private const val TAG = "Esp32VpnService"

/**
 * VPN Service that creates a TUN interface and bridges IP traffic to/from
 * the ESP32 over USB serial (SLIP framing).
 *
 * Traffic flow:
 *   Android apps → TUN FD → [SLIP encode + prepend 0x00] → USB serial → ESP32
 *   ESP32        → USB serial → [SLIP decode, strip 0x00]  → TUN FD → Android apps
 *
 * The service is started by the ViewModel once USB permission is granted and
 * the user initiates a VPN session.
 */
class Esp32VpnService : VpnService() {

    companion object {
        const val ACTION_START  = "com.esp32wifiadapter.vpn.START"
        const val ACTION_STOP   = "com.esp32wifiadapter.vpn.STOP"

        // TUN interface parameters — must match ESP32 firmware
        const val TUN_ADDRESS   = "10.0.0.2"
        const val TUN_PREFIX    = 30           // /30
        const val PEER_ADDRESS  = "10.0.0.1"
        const val DNS1          = "8.8.8.8"
        const val DNS2          = "8.8.4.4"
        const val MTU           = 1500
        const val SESSION_NAME  = "ESP32 Wi-Fi Adapter"

        /** Shared instance so ViewModel can attach/detach the USB connection */
        @Volatile var instance: Esp32VpnService? = null
        @Volatile var usbManager: UsbSerialManager? = null
    }

    private var tunFd: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunToUsbJob: Job? = null
    private var usbToTunJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "VPN service created")
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP  -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        stopVpn()
        scope.cancel()
        Log.i(TAG, "VPN service destroyed")
        super.onDestroy()
    }

    private fun startVpn() {
        stopVpn()

        // Build TUN interface
        val builder = Builder()
            .setSession(SESSION_NAME)
            .addAddress(TUN_ADDRESS, TUN_PREFIX)
            .addRoute("0.0.0.0", 0)           // Route ALL traffic through VPN
            .addDnsServer(DNS1)
            .addDnsServer(DNS2)
            .setMtu(MTU)
            .setBlocking(true)

        // Exclude our own app from VPN so USB serial traffic doesn't loop
        try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}

        tunFd = builder.establish() ?: run {
            Log.e(TAG, "Failed to establish TUN interface")
            stopSelf()
            return
        }

        Log.i(TAG, "TUN interface established: $TUN_ADDRESS/$TUN_PREFIX")
        startForwarding()
    }

    private fun startForwarding() {
        val fd    = tunFd ?: return
        val usb   = usbManager ?: run { Log.e(TAG, "No USB manager attached"); stopSelf(); return }
        val tunIn  = FileInputStream(fd.fileDescriptor)
        val tunOut = FileOutputStream(fd.fileDescriptor)

        // TUN → USB (Android → ESP32)
        tunToUsbJob = scope.launch {
            val pkt = ByteArray(MTU + 1)   // +1 for type prefix
            val ip  = ByteArray(MTU)
            while (isActive) {
                val len = try { tunIn.read(ip) } catch (e: IOException) { break }
                if (len <= 0) continue
                // Prepend type byte 0x00 (data) and send SLIP frame
                pkt[0] = Slip.TYPE_DATA
                System.arraycopy(ip, 0, pkt, 1, len)
                usb.sendFrame(pkt.copyOf(len + 1))
            }
            Log.i(TAG, "TUN→USB loop ended")
        }

        // USB → TUN (ESP32 → Android)
        usbToTunJob = scope.launch {
            for (frame in usb.incomingData) {
                if (frame.isEmpty()) continue
                if (frame[0] == Slip.TYPE_DATA && frame.size > 1) {
                    // Write raw IP packet (strip type byte) to TUN
                    try {
                        tunOut.write(frame, 1, frame.size - 1)
                    } catch (e: IOException) {
                        Log.e(TAG, "TUN write error: ${e.message}")
                        break
                    }
                }
                // Control events (0x02) are handled by the ViewModel, not here
            }
            Log.i(TAG, "USB→TUN loop ended")
        }
    }

    private fun stopVpn() {
        tunToUsbJob?.cancel()
        usbToTunJob?.cancel()
        tunToUsbJob = null
        usbToTunJob = null
        try { tunFd?.close() } catch (_: Exception) {}
        tunFd = null
        Log.i(TAG, "VPN stopped")
    }
}
