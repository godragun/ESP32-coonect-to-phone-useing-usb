# Test Matrix — ESP32 External Wi-Fi Adapter

## Status Key
- ✅ Pass — verified with actual hardware
- ❌ Fail
- ⚠️ Partial — works with caveats
- 🔲 Not tested — physical hardware unavailable
- 🚫 Blocked — prerequisite hardware missing

---

## USB Connectivity Tests

| # | Test | Expected Result | Status | Notes |
|---|---|---|---|---|
| U1 | Connect ESP32 via OTG adapter | App shows "ESP32 connected", USB status green | 🔲 | Requires physical hardware |
| U2 | App launched before USB connected | App shows "Connect ESP32" placeholder | 🔲 | |
| U3 | Plug in ESP32 after app launch | App auto-detects, shows connected | 🔲 | Via USB broadcast intent |
| U4 | Deny USB permission | App shows "USB permission denied" — no false success | 🔲 | |
| U5 | Non-ESP32 USB device connected | App ignores it | 🔲 | |
| U6 | Unplug ESP32 mid-session | App shows "USB disconnected", VPN stops | 🔲 | |
| U7 | Replug ESP32 after disconnect | App auto-reconnects within 2 s | 🔲 | USB polling loop |
| U8 | Charge-only cable (no data) | App shows no device found | 🔲 | Expected — data cable required |

---

## Wi-Fi Scan Tests

| # | Test | Expected Result | Status | Notes |
|---|---|---|---|---|
| W1 | Tap Refresh with ESP32 connected | Real nearby networks appear (SSID, RSSI, security) | 🔲 | |
| W2 | Scan with no APs nearby | "No networks found" message | 🔲 | |
| W3 | Scan in progress — scan again | Second scan waits; spinner shown | 🔲 | |
| W4 | Networks sorted by signal | Strongest signal at top | 🔲 | Sorted by RSSI in ViewModel |
| W5 | Network with special chars in SSID | Displays correctly | 🔲 | |
| W6 | Hidden network (empty SSID) | Shows "<hidden>" | 🔲 | |

---

## Connection Tests

| # | Test | Expected Result | Status | Notes |
|---|---|---|---|---|
| C1 | Correct WPA2 password | "Associated" → "Online" within 15 s | 🔲 | |
| C2 | Incorrect WPA2 password | "Authentication failed" within 20 s | 🔲 | ESP32 reports auth_fail reason code |
| C3 | Open network | Connects without password prompt | 🔲 | |
| C4 | Network out of range | "Network unavailable" within timeout | 🔲 | |
| C5 | Correct password, no DHCP | "Associated" but never reaches "Online" | 🔲 | |
| C6 | Connect, disconnect, reconnect | Full re-association cycle | 🔲 | |
| C7 | AP powers off while connected | Disconnected state reported | 🔲 | |
| C8 | Weak signal (-85 dBm) | Connection may succeed; throughput low | 🔲 | |

---

## Internet Routing Tests

| # | Test | Expected Result | Status | Notes |
|---|---|---|---|---|
| I1 | DNS lookup (e.g., google.com) | Resolves via 8.8.8.8 injected by VPN | 🔲 | |
| I2 | HTTP GET via browser | Fresh page loads | 🔲 | |
| I3 | HTTPS request | TLS handshake succeeds | 🔲 | |
| I4 | Multiple apps (browser + Play Store) | All use VPN tunnel | 🔲 | addRoute(0.0.0.0/0) routes all |
| I5 | No internet on upstream router | Wi-Fi association succeeds; browser shows no internet | 🔲 | |
| I6 | YouTube / video streaming | Partial — audio quality streams fine; HD video may stutter | 🔲 | Serial bandwidth limit |
| I7 | Long-running connection (1 h) | No memory leak; stable | 🔲 | Monitor ESP32 heap via heartbeat |
| I8 | Concurrent TCP connections | ~50–100 sessions; typical browser load OK | 🔲 | |
| I9 | UDP traffic (DNS, video) | DNS works; UDP generally passes | 🔲 | |
| I10 | Ping to 8.8.8.8 | Responds with ~20–50 ms added latency | 🔲 | |

---

## VPN Lifecycle Tests

| # | Test | Expected Result | Status | Notes |
|---|---|---|---|---|
| V1 | First VPN start | Android consent dialog appears | 🔲 | Required by OS — expected |
| V2 | VPN consent denied | App explains; no crash | 🔲 | |
| V3 | VPN consent granted | TUN interface established; traffic routes | 🔲 | |
| V4 | VPN already allowed (second run) | No consent dialog | 🔲 | |
| V5 | App killed mid-VPN | VPN service stops cleanly | 🔲 | |
| V6 | Reopen app after previous session | Reconnects if ESP32 still plugged in | 🔲 | |

---

## Error Handling Tests

| # | Test | Expected Result | Status | Notes |
|---|---|---|---|---|
| E1 | Malformed SLIP frame from ESP32 | Frame discarded; app continues | 🔲 | Decoder resets on next END |
| E2 | ESP32 resets mid-session | App detects lost heartbeat; shows error | 🔲 | |
| E3 | ESP32 flash low on memory | Firmware drops frames; heap reported in heartbeat | 🔲 | |
| E4 | Very long SSID or password | Firmware validates; returns error event | 🔲 | |

---

## Performance Measurements

| Metric | Target | Measured | Status |
|---|---|---|---|
| Throughput (download) | > 300 KB/s | TBD | 🔲 |
| Throughput (upload) | > 100 KB/s | TBD | 🔲 |
| DNS response latency | < 200 ms | TBD | 🔲 |
| Web page load time | < 5 s (simple page) | TBD | 🔲 |
| ESP32 heap after 30 min | > 100 KB free | TBD | 🔲 |

---

## Notes on Untested Items

All tests are marked 🔲 because physical hardware (the ESP32 board connected
to the Samsung Galaxy A57) is not available in this development environment.

**To execute these tests:**
1. Flash the firmware following `docs/BUILD_AND_FLASH_GUIDE.md`
2. Install the app on the Galaxy A57
3. Connect via USB OTG adapter
4. Run each test case and record results here

Tests can be marked ✅ / ❌ / ⚠️ with observed values filled in.
