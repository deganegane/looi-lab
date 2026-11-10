package io.github.deganegane.looilab

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    private lateinit var ble: LooiBle
    private val face = FaceState()

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
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        ble = LooiBle(applicationContext)
        setContent {
            var faceMode by remember { mutableStateOf(false) }

            // The face reacts to what the base reports.
            LaunchedEffect(Unit) {
                var lastCurious = 0L
                ble.events.collect { e ->
                    val type = e.firstOrNull()?.toInt()?.and(0xFF) ?: return@collect
                    val v = e.getOrNull(1)?.toInt() ?: 0
                    when {
                        type in 0x09..0x0B && v == 1 -> face.flash(Expr.SURPRISED)
                        type == 0x01 && System.currentTimeMillis() - lastCurious > 4_000 -> {
                            lastCurious = System.currentTimeMillis()
                            face.flash(Expr.CURIOUS)
                        }
                    }
                }
            }

            if (faceMode) {
                FullscreenFace(onExit = { faceMode = false })
            } else {
                LabScreen(
                    ble = ble,
                    face = face,
                    onConnect = {
                        if (hasPermissions()) ble.scanAndConnect() else askPermissions.launch(permissions)
                    },
                    onFullscreen = { faceMode = true },
                )
            }
        }
    }

    /** The robot's face: landscape, no system bars, screen kept on. Tap = happy nod, long press = love. */
    @androidx.compose.runtime.Composable
    private fun FullscreenFace(onExit: () -> Unit) {
        BackHandler(onBack = onExit)
        DisposableEffect(Unit) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val bars = WindowCompat.getInsetsController(window, window.decorView)
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
            onDispose {
                bars.show(WindowInsetsCompat.Type.systemBars())
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
        NixieFace(
            face,
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        face.flash(Expr.HAPPY)
                        ble.launch { playScript(CapturedAnims.all[1].keyframes, "dodir → klimanje") }
                    },
                    onLongPress = {
                        face.flash(Expr.LOVE)
                        ble.launch { playScript(CapturedAnims.all[21].keyframes, "dugi dodir → gore, kimne") }
                    },
                )
            },
        )
    }

    override fun onDestroy() {
        if (isFinishing) ble.disconnect()
        super.onDestroy()
    }
}
