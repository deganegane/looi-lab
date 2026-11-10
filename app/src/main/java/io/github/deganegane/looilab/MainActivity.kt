package io.github.deganegane.looilab

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Screen { FACE, SETTINGS, MEMORY, DIAGNOSTICS }

class MainActivity : ComponentActivity() {
    private lateinit var ble: LooiBle
    private lateinit var brain: StevoBrain
    private lateinit var eyes: CameraEyes
    private lateinit var tracking: Tracking
    private lateinit var memory: Memory
    private lateinit var life: Life
    private val face = FaceState()
    private var screen by mutableStateOf(Screen.FACE)

    // ------------------------------------------------------------------ permissions

    private val btPermissions = if (Build.VERSION.SDK_INT >= 31)
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun hasBt() = btPermissions.all { granted(it) }
    private fun hasCamera() = granted(Manifest.permission.CAMERA)

    /** First start: everything at once (Bluetooth, camera, mic), so there is only one round of questions. */
    private val askStartup = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        applySettings(); if (hasBt()) ble.scanAndConnect()
    }
    private val askBt = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasBt()) ble.scanAndConnect() else ble.note("!!", "bez Bluetooth dozvole ne mogu do robota")
    }
    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) applySettings() else ble.note("!!", "bez kamere nema praćenja lica ni slikanja")
    }
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) brain.start() else ble.note("!!", "bez mikrofona Stevo ne čuje")
    }

    private val exportMemory = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) contentResolver.openOutputStream(uri)?.use { it.write(memory.export().toByteArray()) }
    }
    private val importMemory = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ble.note("··", if (text != null && memory.import(text)) "pamćenje uvezeno" else "uvoz nije uspio")
        }
    }

    private fun connect() { if (hasBt()) ble.scanAndConnect() else askBt.launch(btPermissions.toTypedArray()) }

    // ------------------------------------------------------------------ actions

    /** Tap on the face: wake up and talk, or end the conversation. */
    private fun talk() {
        life.wake()
        if (brain.state.value != TalkState.OFF) brain.stop("· razgovor prekinut")
        else if (granted(Manifest.permission.RECORD_AUDIO)) brain.start()
        else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun photo() {
        life.touched()
        if (!eyes.running) {
            if (!hasCamera()) askCamera.launch(Manifest.permission.CAMERA)
            else ble.note("!!", "uključi praćenje lica ili \"Stevo vidi\" pa slikaj")
            return
        }
        face.flash(Expr.SURPRISED)
        eyes.takePhoto { f ->
            if (f != null) { ble.note("··", "📷 Pictures/Stevo/$f"); face.flash(Expr.HAPPY) }
            else ble.note("!!", "fotografija nije uspjela")
        }
    }

    /** Opens Stevo's newest photo in the phone's viewer (swipe for the others). */
    private fun openPhotos() {
        val proj = arrayOf(MediaStore.Images.Media._ID)
        val sel: String; val args: Array<String>
        if (Build.VERSION.SDK_INT >= 29) { sel = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"; args = arrayOf("Pictures/Stevo%") }
        else { sel = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"; args = arrayOf("Stevo_%") }
        val id = contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, sel, args,
            "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        if (id == null) { face.flash(Expr.CURIOUS); ble.note("··", "još nema fotografija"); return }
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
    }

    /** Camera on when tracking or seeing is wanted; screen kept on if asked (always on the face). */
    private fun applySettings() {
        if (brain.keepScreenOn || screen == Screen.FACE) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val wantCamera = (brain.tracking || brain.vision) && hasCamera()
        if (wantCamera) eyes.start(this) else eyes.stop()
        if (brain.tracking && wantCamera) tracking.start(lifecycleScope) else tracking.stop()
    }

    // ------------------------------------------------------------------ screens

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= 30) display?.rotation ?: 0 else windowManager.defaultDisplay.rotation

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        eyes.rotation = displayRotation()
    }

    /** The face is landscape and fullscreen (the phone lies on the robot); the rest follows the phone. */
    private fun showScreen(s: Screen) {
        screen = s
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        if (s == Screen.FACE) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            requestedOrientation = if (s == Screen.DIAGNOSTICS) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
        applySettings()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ble = LooiBle(applicationContext)
        eyes = CameraEyes(applicationContext)
        memory = Memory(applicationContext) { brain.apiKey }
        brain = StevoBrain(applicationContext, face, ble, eyes, memory,
            takePhoto = { done -> if (eyes.running) eyes.takePhoto(done) else done(null) },
            onIdle = { life.sleep() })
        life = Life(face, ble, eyes, talking = { brain.state.value != TalkState.OFF })
        eyes.rotation = displayRotation()
        tracking = Tracking(eyes, face, ble, mirror = { brain.mirror },
            bodyAllowed = { screen == Screen.FACE && !life.sleeping })
        ble.onReady = { if (!brain.phoneCharging) setCharging(false) }

        val reactions = Reactions(face) { block -> lifecycleScope.launch { block() } }
        lifecycleScope.launch {
            ble.events.collect { e ->
                val type = e.firstOrNull()?.toInt()
                if (type == 0x0A && e.getOrNull(1)?.toInt() == 1) life.wake()
                if (!life.sleeping) reactions.on(e)
            }
        }
        life.start(lifecycleScope)
        // Keep trying to reach the robot while the face is shown.
        lifecycleScope.launch {
            while (true) {
                delay(15_000)
                if (screen == Screen.FACE && hasBt() &&
                    (ble.state.value == ConnState.IDLE || ble.state.value == ConnState.DISCONNECTED)) ble.scanAndConnect()
            }
        }

        val missing = (btPermissions + Manifest.permission.CAMERA + Manifest.permission.RECORD_AUDIO).filterNot { granted(it) }
        if (missing.isEmpty()) ble.scanAndConnect() else askStartup.launch(missing.toTypedArray())
        showScreen(Screen.FACE)

        setContent {
            StevoTheme {
                Surface(Modifier.fillMaxSize()) {
                    BackHandler(enabled = screen != Screen.FACE) {
                        showScreen(if (screen == Screen.MEMORY) Screen.SETTINGS else Screen.FACE)
                    }
                    when (screen) {
                        Screen.FACE -> HomeFace(
                            face, ble, brain, life,
                            onTalk = { talk() },
                            onPhoto = { photo() },
                            onPhotos = { openPhotos() },
                            onSettings = { showScreen(Screen.SETTINGS) },
                            onConnect = { connect() },
                        )
                        Screen.SETTINGS -> SettingsScreen(
                            ble, brain, memory,
                            onBack = { showScreen(Screen.FACE) },
                            onConnect = { connect() },
                            onApply = {
                                if ((brain.tracking || brain.vision) && !hasCamera()) askCamera.launch(Manifest.permission.CAMERA)
                                else applySettings()
                            },
                            onPhoto = { photo() },
                            onMemory = { showScreen(Screen.MEMORY) },
                            onDiagnostics = { showScreen(Screen.DIAGNOSTICS) },
                            onCharging = { on -> ble.launch { setCharging(on) } },
                        )
                        Screen.MEMORY -> MemoryScreen(
                            memory,
                            onBack = { showScreen(Screen.SETTINGS) },
                            onExport = { exportMemory.launch("stevo-pamcenje.json") },
                            onImport = { importMemory.launch(arrayOf("application/json", "*/*")) },
                        )
                        Screen.DIAGNOSTICS -> LabScreen(
                            ble = ble, face = face, brain = brain, eyes = eyes,
                            onTalk = { talk() },
                            onConnect = { connect() },
                            onFullscreen = { showScreen(Screen.SETTINGS) },
                            onSettings = { applySettings() },
                            onPhoto = { photo() },
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) { brain.stop(""); tracking.stop(); eyes.stop(); ble.disconnect() }
        super.onDestroy()
    }
}
