# Build & Flash Guide

## Requirements

### Hardware
- ESP32-WROOM-32 DevKit (with USB-C or Micro-USB port, using CP2102 or CH340 bridge chip)
- Cable setup (either option works):
  - **Option A (Direct C-to-C):** USB Type-C to Type-C data cable (if your ESP32 board has a Type-C port) — connects directly between phone and ESP32 with no adapter needed!
  - **Option B (OTG Adapter):** USB-A to Micro-USB cable + USB-C OTG adapter for the phone
- Samsung Galaxy A57 (or any Android smartphone with USB-OTG host support)

---

## Firmware Build (Arduino IDE)

### Step 1 — Install Arduino IDE 2.x
Download from https://www.arduino.cc/en/software

### Step 2 — Add ESP32 board package
1. Open **File → Preferences**
2. In "Additional boards manager URLs", add:
   ```
   https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json
   ```
3. Open **Tools → Board → Boards Manager**
4. Search for `esp32` by Espressif Systems
5. Install version **≥ 2.0.17**

### Step 3 — Install ArduinoJson library
1. Open **Sketch → Include Library → Manage Libraries**
2. Search for `ArduinoJson` by Benoit Blanchon
3. Install version **≥ 7.x**

### Step 4 — Install USB driver (Windows only)
- If your DevKit uses CH340: install the [WCH CH340 driver](https://www.wch-ic.com/downloads/CH341SER_EXE.html)
- If your DevKit uses CP2102: install [Silicon Labs VCP driver](https://www.silabs.com/developers/usb-to-uart-bridge-vcp-drivers)

### Step 5 — Open and configure the sketch
1. Open `esp32-firmware/esp32_wifi_adapter/esp32_wifi_adapter.ino`
2. Review `settings.h` — adjust `CONF_SERIAL_BAUD` if needed (default: 921600)
3. Select **Tools → Board → ESP32 Dev Module**
4. Select **Tools → Port → COMx** (your DevKit port)
5. Set **Tools → Upload Speed → 921600**
6. Set **Tools → Flash Size → 4MB**

### Step 6 — Flash
1. Hold the **BOOT** button on the DevKit
2. Click **Upload** in Arduino IDE
3. Release BOOT when "Connecting…" appears in the console
4. Wait for "Hard resetting via RTS pin…" — flashing is done

### Step 7 — Verify
1. Open **Tools → Serial Monitor**
2. Set baud rate to **921600**
3. After reset, you should see a SLIP-encoded binary "ready" message
   (it will look like garbage in plain text — that is expected)
4. To see human-readable output, temporarily set `CONF_VERBOSE_LOG 1`
   in `settings.h`, reflash, and check Serial Monitor at 115200

---

## Android App Build

### Requirements
- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android SDK 34
- Internet connection (to download Gradle dependencies)

### Step 1 — Open the project
```
File → Open → select the android-app/ folder
```

### Step 2 — Sync Gradle
Click "Sync Now" if prompted. Gradle will download:
- Compose BOM 2024.04.01
- usb-serial-for-android 3.7.0 (from JitPack)
- kotlinx-serialization-json

### Step 3 — Build and install
```bash
# From the android-app/ directory
./gradlew installDebug
```
Or use the **Run** button in Android Studio with the phone connected.

### Step 4 — Enable USB OTG on the phone
On Samsung Galaxy A57:
- Go to **Settings → Battery → More battery settings**
- Enable **Power delivery via USB** / OTG mode if prompted
- Or simply plug in an OTG device — it activates automatically

---

## Hardware Setup

```
Phone (USB-C port)
    │
    └── [USB-C OTG adapter]
            │
            └── USB-A female port
                    │
                    └── USB cable (USB-A to Micro-USB)
                                │
                                └── ESP32 DevKit
```

**Power notes:**
- The phone powers the DevKit through the OTG adapter (~500 mA available)
- ESP32 draws ~80–240 mA during Wi-Fi activity — well within OTG limits
- No external power supply is required for the DevKit

---

## First-Use Walkthrough

1. Flash the firmware onto the ESP32
2. Install the Android app
3. Connect ESP32 → OTG adapter → phone
4. Open the **ESP32 Wi-Fi** app
5. The app detects the device and shows "ESP32 connected" in the status bar
6. Tap **Refresh** (↻) to scan for Wi-Fi networks
7. Tap a network, enter the password, tap **Connect**
8. Wait for "Connected · Internet ready" status (~5–15 s)
9. Android prompts "New network VPN — do you want to connect?" — tap **OK**
10. Open any browser — internet works through the ESP32

---

## Recovery

### ESP32 not detected
1. Try a different USB cable (must be a **data** cable, not charge-only)
2. Re-install the USB bridge driver (CH340 or CP2102)
3. Try reflashing at lower speed (115200)

### "Authentication failed"
1. Re-enter the Wi-Fi password — it is case-sensitive
2. Confirm WPA2-Personal (not WPA2-Enterprise / 802.1x)

### App crashes on VPN start
1. Ensure `android.permission.BIND_VPN_SERVICE` is in the manifest (already included)
2. On Samsung One UI, check **Settings → Apps → ESP32 Wi-Fi → Permissions**

### Very slow internet
1. Check signal strength (RSSI) — below -80 dBm will be slow
2. Move closer to the router
3. Reduce baud rate to 460800 if your CH340 cable is unreliable

---

## Serial Monitor Debugging

When `CONF_VERBOSE_LOG 1` is set and reflashed:
```
[SLIP_NAT] SLIP interface up: 10.0.0.1
[SLIP_NAT] Wi-Fi connecting...
[SLIP_NAT] Wi-Fi associated
[SLIP_NAT] Got IP: 192.168.1.42
[SLIP_NAT] State: online
[SLIP_NAT] Heartbeat — heap: 218432, RSSI: -61
```
Note: passwords are **never printed**.

---

## Known Limitations

| Limitation | Detail |
|---|---|
| Throughput cap | ~80–115 KB/s at 921600 baud (USB serial bottleneck) |
| IPv6 | Not supported in this version; IPv4 only |
| WPA3 | Depends on ESP32 firmware version; WPA2 recommended |
| WPA2-Enterprise | Not supported (requires RADIUS) |
| Simultaneous sessions | ~50–100 TCP sessions; large numbers may hit ESP32 RAM limit |
| Background VPN | Android may reduce battery background execution — set VPN to always-on if needed |
| Samsung VPN warning | One UI shows a persistent VPN notification — this is expected OS behaviour |
| Speed for video | HD streaming is not realistic at serial baud limits |
