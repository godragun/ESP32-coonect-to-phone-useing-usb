package com.esp32wifiadapter.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Control protocol messages exchanged over the SLIP channel.
 * All messages are JSON, prefixed with a type byte (0x01 or 0x02).
 */

val json = Json { ignoreUnknownKeys = true; isLenient = true }

// ─── Commands (Android → ESP32, type = 0x01) ─────────────────────────────────

object Cmd {
    fun scan() = buildJsonObject { put("type", "scan") }.toString().toByteArray()

    fun connect(ssid: String, password: String) = buildJsonObject {
        put("type", "connect")
        put("ssid", ssid)
        put("pass", password)   // NOT logged anywhere in the app
    }.toString().toByteArray()

    fun disconnect() = buildJsonObject { put("type", "disconnect") }.toString().toByteArray()

    fun status() = buildJsonObject { put("type", "status") }.toString().toByteArray()

    fun version() = buildJsonObject { put("type", "version") }.toString().toByteArray()
}

// ─── Events (ESP32 → Android, type = 0x02) ───────────────────────────────────

/** Parse a JSON event from the firmware. Returns null for malformed payloads. */
fun parseEvent(bytes: ByteArray): FirmwareEvent? {
    return try {
        val text = String(bytes, Charsets.UTF_8)
        val obj  = json.parseToJsonElement(text) as? JsonObject ?: return null
        when (obj["type"]?.jsonPrimitive?.content) {
            "ready"       -> FirmwareEvent.Ready(obj["fw"]?.jsonPrimitive?.content ?: "?")
            "status"      -> FirmwareEvent.Status(
                state  = obj["state"]?.jsonPrimitive?.content ?: "unknown",
                ssid   = obj["ssid"]?.jsonPrimitive?.content ?: "",
                ip     = obj["ip"]?.jsonPrimitive?.content ?: ""
            )
            "scan_result" -> {
                val nets = obj["networks"]
                    ?.let { it as? kotlinx.serialization.json.JsonArray }
                    ?.mapNotNull { el ->
                        (el as? JsonObject)?.let { n ->
                            WifiNetwork(
                                ssid    = n["ssid"]?.jsonPrimitive?.content ?: "",
                                bssid   = n["bssid"]?.jsonPrimitive?.content ?: "",
                                rssi    = n["rssi"]?.jsonPrimitive?.content?.toIntOrNull() ?: -100,
                                channel = n["channel"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                                encType = n["enc"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                            )
                        }
                    } ?: emptyList()
                FirmwareEvent.ScanResult(nets)
            }
            "heartbeat"   -> FirmwareEvent.Heartbeat(
                state = obj["state"]?.jsonPrimitive?.content ?: "unknown",
                rssi  = obj["rssi"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                heap  = obj["heap"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            )
            "error"       -> FirmwareEvent.Error(obj["error"]?.jsonPrimitive?.content ?: "?")
            "version"     -> FirmwareEvent.Version(
                fw   = obj["fw"]?.jsonPrimitive?.content ?: "?",
                chip = obj["chip"]?.jsonPrimitive?.content ?: "?"
            )
            else          -> FirmwareEvent.Unknown(text)
        }
    } catch (e: Exception) { null }
}

sealed class FirmwareEvent {
    data class Ready(val fw: String) : FirmwareEvent()
    data class Status(val state: String, val ssid: String, val ip: String) : FirmwareEvent()
    data class ScanResult(val networks: List<WifiNetwork>) : FirmwareEvent()
    data class Heartbeat(val state: String, val rssi: Int, val heap: Long) : FirmwareEvent()
    data class Error(val code: String) : FirmwareEvent()
    data class Version(val fw: String, val chip: String) : FirmwareEvent()
    data class Unknown(val raw: String) : FirmwareEvent()
}

@Serializable
data class WifiNetwork(
    val ssid:    String,
    val bssid:   String,
    val rssi:    Int,
    val channel: Int,
    val encType: Int   // maps to Arduino wifi_auth_mode_t: 0=open,2=WPA,4=WPA2,8=WPA3
) {
    val isOpen get() = encType == 0
    val securityLabel get() = when (encType) {
        0    -> "Open"
        2    -> "WPA"
        4    -> "WPA2"
        7    -> "WPA2-Enterprise"
        8    -> "WPA3"
        else -> "Unknown"
    }
    /** Signal quality 0–100 */
    val signalLevel get() = when {
        rssi >= -50 -> 100
        rssi >= -60 -> 80
        rssi >= -70 -> 60
        rssi >= -80 -> 40
        rssi >= -90 -> 20
        else         -> 5
    }
}
