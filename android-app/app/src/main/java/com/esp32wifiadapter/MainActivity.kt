package com.esp32wifiadapter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelProvider
import com.esp32wifiadapter.ui.theme.ESP32WifiAdapterTheme
import com.esp32wifiadapter.ui.screens.MainScreen
import com.esp32wifiadapter.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel
    private lateinit var vpnPermissionLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel = ViewModelProvider(
            this,
            MainViewModel.factory(application)
        )[MainViewModel::class.java]

        // Register VPN permission result BEFORE any composable is set
        vpnPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                viewModel.onVpnPermissionGranted()
            }
        }

        setContent {
            ESP32WifiAdapterTheme {
                val uiState by viewModel.uiState.collectAsState()

                MainScreen(
                    uiState        = uiState,
                    onScan         = viewModel::requestScan,
                    onConnect      = viewModel::connectToNetwork,
                    onDisconnect   = viewModel::disconnect,
                    onRequestVpn   = { intent ->
                        vpnPermissionLauncher.launch(intent)
                    }
                )

                // Trigger VPN consent when needed
                androidx.compose.runtime.LaunchedEffect(uiState.pendingVpnIntent) {
                    uiState.pendingVpnIntent?.let { intent ->
                        vpnPermissionLauncher.launch(intent)
                        viewModel.onVpnIntentLaunched()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // Handle USB attach event when app is already running
        if (::viewModel.isInitialized) {
            viewModel.handleUsbIntent(intent)
        }
    }
}
