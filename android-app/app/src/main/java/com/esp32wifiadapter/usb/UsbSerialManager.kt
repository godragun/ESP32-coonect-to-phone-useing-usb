package com.esp32wifiadapter.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.esp32wifiadapter.protocol.Slip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "UsbSerial"
private const val ACTION_USB_PERMISSION = "com.esp32wifiadapter.USB_PERMISSION"
private const val BAUD_RATE = 921600
private const val READ_TIMEOUT_MS = 200
private const val WRITE_TIMEOUT_MS = 500
private const val READ_BUF = 4096

/**
 * Manages the USB serial connection to the ESP32 DevKit.
 *
 * Usage:
 *   1. Call [findCompatibleDevice] to get the UsbDevice.
 *   2. Call [requestPermission] if [UsbManager.hasPermission] returns false.
 *   3. Call [open] to start the connection.
 *   4. Collect [incomingFrames] to receive SLIP-decoded payloads.
 *   5. Call [sendFrame] to SLIP-encode and send bytes.
 *   6. Call [close] on teardown.
 */
class UsbSerialManager(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var port: UsbSerialPort? = null
    private var rxJob: Job? = null
    private val _decoder = Slip.Decoder(maxFrameSize = 65536)

    /** Control channel events (0x01, 0x02) for ViewModel */
    val incomingControl = Channel<ByteArray>(capacity = 64)

    /** Raw IP data packets (0x00) for Esp32VpnService */
    val incomingData = Channel<ByteArray>(capacity = 512)

    /** Returns the first compatible USB device found, or null if none are connected. */
    fun findCompatibleDevice(): UsbDevice? {
        val prober = UsbSerialProber.getDefaultProber()
        for ((_, device) in usbManager.deviceList) {
            val driver = prober.probeDevice(device)
            if (driver != null) return device
        }
        return null
    }

    /** Returns true if the app already has USB permission for [device]. */
    fun hasPermission(device: UsbDevice) = usbManager.hasPermission(device)

    /**
     * Asynchronously requests USB permission for [device].
     * The result is delivered via the system broadcast ACTION_USB_PERMISSION.
     * Callers should observe [UsbManager.EXTRA_PERMISSION_GRANTED] in the broadcast.
     */
    fun requestPermission(device: UsbDevice, onResult: (granted: Boolean) -> Unit) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION), flags
        )
        val filter = IntentFilter(ACTION_USB_PERMISSION)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != ACTION_USB_PERMISSION) return
                context.unregisterReceiver(this)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                onResult(granted)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        usbManager.requestPermission(device, pi)
    }

    /**
     * Opens the serial port to [device] at [BAUD_RATE].
     * Returns true on success.
     */
    suspend fun open(device: UsbDevice, scope: CoroutineScope): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val prober  = UsbSerialProber.getDefaultProber()
                val driver: UsbSerialDriver = prober.probeDevice(device) ?: return@withContext false
                val connection = usbManager.openDevice(device) ?: return@withContext false
                val p = driver.ports[0]
                p.open(connection)
                p.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                port = p
                _decoder.reset()
                startRxLoop(scope)
                Log.i(TAG, "USB serial opened: ${device.productName} @ ${BAUD_RATE} baud")
                true
            } catch (e: Exception) {
                Log.e(TAG, "open() failed: ${e.message}")
                false
            }
        }

    /** Send a SLIP-encoded frame (type byte already prepended in payload). */
    suspend fun sendFrame(payload: ByteArray) = withContext(Dispatchers.IO) {
        try {
            val frame = Slip.encode(payload)
            port?.write(frame, WRITE_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.e(TAG, "sendFrame() error: ${e.message}")
        }
    }

    fun close() {
        rxJob?.cancel()
        rxJob = null
        try { port?.close() } catch (_: Exception) {}
        port = null
        Log.i(TAG, "USB serial closed")
    }

    val isOpen get() = port != null

    private fun startRxLoop(scope: CoroutineScope) {
        rxJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(READ_BUF)
            while (isActive) {
                val len = try {
                    port?.read(buf, READ_TIMEOUT_MS) ?: break
                } catch (e: Exception) {
                    Log.w(TAG, "read error: ${e.message}")
                    break
                }
                for (i in 0 until len) {
                    val frame = _decoder.feed(buf[i])
                    if (frame != null && frame.isNotEmpty()) {
                        if (frame[0] == Slip.TYPE_DATA) {
                            incomingData.trySend(frame)
                        } else {
                            incomingControl.trySend(frame)
                        }
                    }
                }
            }
            Log.i(TAG, "RX loop ended")
        }
    }
}
