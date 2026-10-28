# Protocol Specification — ESP32 External Wi-Fi Adapter
## Version 1.0.0

---

## 1. Physical Layer

| Parameter | Value |
|---|---|
| Interface | USB serial (CDC-ACM via CP2102 or CH340 bridge) |
| Baud rate | 921 600 bps |
| Data bits | 8 |
| Parity | None |
| Stop bits | 1 |
| Flow control | None |

---

## 2. Framing — SLIP (RFC 1055)

All bytes on the wire are SLIP-framed.

| Symbol | Byte | Meaning |
|---|---|---|
| END | 0xC0 | Start/end of frame |
| ESC | 0xDB | Escape character |
| ESC_END | 0xDC | Escaped END within payload |
| ESC_ESC | 0xDD | Escaped ESC within payload |

A frame consists of:
```
END  [escaped payload bytes]  END
```

A leading END with no preceding bytes is ignored (used to clear the line).

---

## 3. Payload Structure

The first byte of every SLIP payload is a **type byte**:

| Type | Value | Direction | Description |
|---|---|---|---|
| DATA | 0x00 | Both | Raw IPv4 packet (data plane) |
| CMD | 0x01 | Android → ESP32 | JSON control command |
| RESP | 0x02 | ESP32 → Android | JSON control response / event |

### 3.1 DATA packets (0x00)

Payload:
```
[0x00] [raw IPv4 packet bytes …]
```

- Minimum length: 21 bytes (1 type + 20 byte IP header)
- Maximum length: 1501 bytes (1 type + 1500 byte MTU)
- Packets larger than MTU are silently dropped

### 3.2 CMD packets (0x01) — Android → ESP32

Payload:
```
[0x01] [UTF-8 JSON string]
```

Available commands:

#### scan
```json
{"type": "scan"}
```
Triggers a full passive Wi-Fi scan. Response: `scan_result` event.

#### connect
```json
{"type": "connect", "ssid": "MyNetwork", "pass": "secret123"}
```
- `pass` may be omitted or empty for open networks.
- Password is **never echoed** in any log or response.

#### disconnect
```json
{"type": "disconnect"}
```

#### status
```json
{"type": "status"}
```
Returns current `status` event immediately.

#### version
```json
{"type": "version"}
```
Returns `version` event.

### 3.3 RESP events (0x02) — ESP32 → Android

Payload:
```
[0x02] [UTF-8 JSON string]
```

#### ready
```json
{"type": "ready", "fw": "1.0.0"}
```
Sent on startup. Android should request a scan on receiving this.

#### status
```json
{
  "type":   "status",
  "state":  "online",
  "ssid":   "MyNetwork",
  "ip":     "192.168.1.42",
  "detail": "(optional human-readable note)"
}
```

`state` values:

| State | Meaning |
|---|---|
| `idle` | Not connected, not connecting |
| `scanning` | Wi-Fi scan in progress |
| `connecting` | Attempting to associate |
| `associated` | Layer-2 link established, getting IP |
| `got_ip` | DHCP complete |
| `online` | Connected and internet-routable |
| `auth_fail` | Wrong password or rejected by AP |
| `no_ap` | SSID not found |
| `disconnected` | Unexpectedly disconnected |

#### scan_result
```json
{
  "type": "scan_result",
  "networks": [
    {
      "ssid":    "HomeNetwork",
      "bssid":   "AA:BB:CC:DD:EE:FF",
      "rssi":    -58,
      "channel": 6,
      "enc":     4
    }
  ]
}
```

`enc` values (maps to `wifi_auth_mode_t`):

| enc | Security |
|---|---|
| 0 | Open |
| 2 | WPA-PSK |
| 4 | WPA2-PSK |
| 7 | WPA2-Enterprise |
| 8 | WPA3 |

#### heartbeat
```json
{
  "type":  "heartbeat",
  "state": "online",
  "rssi":  -62,
  "heap":  215840
}
```
Sent every 5 seconds. `heap` is free heap in bytes.

#### version
```json
{
  "type": "version",
  "fw":   "1.0.0",
  "chip": "ESP32-WROOM-32"
}
```

#### error
```json
{"type": "error", "error": "bad_json"}
```

Error codes: `bad_json`, `no_ssid`, `unknown_cmd`

---

## 4. IP Network Configuration

| Entity | Address |
|---|---|
| ESP32 SLIP interface | 10.0.0.1/30 |
| Android TUN interface | 10.0.0.2/30 |
| Default route | 0.0.0.0/0 via 10.0.0.1 |
| DNS server 1 | 8.8.8.8 (injected by VPN) |
| DNS server 2 | 8.8.4.4 (injected by VPN) |

The Android VpnService adds a default route through the TUN interface,
directing all IP traffic to the ESP32. The ESP32 performs NAT (via the
upstream Wi-Fi router's NAT) — the ESP32 itself is natted by the home
router, and acts as a transparent forwarder.

---

## 5. Session Lifecycle

```
Android                       ESP32
   │  [USB connected]            │
   │ ◄───── ready ───────────────│
   │ ──── version ──────────────►│
   │ ◄─── version event ─────────│
   │ ──── scan ─────────────────►│
   │ ◄─── scan_result ───────────│
   │  [user selects network]     │
   │ ──── connect(ssid,pass) ───►│
   │ ◄─── status(connecting) ────│
   │ ◄─── status(associated) ────│
   │ ◄─── status(online) ────────│
   │  [VPN established]          │
   │ ══ IP data packets ════════►│
   │ ◄══ IP data packets ════════│
   │ ◄─── heartbeat (5s) ────────│
   │  [user disconnects]         │
   │ ──── disconnect ───────────►│
   │ ◄─── status(idle) ──────────│
```

---

## 6. Error Handling

- **Malformed SLIP frame:** Silently discarded; decoder resets on next END byte.
- **Oversized packet:** Silently dropped (> MTU).
- **Invalid JSON command:** Firmware returns `{"type":"error","error":"bad_json"}`.
- **USB disconnected mid-session:** Android VPN service stops; UI shows error.
- **Wi-Fi auth failure:** Firmware sends `{"state":"auth_fail"}` within 20 s timeout.
- **Reconnect after USB unplug:** Android polls every 2 s; auto-reconnects.

---

## 7. Security Notes

- Wi-Fi passwords are transmitted over USB serial (physically local connection).
- Passwords are **never written to any log** on either side.
- No persistent credential storage is implemented in version 1.0.0.
- The SLIP channel provides no encryption. An attacker with physical USB access
  could read network credentials. Treat the USB connection as trusted.
- The VPN tunnel does not add encryption — it is a local loopback routing mechanism.
