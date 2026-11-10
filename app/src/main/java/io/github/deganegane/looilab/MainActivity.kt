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
import androidx.lifecycle.lifecycleScope
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var ble: LooiBle
    private lateinit var brain: StevoBrain
    private lateinit var eyes: CameraEyes
    private lateinit var tracking: Tracking
    private val face = FaceState()

    private val askCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) applySettings() else ble.note("!!", "bez kamere nema praćenja lica ni slikanja")
        }

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** Camera on when tracking or seeing is wanted; screen kept on if asked. */
    private fun applySettings() {
        if (brain.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val wantCamera = brain.tracking || brain.vision
        if (wantCamera && !hasCamera()) { askCamera.launch(Manifest.permission.CAMERA); return }
        if (wantCamera) eyes.start(this) else eyes.stop()
        if (brain.tracking && wantCamera) tracking.start(lifecycleScope) else tracking.stop()
    }

    private fun photo() {
        if (!eyes.running) {
            if (!hasCamera()) askCamera.launch(Manifest.permission.CAMERA)
            else ble.note("!!", "uključi praćenje lica ili \"Stevo vidi\" pa slikaj")
            return
        }
        face.flash(Expr.SURPRISED)
        eyes.takePhoto { f ->
            if (f != null) { ble.note("··", "📷 spremljeno: Pictures/Stevo/$f"); face.flash(Expr.HAPPY) }
            else ble.note("!!", "fotografija nije uspjela")
        }
    }

    private val askMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) brain.start() else ble.note("!!", "bez mikrofona Stevo ne čuje")
        }

    /** Start or end a conversation (asks for the mic the first time). */
    private fun talk() {
        if (brain.state.value != TalkState.OFF) brain.stop("· razgovor prekinut")
        else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) brain.start()
        else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

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
        eyes = CameraEyes(applicationContext)
        brain = StevoBrain(applicationContext, face, ble, eyes) { done ->
            if (eyes.running) eyes.takePhoto(done) else done(null)
        }
        tracking = Tracking(eyes, face, ble, mirror = { brain.mirror }, busy = { ble.manualDrive })
        applySettings()
        setContent {
            var faceMode by remember { mutableStateOf(false) }

            // The face reacts to what the base reports.
            //  - 0A 01 / 0A 00: lifted / put down (confirmed 2026-11-10);
            //  - 01 a b c d: four floor sensors, 01 = surface under it, 00 = air.
            LaunchedEffect(Unit) {
                val r = Reactions(face) { block -> launch { block() } }
                ble.events.collect { r.on(it) }
            }

            if (faceMode) {
                FullscreenFace(onExit = { faceMode = false })
            } else {
                LabScreen(
                    ble = ble,
                    face = face,
                    brain = brain,
                    eyes = eyes,
                    onTalk = { talk() },
                    onSettings = { applySettings() },
                    onPhoto = { photo() },
                    onConnect = {
                        if (hasPermissions()) ble.scanAndConnect() else askPermissions.launch(permissions)
                    },
                    onFullscreen = { faceMode = true },
                )
            }
        }
    }

    /** The robot's face: landscape, no system bars, screen kept on. Tap = talk to Stevo, long press = love. */
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
                if (!brain.keepScreenOn) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
        NixieFace(
            face,
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(
                    onTap = { talk() },
                    onLongPress = {
                        face.flash(Expr.LOVE)
                        ble.launch { playScript(CapturedAnims.all[21].keyframes, "dugi dodir → gore, kimne") }
                    },
                )
            },
        )
    }

    override fun onDestroy() {
        if (isFinishing) { brain.stop(""); tracking.stop(); eyes.stop(); ble.disconnect() }
        super.onDestroy()
    }
}
