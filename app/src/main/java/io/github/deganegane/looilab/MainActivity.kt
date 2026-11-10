package io.github.deganegane.looilab

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var ble: LooiBle

    private val permissions = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) ble.scanAndConnect()
            else ble.note("!!", "bez Bluetooth dozvole ne mogu do robota")
        }

    private fun hasPermissions() = permissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ble = LooiBle(applicationContext)
        setContent {
            LabScreen(
                ble = ble,
                onConnect = {
                    if (hasPermissions()) ble.scanAndConnect() else askPermissions.launch(permissions)
                },
            )
        }
    }

    override fun onDestroy() {
        if (isFinishing) ble.disconnect()
        super.onDestroy()
    }
}
