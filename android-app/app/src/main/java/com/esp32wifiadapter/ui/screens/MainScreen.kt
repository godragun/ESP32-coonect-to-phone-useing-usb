package com.esp32wifiadapter.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import com.esp32wifiadapter.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.esp32wifiadapter.protocol.WifiNetwork
import com.esp32wifiadapter.viewmodel.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    uiState:       UiState,
    onScan:        () -> Unit,
    onConnect:     (ssid: String, pass: String) -> Unit,
    onDisconnect:  () -> Unit,
    onRequestVpn:  (Intent) -> Unit
) {
    var selectedNetwork by remember { mutableStateOf<WifiNetwork?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Top Bar ───────────────────────────────────────────────────────
            TopBar(uiState = uiState)

            // ── USB Status Banner ─────────────────────────────────────────────
            UsbStatusBanner(uiState = uiState)

            // ── Connection Status Card ────────────────────────────────────────
            AnimatedVisibility(visible = uiState.connectionState != ConnectionState.Idle) {
                ConnectionStatusCard(uiState = uiState, onDisconnect = onDisconnect)
            }

            // ── Network List ──────────────────────────────────────────────────
            NetworkList(
                uiState        = uiState,
                onScan         = onScan,
                onNetworkClick = { net -> selectedNetwork = net }
            )
        }

        // ── Error Snackbar ────────────────────────────────────────────────────
        uiState.errorMessage?.let { msg ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.errorContainer
            ) {
                Text(msg, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }

    // ── Connect Dialog ────────────────────────────────────────────────────────
    selectedNetwork?.let { net ->
        ConnectDialog(
            network    = net,
            isConnecting = uiState.connectionState == ConnectionState.Connecting && uiState.connectedSsid == net.ssid,
            onDismiss  = { selectedNetwork = null },
            onConnect  = { pass ->
                selectedNetwork = null
                onConnect(net.ssid, pass)
            }
        )
    }
}

// ─── Top Bar ──────────────────────────────────────────────────────────────────
@Composable
private fun TopBar(uiState: UiState) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter            = painterResource(id = R.drawable.app_logo),
                contentDescription = "ESP32 Wi-Fi Adapter Logo",
                modifier           = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale       = ContentScale.Crop
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text       = "ESP32 Wi-Fi",
                    style      = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color      = MaterialTheme.colorScheme.primary
                )
                Text(
                    text  = if (uiState.fwVersion.isNotBlank()) "FW ${uiState.fwVersion}" else "External Adapter",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Spacer(Modifier.weight(1f))
            // Heap indicator when connected
            if (uiState.freeHeap > 0) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text  = "${uiState.freeHeap / 1024}K",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text  = "heap",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }
}

// ─── USB Status Banner ────────────────────────────────────────────────────────
@Composable
private fun UsbStatusBanner(uiState: UiState) {
    val (bgColor, icon, label) = when (uiState.usbStatus) {
        UsbStatus.Disconnected -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            Icons.Outlined.UsbOff,
            "ESP32 not connected — plug in via USB OTG adapter"
        )
        UsbStatus.Connecting   -> Triple(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            Icons.Outlined.Usb,
            "Connecting to ESP32…"
        )
        UsbStatus.Connected    -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            Icons.Filled.Usb,
            "ESP32 connected"
        )
        UsbStatus.Error        -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            Icons.Filled.ErrorOutline,
            "USB error — check cable and OTG adapter"
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = null,
            tint               = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier           = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text  = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (uiState.vpnActive) {
            Spacer(Modifier.weight(1f))
            Surface(
                color  = MaterialTheme.colorScheme.primary,
                shape  = RoundedCornerShape(50),
                modifier = Modifier.padding(2.dp)
            ) {
                Text(
                    text     = "VPN ON",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style    = MaterialTheme.typography.labelSmall,
                    color    = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ─── Connection Status Card ───────────────────────────────────────────────────
@Composable
private fun ConnectionStatusCard(uiState: UiState, onDisconnect: () -> Unit) {
    val state = uiState.connectionState
    val (color, icon, label, sublabel) = when (state) {
        ConnectionState.Connecting   -> Quad(
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f),
            Icons.Outlined.SignalWifiStatusbarConnectedNoInternet4,
            "Connecting…",
            "Authenticating with ${uiState.connectedSsid}"
        )
        ConnectionState.Associated   -> Quad(
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f),
            Icons.Outlined.Wifi,
            "Associated",
            "Getting IP address…"
        )
        ConnectionState.GettingIp    -> Quad(
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f),
            Icons.Outlined.Wifi,
            "Obtaining IP",
            "DHCP in progress…"
        )
        ConnectionState.Online       -> Quad(
            MaterialTheme.colorScheme.primaryContainer,
            Icons.Filled.Wifi,
            "Connected · Internet ready",
            "${uiState.connectedSsid}  •  ${uiState.connectedIp}  •  ${uiState.signalRssi} dBm"
        )
        ConnectionState.AuthFail     -> Quad(
            MaterialTheme.colorScheme.errorContainer,
            Icons.Filled.WifiOff,
            "Authentication failed",
            "Incorrect password or rejected by AP"
        )
        ConnectionState.NoAp         -> Quad(
            MaterialTheme.colorScheme.errorContainer,
            Icons.Filled.WifiOff,
            "Network unavailable",
            "AP not in range or powered off"
        )
        ConnectionState.Disconnected -> Quad(
            MaterialTheme.colorScheme.surfaceVariant,
            Icons.Outlined.WifiOff,
            "Disconnected",
            "Select a network to reconnect"
        )
        ConnectionState.UsbError     -> Quad(
            MaterialTheme.colorScheme.errorContainer,
            Icons.Outlined.UsbOff,
            "USB disconnected",
            "Reconnect the ESP32 device"
        )
        else -> Quad(Color.Transparent, Icons.Outlined.Wifi, "", "")
    }

    if (label.isEmpty()) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = color),
        shape  = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Pulsing dot for active states
            if (state == ConnectionState.Connecting || state == ConnectionState.GettingIp) {
                PulsingDot(MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(12.dp))
            } else {
                Icon(
                    imageVector        = icon,
                    contentDescription = null,
                    tint               = MaterialTheme.colorScheme.primary,
                    modifier           = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = label,
                    style      = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color      = MaterialTheme.colorScheme.onSurface
                )
                if (sublabel.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text     = sublabel,
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (state == ConnectionState.Online || state == ConnectionState.Associated) {
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onDisconnect) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Disconnect",
                        tint               = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

// ─── Network List ─────────────────────────────────────────────────────────────
@Composable
private fun NetworkList(
    uiState:        UiState,
    onScan:         () -> Unit,
    onNetworkClick: (WifiNetwork) -> Unit
) {
    Column {
        // Header row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text       = "Available Networks",
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color      = MaterialTheme.colorScheme.onBackground,
                modifier   = Modifier.weight(1f)
            )
            AnimatedContent(
                targetState = uiState.scanStatus,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label          = "scan_btn"
            ) { status ->
                when (status) {
                    ScanStatus.Scanning -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier  = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color     = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Scanning…", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    else -> {
                        val enabled = uiState.usbStatus == UsbStatus.Connected &&
                                uiState.scanStatus != ScanStatus.Scanning
                        FilledTonalIconButton(
                            onClick  = onScan,
                            enabled  = enabled
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Scan")
                        }
                    }
                }
            }
        }

        Divider(
            color     = MaterialTheme.colorScheme.surfaceVariant,
            thickness = 1.dp,
            modifier  = Modifier.padding(horizontal = 16.dp)
        )

        // List or placeholder
        when {
            uiState.usbStatus == UsbStatus.Disconnected -> {
                PlaceholderMessage(
                    icon    = Icons.Outlined.UsbOff,
                    message = "Connect the ESP32 to see\nnearby Wi-Fi networks"
                )
            }
            uiState.scanStatus == ScanStatus.Scanning -> {
                PlaceholderMessage(
                    icon    = Icons.Outlined.Search,
                    message = "Scanning for networks…"
                )
            }
            uiState.networks.isEmpty() && uiState.scanStatus == ScanStatus.Done -> {
                PlaceholderMessage(
                    icon    = Icons.Outlined.WifiOff,
                    message = "No networks found\nTap refresh to scan again"
                )
            }
            uiState.networks.isEmpty() -> {
                HeroWelcome(
                    message = "Ready to connect.\nTap the scan button above to discover networks."
                )
            }
            else -> {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(uiState.networks, key = { it.bssid }) { network ->
                        NetworkCard(
                            network  = network,
                            isActive = network.ssid == uiState.connectedSsid &&
                                       uiState.connectionState == ConnectionState.Online,
                            onClick  = { onNetworkClick(network) }
                        )
                    }
                }
            }
        }
    }
}

// ─── Network Card ─────────────────────────────────────────────────────────────
@Composable
private fun NetworkCard(
    network:  WifiNetwork,
    isActive: Boolean,
    onClick:  () -> Unit
) {
    val bgColor = if (isActive) MaterialTheme.colorScheme.primaryContainer
                  else         MaterialTheme.colorScheme.surface

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape  = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(if (isActive) 4.dp else 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Signal icon
            SignalIcon(level = network.signalLevel, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = network.ssid.ifBlank { "<hidden>" },
                    style      = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                    color      = MaterialTheme.colorScheme.onSurface,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text  = "${network.rssi} dBm  •  ch ${network.channel}  •  ${network.securityLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            if (!network.isOpen) {
                Icon(
                    imageVector        = Icons.Filled.Lock,
                    contentDescription = "Secured",
                    tint               = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier           = Modifier.size(16.dp)
                )
            }
            if (isActive) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector        = Icons.Filled.CheckCircle,
                    contentDescription = "Connected",
                    tint               = MaterialTheme.colorScheme.primary,
                    modifier           = Modifier.size(20.dp)
                )
            }
        }
    }
}

// ─── Connect Dialog ───────────────────────────────────────────────────────────
@Composable
private fun ConnectDialog(
    network:      WifiNetwork,
    isConnecting: Boolean,
    onDismiss:    () -> Unit,
    onConnect:    (password: String) -> Unit
) {
    var password      by remember { mutableStateOf("") }
    var showPassword  by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape  = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text       = "Connect to Network",
                    style      = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color      = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text  = network.ssid,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text  = "${network.securityLabel}  •  ${network.rssi} dBm",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(20.dp))

                if (!network.isOpen) {
                    OutlinedTextField(
                        value         = password,
                        onValueChange = { password = it },
                        label         = { Text("Password") },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth(),
                        visualTransformation = if (showPassword)
                            VisualTransformation.None
                        else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password
                        ),
                        trailingIcon  = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    imageVector = if (showPassword)
                                        Icons.Filled.VisibilityOff
                                    else Icons.Filled.Visibility,
                                    contentDescription = if (showPassword) "Hide" else "Show"
                                )
                            }
                        }
                    )
                } else {
                    Text(
                        "This is an open network. No password needed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                Spacer(Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick  = { onConnect(password) },
                        enabled  = !isConnecting && (network.isOpen || password.isNotBlank()),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isConnecting) {
                            CircularProgressIndicator(
                                modifier    = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color       = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text("Connect")
                        }
                    }
                }
            }
        }
    }
}

// ─── Supporting Composables ───────────────────────────────────────────────────
@Composable
private fun HeroWelcome(message: String) {
    Column(
        modifier            = Modifier
            .fillMaxWidth()
            .padding(36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter            = painterResource(id = R.drawable.app_logo),
            contentDescription = "ESP32 Wi-Fi Adapter",
            modifier           = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(22.dp)),
            contentScale       = ContentScale.Crop
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text       = "ESP32 Wi-Fi Adapter",
            style      = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color      = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text      = message,
            style     = MaterialTheme.typography.bodyMedium,
            color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun PlaceholderMessage(icon: ImageVector, message: String) {
    Column(
        modifier            = Modifier
            .fillMaxWidth()
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = null,
            tint               = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            modifier           = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text      = message,
            style     = MaterialTheme.typography.bodyMedium,
            color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun SignalIcon(level: Int, tint: Color) {
    val alpha = when {
        level >= 80 -> 1.0f
        level >= 60 -> 0.8f
        level >= 40 -> 0.6f
        level >= 20 -> 0.4f
        else        -> 0.25f
    }
    Icon(
        imageVector = Icons.Filled.Wifi,
        contentDescription = "Signal: $level%",
        tint = tint.copy(alpha = alpha),
        modifier = Modifier.size(24.dp)
    )
}

@Composable
private fun PulsingDot(color: Color) {
    val inf = rememberInfiniteTransition(label = "pulse")
    val scale by inf.animateFloat(
        initialValue   = 0.7f,
        targetValue    = 1.3f,
        animationSpec  = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label          = "dot_scale"
    )
    Box(
        modifier = Modifier
            .size(24.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(color)
    )
}

// Utility to destructure 4-tuples in `when` expressions
private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
private operator fun <A, B, C, D> Quad<A, B, C, D>.component1() = a
private operator fun <A, B, C, D> Quad<A, B, C, D>.component2() = b
private operator fun <A, B, C, D> Quad<A, B, C, D>.component3() = c
private operator fun <A, B, C, D> Quad<A, B, C, D>.component4() = d
