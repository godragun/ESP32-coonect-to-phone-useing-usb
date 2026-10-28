# ESP32 External Wi-Fi Adapter

A complete solution to provide internet connectivity to an Android phone
(Samsung Galaxy A57) via an ESP32-WROOM-32 DevKit over USB OTG.

## Repository Structure

```
.
├── esp32-firmware/
│   └── esp32_wifi_adapter/
│       ├── esp32_wifi_adapter.ino   ← Main firmware sketch
│       └── settings.h               ← Configuration (baud rate, IPs)
├── android-app/
│   ├── settings.gradle
│   └── app/
│       ├── build.gradle
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── res/xml/device_filter.xml
│           └── java/com/esp32wifiadapter/
│               ├── MainActivity.kt
│               ├── protocol/
│               │   ├── Slip.kt           ← SLIP encoder/decoder (RFC 1055)
│               │   └── Messages.kt       ← JSON message types
│               ├── usb/
│               │   └── UsbSerialManager.kt  ← USB serial via usb-serial-for-android
│               ├── vpn/
│               │   └── Esp32VpnService.kt   ← Android VpnService (TUN bridge)
│               ├── viewmodel/
│               │   └── MainViewModel.kt     ← State machine + USB/VPN lifecycle
│               └── ui/
│                   ├── theme/Theme.kt
│                   └── screens/MainScreen.kt ← Compose UI
├── protocol/
│   └── PROTOCOL.md                  ← Full protocol specification
├── docs/
│   ├── FEASIBILITY_REPORT.md        ← Phase 1 architecture decision
│   └── BUILD_AND_FLASH_GUIDE.md     ← Step-by-step build instructions
└── tests/
    └── TEST_MATRIX.md               ← Test plan with status tracking
```

## How It Works

```
[Samsung Galaxy A57]
  All apps' IP traffic
      │
  [VpnService TUN 10.0.0.2]
      │ Raw IPv4 packets
  [SLIP framer]
      │ 0xC0-framed bytes
  [USB serial 921600 baud]
      │
  [USB OTG adapter]
      │
  [ESP32 DevKit USB connector]
      │
  [CH340/CP2102 UART bridge]
      │
  [ESP32-WROOM-32 UART0]
      │
  [SLIP decoder → lwIP]
      │ IP forwarding + NAT
  [Wi-Fi STA interface]
      │
  [Your Wi-Fi Router → Internet]
```

## Quick Start

1. **Flash firmware**: See `docs/BUILD_AND_FLASH_GUIDE.md`
2. **Build & install app**: `cd android-app && ./gradlew installDebug`
3. **Connect hardware**:
   - Plug ESP32 USB cable into a USB-C OTG adapter
   - Plug OTG adapter into the Galaxy A57
4. **Use the app**:
   - Open "ESP32 Wi-Fi" app
   - Tap ↻ to scan
   - Select a network and enter password
   - Accept the VPN prompt
   - All Android apps now use internet through the ESP32

## Key Technical Facts

| Fact | Detail |
|---|---|
| ESP32-WROOM-32 USB | **No native USB** — uses CP2102/CH340 bridge |
| USB Ethernet (CDC-NCM) | **Not possible** with WROOM-32 |
| Selected approach | USB Serial + Android VpnService + SLIP |
| System-wide routing | Yes — all apps get internet |
| Root required | No |
| Speed | ~80–115 KB/s (serial bound) |
| Upgrade path | ESP32-S3 → CDC-NCM → ~10× faster |

## Upgrade Path (Optional)

Replace the ESP32-WROOM-32 with an **ESP32-S3** DevKit (~$6–8):
- Native USB device support
- Can enumerate as CDC-NCM Ethernet adapter
- Android sees it as a standard network interface
- No VpnService needed — no VPN consent prompt
- 5–10× higher throughput
- The DrWhax `esp32-usb-wifi` project can be used as firmware reference

## Security Notes

- Wi-Fi passwords are never logged on either device
- No credentials are stored persistently
- The USB connection is treated as a trusted physical channel
- The VPN tunnel provides routing only (no encryption layer)

## License

MIT License — see LICENSE file.
