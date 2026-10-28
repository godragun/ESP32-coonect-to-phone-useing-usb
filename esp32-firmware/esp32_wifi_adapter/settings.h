// settings.h — Firmware configuration (edit before flashing)
#pragma once

// Serial baud rate for the UART bridge.
// 921600 is the recommended rate. If your bridge chip is CH340G it may need
// 460800 for stability on some cables. CP2102 typically handles 921600 fine.
#define CONF_SERIAL_BAUD    921600

// SLIP network addresses (must match Android VPN service config)
#define CONF_SLIP_IP_ESP32     "10.0.0.1"
#define CONF_SLIP_IP_ANDROID   "10.0.0.2"
#define CONF_SLIP_NETMASK      "255.255.255.252"

// Firmware version string
#define CONF_FW_VERSION  "1.0.0"

// Max IP packet size (standard Ethernet MTU)
#define CONF_MTU  1500

// Wi-Fi connection timeout in milliseconds
#define CONF_WIFI_TIMEOUT_MS  20000

// Enable verbose serial logging (disable before production; NEVER log passwords)
#define CONF_VERBOSE_LOG  0
