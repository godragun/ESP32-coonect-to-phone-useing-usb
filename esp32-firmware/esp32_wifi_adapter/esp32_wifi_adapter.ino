/**
 * ESP32 External Wi-Fi Adapter Firmware
 * ======================================
 * Architecture: USB Serial (CDC-ACM via CH340/CP2102) + SLIP framing + lwIP NAT
 *
 * Packet format:
 *   SLIP-framed payload, first byte = packet type:
 *     0x00  Raw IPv4 data packet  (data plane)
 *     0x01  Control command JSON  (Android → ESP32)
 *     0x02  Control response JSON (ESP32 → Android)
 *
 * IP plan:
 *   ESP32 SLIP interface : 10.0.0.1
 *   Android TUN          : 10.0.0.2
 *   Default gateway      : 10.0.0.1
 *
 * Build: Arduino IDE with arduino-esp32 board package (≥ 2.0.17)
 * Board: "ESP32 Dev Module"
 * Baud:  921600 (SERIAL_BAUD below)
 *
 * SECURITY NOTE: Wi-Fi passwords are never written to Serial logs.
 */

#include <Arduino.h>
#include <WiFi.h>
#include <lwip/netif.h>
#include <lwip/ip4.h>
#include <lwip/ip4_addr.h>
#include <lwip/pbuf.h>
#include <lwip/tcpip.h>
#include <lwip/etharp.h>
#include <lwip/lwip_napt.h>
#include <ArduinoJson.h>
#include <esp_wifi.h>
#include <esp_netif.h>
#include <nvs_flash.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <freertos/semphr.h>

// ─── Configuration ────────────────────────────────────────────────────────────
#define SERIAL_BAUD          921600
#define SLIP_MTU             1500
#define SLIP_BUF_SIZE        2048     // > MTU + worst-case SLIP expansion
#define CTRL_BUF_SIZE        512
#define SLIP_END             0xC0
#define SLIP_ESC             0xDB
#define SLIP_ESC_END         0xDC
#define SLIP_ESC_ESC         0xDD
#define PKT_TYPE_DATA        0x00
#define PKT_TYPE_CMD         0x01
#define PKT_TYPE_RESP        0x02
#define WIFI_CONNECT_TIMEOUT_MS 20000
#define PING_HOST            "8.8.8.8"
#define SLIP_IP_ESP32        "10.0.0.1"
#define SLIP_IP_ANDROID      "10.0.0.2"
#define SLIP_IP_NETMASK      "255.255.255.252"
#define FW_VERSION           "1.0.0"

// ─── State ────────────────────────────────────────────────────────────────────
typedef enum {
    WIFI_STATE_IDLE,
    WIFI_STATE_CONNECTING,
    WIFI_STATE_CONNECTED,
    WIFI_STATE_GETTING_IP,
    WIFI_STATE_ONLINE,
    WIFI_STATE_AUTH_FAIL,
    WIFI_STATE_NO_AP,
    WIFI_STATE_DISCONNECTED
} wifi_state_t;

static volatile wifi_state_t g_wifi_state = WIFI_STATE_IDLE;
static volatile bool         g_slip_active = false;
static SemaphoreHandle_t     g_serial_mutex;
static char                  g_connected_ssid[33] = {0};
static char                  g_ip_addr[16]         = {0};

// ─── SLIP encoder/decoder ─────────────────────────────────────────────────────
class SlipCodec {
public:
    // Encode raw bytes into SLIP frame and write to Serial
    static void sendFrame(const uint8_t* data, size_t len) {
        if (xSemaphoreTake(g_serial_mutex, pdMS_TO_TICKS(200)) != pdTRUE) return;
        Serial.write(SLIP_END);
        for (size_t i = 0; i < len; i++) {
            if (data[i] == SLIP_END) {
                Serial.write(SLIP_ESC);
                Serial.write(SLIP_ESC_END);
            } else if (data[i] == SLIP_ESC) {
                Serial.write(SLIP_ESC);
                Serial.write(SLIP_ESC_ESC);
            } else {
                Serial.write(data[i]);
            }
        }
        Serial.write(SLIP_END);
        xSemaphoreGive(g_serial_mutex);
    }

    // Stream a pbuf chain as a SLIP frame with type prefix without large stack buffers
    static void sendPbuf(uint8_t pktType, struct pbuf* p) {
        if (xSemaphoreTake(g_serial_mutex, pdMS_TO_TICKS(200)) != pdTRUE) return;
        Serial.write(SLIP_END);
        // Write packet type byte
        if (pktType == SLIP_END) {
            Serial.write(SLIP_ESC);
            Serial.write(SLIP_ESC_END);
        } else if (pktType == SLIP_ESC) {
            Serial.write(SLIP_ESC);
            Serial.write(SLIP_ESC_ESC);
        } else {
            Serial.write(pktType);
        }
        // Write payload directly from pbuf chain
        for (struct pbuf* q = p; q != NULL; q = q->next) {
            const uint8_t* d = (const uint8_t*)q->payload;
            for (size_t i = 0; i < q->len; i++) {
                if (d[i] == SLIP_END) {
                    Serial.write(SLIP_ESC);
                    Serial.write(SLIP_ESC_END);
                } else if (d[i] == SLIP_ESC) {
                    Serial.write(SLIP_ESC);
                    Serial.write(SLIP_ESC_ESC);
                } else {
                    Serial.write(d[i]);
                }
            }
        }
        Serial.write(SLIP_END);
        xSemaphoreGive(g_serial_mutex);
    }

    // State machine decoder: call with each incoming byte.
    // Returns true and fills out/outLen when a complete frame is ready.
    bool feed(uint8_t byte, uint8_t* out, size_t* outLen) {
        switch (byte) {
        case SLIP_END:
            if (_pos > 0) {
                memcpy(out, _buf, _pos);
                *outLen = _pos;
                _pos = 0;
                _escaped = false;
                return true;
            }
            return false;
        case SLIP_ESC:
            _escaped = true;
            return false;
        case SLIP_ESC_END:
            if (_escaped) { _appendByte(SLIP_END); _escaped = false; }
            else          { _appendByte(byte); }
            return false;
        case SLIP_ESC_ESC:
            if (_escaped) { _appendByte(SLIP_ESC); _escaped = false; }
            else          { _appendByte(byte); }
            return false;
        default:
            _escaped = false;
            _appendByte(byte);
            return false;
        }
    }

    void reset() { _pos = 0; _escaped = false; }

private:
    uint8_t _buf[SLIP_BUF_SIZE];
    size_t  _pos     = 0;
    bool    _escaped = false;

    void _appendByte(uint8_t b) {
        if (_pos < SLIP_BUF_SIZE) _buf[_pos++] = b;
        else reset(); // overflow — drop frame
    }
};

static SlipCodec g_slipDecoder;

// ─── SLIP netif for lwIP ──────────────────────────────────────────────────────
// The SLIP netif feeds received IP packets from serial into lwIP's IP stack,
// and lwIP calls slipif_output to transmit packets toward Android.
static struct netif g_slip_netif;

static err_t slip_netif_output(struct netif* netif, struct pbuf* p, const ip4_addr_t* ipaddr) {
    (void)netif; (void)ipaddr;
    SlipCodec::sendPbuf(PKT_TYPE_DATA, p);
    return ERR_OK;
}

static err_t slip_netif_init(struct netif* netif) {
    netif->name[0] = 's';
    netif->name[1] = 'l';
    netif->output  = slip_netif_output;
    netif->mtu     = SLIP_MTU;
    netif->flags   = NETIF_FLAG_BROADCAST | NETIF_FLAG_LINK_UP;
    return ERR_OK;
}

// Inject raw IP packet into lwIP from the serial/SLIP side
static void slip_recv_packet(const uint8_t* data, size_t len) {
    struct pbuf* p = pbuf_alloc(PBUF_RAW, (u16_t)len, PBUF_RAM);
    if (!p) return;
    memcpy(p->payload, data, len);
    LOCK_TCPIP_CORE();
    if (g_slip_netif.input(p, &g_slip_netif) != ERR_OK) pbuf_free(p);
    UNLOCK_TCPIP_CORE();
}

// ─── Control channel helpers ──────────────────────────────────────────────────
static void sendControl(JsonDocument& doc) {
    uint8_t buf[CTRL_BUF_SIZE + 1];
    buf[0] = PKT_TYPE_RESP;
    size_t n = serializeJson(doc, (char*)buf + 1, CTRL_BUF_SIZE);
    SlipCodec::sendFrame(buf, n + 1);
}

static void sendStatus(const char* status, const char* detail = nullptr) {
    StaticJsonDocument<256> doc;
    doc["type"]   = "status";
    doc["state"]  = status;
    if (detail) doc["detail"] = detail;
    doc["ssid"]   = g_connected_ssid;
    doc["ip"]     = g_ip_addr;
    sendControl(doc);
}

static const char* stateToString(wifi_state_t s) {
    switch (s) {
    case WIFI_STATE_IDLE:        return "idle";
    case WIFI_STATE_CONNECTING:  return "connecting";
    case WIFI_STATE_CONNECTED:   return "associated";
    case WIFI_STATE_GETTING_IP:  return "getting_ip";
    case WIFI_STATE_ONLINE:      return "online";
    case WIFI_STATE_AUTH_FAIL:   return "auth_fail";
    case WIFI_STATE_NO_AP:       return "no_ap";
    case WIFI_STATE_DISCONNECTED:return "disconnected";
    default:                     return "unknown";
    }
}

static unsigned long g_connect_start_ms = 0;

static void onWifiDisconnect(WiFiEvent_t event, WiFiEventInfo_t info) {
    (void)event;
    uint8_t reason = info.wifi_sta_disconnected.reason;

    // If we are actively connecting, ignore transient scan/handshake events and let ESP-IDF negotiate
    if (g_wifi_state == WIFI_STATE_CONNECTING) {
        // Only report fatal auth failure if we've been attempting for at least 2 seconds
        // (to ignore stale disconnect events from prior connections)
        if (g_connect_start_ms > 0 && (millis() - g_connect_start_ms > 2000)) {
            if (reason == WIFI_REASON_AUTH_FAIL ||
                reason == WIFI_REASON_4WAY_HANDSHAKE_TIMEOUT ||
                reason == WIFI_REASON_HANDSHAKE_TIMEOUT ||
                reason == WIFI_REASON_MIC_FAILURE) {
                g_connect_start_ms = 0;
                g_wifi_state = WIFI_STATE_AUTH_FAIL;
                ip_napt_enable((u32_t)WiFi.localIP(), 0);
                sendStatus("auth_fail", "Authentication failed");
                return;
            }
        }
        // Let ESP-IDF keep scanning and attempting association until the 25s watchdog fires
        return;
    }

    // If we were previously online or associated, report the disconnection
    if (g_wifi_state == WIFI_STATE_ONLINE || g_wifi_state == WIFI_STATE_CONNECTED) {
        g_wifi_state = WIFI_STATE_DISCONNECTED;
        memset(g_connected_ssid, 0, sizeof(g_connected_ssid));
        memset(g_ip_addr, 0, sizeof(g_ip_addr));
        ip_napt_enable((u32_t)WiFi.localIP(), 0);
        sendStatus("disconnected");
    }
}

// ─── Control Command Dispatcher ───────────────────────────────────────────────
static void handleCommand(const uint8_t* payload, size_t len) {
    StaticJsonDocument<512> cmd;
    DeserializationError err = deserializeJson(cmd, payload, len);
    if (err) {
        StaticJsonDocument<128> resp;
        resp["type"]  = "error";
        resp["error"] = "bad_json";
        sendControl(resp);
        return;
    }

    const char* type = cmd["type"] | "";

    if (strcmp(type, "scan") == 0) {
        // Wi-Fi scan
        sendStatus("scanning");
        int n = WiFi.scanNetworks(false /*async*/, true /*show_hidden*/);
        StaticJsonDocument<4096> resp;
        resp["type"] = "scan_result";
        JsonArray networks = resp.createNestedArray("networks");
        for (int i = 0; i < n; i++) {
            JsonObject net = networks.createNestedObject();
            net["ssid"]     = WiFi.SSID(i);
            net["rssi"]     = WiFi.RSSI(i);
            net["bssid"]    = WiFi.BSSIDstr(i);
            net["channel"]  = WiFi.channel(i);
            net["enc"]      = (int)WiFi.encryptionType(i);
        }
        WiFi.scanDelete();
        sendControl(resp);

    } else if (strcmp(type, "connect") == 0) {
        const char* ssid = cmd["ssid"] | "";
        const char* pass = cmd["pass"] | "";
        if (strlen(ssid) == 0) {
            StaticJsonDocument<128> resp;
            resp["type"]  = "error";
            resp["error"] = "no_ssid";
            sendControl(resp);
            return;
        }
        g_wifi_state = WIFI_STATE_CONNECTING;
        g_connect_start_ms = millis();
        strncpy(g_connected_ssid, ssid, sizeof(g_connected_ssid)-1);
        sendStatus("connecting");
        WiFi.persistent(false);
        WiFi.disconnect(false, false);
        delay(30);
        if (strlen(pass) > 0) {
            WiFi.begin(ssid, pass);
        } else {
            WiFi.begin(ssid);
        }

    } else if (strcmp(type, "disconnect") == 0) {
        g_connect_start_ms = 0;
        WiFi.disconnect(false, false);
        g_wifi_state = WIFI_STATE_IDLE;
        memset(g_connected_ssid, 0, sizeof(g_connected_ssid));
        memset(g_ip_addr, 0, sizeof(g_ip_addr));
        sendStatus("idle");

    } else if (strcmp(type, "status") == 0) {
        sendStatus(stateToString(g_wifi_state));

    } else if (strcmp(type, "version") == 0) {
        StaticJsonDocument<128> resp;
        resp["type"]    = "version";
        resp["fw"]      = FW_VERSION;
        resp["chip"]    = "ESP32-WROOM-32";
        resp["baud"]    = SERIAL_BAUD;
        sendControl(resp);

    } else {
        StaticJsonDocument<128> resp;
        resp["type"]  = "error";
        resp["error"] = "unknown_cmd";
        sendControl(resp);
    }
}

// ─── Serial RX Task ───────────────────────────────────────────────────────────
static void serialRxTask(void* pvParam) {
    static uint8_t frameBuf[SLIP_BUF_SIZE];
    size_t frameLen = 0;

    while (true) {
        while (Serial.available() > 0) {
            uint8_t b = (uint8_t)Serial.read();
            if (g_slipDecoder.feed(b, frameBuf, &frameLen) && frameLen > 0) {
                uint8_t pktType = frameBuf[0];
                if (pktType == PKT_TYPE_DATA && frameLen > 1) {
                    // Raw IP packet from Android → forward into lwIP
                    slip_recv_packet(frameBuf + 1, frameLen - 1);
                } else if (pktType == PKT_TYPE_CMD && frameLen > 1) {
                    // Control command
                    handleCommand(frameBuf + 1, frameLen - 1);
                }
            }
        }
        vTaskDelay(pdMS_TO_TICKS(1));
    }
}

// ─── lwIP IP Forwarding Setup ─────────────────────────────────────────────────
// Enable IP forwarding so packets from SLIP netif get NAT'd to Wi-Fi STA
static void setupIpForwarding() {
    // Enable lwIP IP forwarding (requires CONFIG_LWIP_IP_FORWARD=y in sdkconfig,
    // which is set by default in Arduino-ESP32 >= 2.x)
    // The actual NAT is handled by lwIP's ip4_forward() in netif routing.
    // Android's default route (0.0.0.0/0 via 10.0.0.1) causes all traffic to
    // arrive at the SLIP netif. lwIP forwards it to the Wi-Fi STA netif.
    // The STA netif's upstream router does the actual NAT to the internet.
    // ESP32's Wi-Fi STA also gets NAT'd by the home router.

    // Set up SLIP netif
    ip4_addr_t ipaddr, netmask, gw;
    IP4_ADDR(&ipaddr,  10, 0, 0, 1);
    IP4_ADDR(&netmask, 255, 255, 255, 252);
    IP4_ADDR(&gw,      10, 0, 0, 2);  // Android is the peer

    LOCK_TCPIP_CORE();
    netif_add(&g_slip_netif, &ipaddr, &netmask, &gw, NULL, slip_netif_init, tcpip_input);
    netif_set_up(&g_slip_netif);
    UNLOCK_TCPIP_CORE();
}

// ─── Arduino Setup ────────────────────────────────────────────────────────────
void setup() {
    Serial.begin(SERIAL_BAUD);
    while (!Serial) delay(10);

    // NVS init (required by Wi-Fi)
    nvs_flash_init();

    g_serial_mutex = xSemaphoreCreateMutex();

    // Wi-Fi events
    WiFi.onEvent([](WiFiEvent_t e, WiFiEventInfo_t info){
        switch (e) {
        case ARDUINO_EVENT_WIFI_STA_START:
            g_wifi_state = WIFI_STATE_IDLE;
            sendStatus("idle");
            break;
        case ARDUINO_EVENT_WIFI_STA_CONNECTED:
            g_wifi_state = WIFI_STATE_CONNECTED;
            sendStatus("associated");
            break;
        case ARDUINO_EVENT_WIFI_STA_GOT_IP:
            g_connect_start_ms = 0;
            strncpy(g_ip_addr, WiFi.localIP().toString().c_str(), sizeof(g_ip_addr)-1);
            g_wifi_state = WIFI_STATE_ONLINE;
            ip_napt_enable((u32_t)WiFi.localIP(), 1);
            sendStatus("online");
            break;
        case ARDUINO_EVENT_WIFI_STA_DISCONNECTED:
            onWifiDisconnect(e, info);
            break;
        default: break;
        }
    });

    WiFi.mode(WIFI_STA);
    WiFi.setSleep(false);        // Disable modem sleep for low latency and fast connection
    WiFi.setAutoReconnect(true); // Auto-reconnect if dropped

    // Setup SLIP / IP forwarding
    setupIpForwarding();
    g_slip_active = true;

    // Start serial RX task on core 0, Wi-Fi runs on core 0 by default
    xTaskCreatePinnedToCore(serialRxTask, "slip_rx", 8192, NULL, 5, NULL, 1);

    // Announce ready
    StaticJsonDocument<128> ready;
    ready["type"]   = "ready";
    ready["fw"]     = FW_VERSION;
    ready["chip"]   = "ESP32-WROOM-32";
    sendControl(ready);
}

// ─── Arduino Loop ─────────────────────────────────────────────────────────────
void loop() {
    // Connection watchdog: if stuck in connecting for >25s without outcome, timeout
    if (g_wifi_state == WIFI_STATE_CONNECTING && g_connect_start_ms > 0) {
        if (millis() - g_connect_start_ms > 25000) {
            g_connect_start_ms = 0;
            WiFi.disconnect(false, false);
            g_wifi_state = WIFI_STATE_DISCONNECTED;
            sendStatus("disconnected", "timeout");
        }
    }

    // Heartbeat every 5 seconds
    static unsigned long lastHB = 0;
    unsigned long now = millis();
    if (now - lastHB > 5000) {
        lastHB = now;
        StaticJsonDocument<128> hb;
        hb["type"]  = "heartbeat";
        hb["state"] = stateToString(g_wifi_state);
        hb["rssi"]  = (g_wifi_state == WIFI_STATE_ONLINE) ? WiFi.RSSI() : 0;
        hb["heap"]  = ESP.getFreeHeap();
        sendControl(hb);
    }
    delay(100);
}
