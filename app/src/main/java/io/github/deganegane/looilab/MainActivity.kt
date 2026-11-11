package io.github.deganegane.looilab

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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

enum class Screen { FACE, SETTINGS, DIAGNOSTICS }

class MainActivity : ComponentActivity() {
    private lateinit var ble: LooiBle
    private lateinit var brain: StevoBrain
    private lateinit var eyes: CameraEyes
    private lateinit var tracking: Tracking
    private lateinit var memory: Memory
    private lateinit var life: Life
    private lateinit var body: BodyLanguage
    private lateinit var autonomy: Autonomy
    private lateinit var local: LocalBrain
    private lateinit var reactions: Reactions
    private val face = FaceState()

    /** The phone's ambient light sensor (lux), for the lamp; -1 if the phone has none. */
    @Volatile private var lux = -1f
    private val lightListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) { lux = e.values[0] }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    override fun onResume() {
        super.onResume()
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sm.registerListener(lightListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    override fun onPause() {
        (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(lightListener)
        super.onPause()
    }
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
        if (ok) startBrain() else ble.note("!!", "bez mikrofona Stevo ne čuje")
    }

    private val exportMemory = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) contentResolver.openOutputStream(uri)?.use { it.write(memory.export().toByteArray()) }
    }
    private val importModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) local.importModel(uri)
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
        life.wake(); autonomy.excite()
        if (brain.state.value != TalkState.OFF) { if (local.running) local.stop("· razgovor prekinut") else brain.stop("· razgovor prekinut") }
        else if (granted(Manifest.permission.RECORD_AUDIO)) startBrain()
        else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    /** Gemini, the model on the phone, or Gemini with the phone as the fallback. */
    private fun startBrain() {
        when (brain.brainMode) {
            "local" -> if (local.modelReady) local.start() else face.say("Preuzmi model: Postavke → Mozak")
            else -> brain.start()
        }
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
            if (f != null) { ble.note("··", "📷 Pictures/Stevo/$f"); face.flash(Expr.HAPPY); face.say("Slika spremljena") }
            else { ble.note("!!", "fotografija nije uspjela"); face.say("Slika nije uspjela") }
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
        if (id == null) { face.flash(Expr.CURIOUS); face.say("Još nema fotografija"); return }
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
            // Settings turn with the phone (also when it lies on the robot); diagnostics stay upright.
            requestedOrientation = if (s == Screen.DIAGNOSTICS) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
        applySettings()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ble = LooiBle(applicationContext)
        eyes = CameraEyes(applicationContext)
        memory = Memory(applicationContext, apiKey = { brain.apiKey }, log = { ble.note("··", it) })
        brain = StevoBrain(applicationContext, face, ble, eyes, memory,
            takePhoto = { done -> if (eyes.running) eyes.takePhoto(done) else done(null) },
            onIdle = { life.sleep() },
            onExpression = { body.onExpression(it) })
        local = LocalBrain(applicationContext, brain, face, ble, memory,
            takePhoto = { done -> if (eyes.running) eyes.takePhoto(done) else done(null) },
            onIdle = { life.sleep() },
            onExpression = { body.onExpression(it) })
        // Gemini out of quota, offline or without a key: the phone's own model takes over.
        brain.onFailed = {
            if (brain.brainMode == "auto" && local.modelReady) runOnUiThread {
                face.say("Gemini nije dostupan, razmišljam sam")
                local.start()
            }
        }
        life = Life(face, ble, eyes, talking = { brain.state.value != TalkState.OFF }, onWake = { autonomy.excite() })
        reactions = Reactions(face) { block -> lifecycleScope.launch { block() } }
        autonomy = Autonomy(ble, face, eyes, mode = { brain.autonomy },
            free = { screen == Screen.FACE && !life.sleeping && brain.state.value == TalkState.OFF && !reactions.lifted })
        autonomy.start(lifecycleScope)
        val edge = EdgeReflex(ble, face) {
            autonomy.edgeAt = System.currentTimeMillis()
            face.say("Ups, rub stola!")
            brain.notify("Your floor sensors: you just reached the edge of the table, stopped and rolled back. Do not go further that way.")
        }
        body = BodyLanguage(ble, level = { brain.liveliness }, speaking = { brain.state.value == TalkState.SPEAKING }, asleep = { life.sleeping })
        body.start(lifecycleScope)
        eyes.rotation = displayRotation()
        tracking = Tracking(eyes, face, ble, mirror = { brain.mirror },
            bodyAllowed = { screen == Screen.FACE && !life.sleeping })
        ble.onReady = { if (brain.lightMode == "on") setLight(true) }

        lifecycleScope.launch {
            ble.events.collect { e ->
                val type = e.firstOrNull()?.toInt()
                if (type == 0x0A && e.getOrNull(1)?.toInt() == 1) life.wake()
                if (type == 0x01) edge.check()
                if (!life.sleeping) reactions.on(e)
            }
        }
        // What happens shows up under the face.
        lifecycleScope.launch {
            var before = ble.state.value
            ble.state.collect { st ->
                if (st == ConnState.READY && before != ConnState.READY) face.say("Robot spojen")
                if (st == ConnState.DISCONNECTED && before == ConnState.READY) face.say("Robot odspojen")
                before = st
            }
        }
        lifecycleScope.launch {
            brain.transcript.collect { lines -> lines.lastOrNull()?.takeIf { it.startsWith("⚠") }?.let { face.say(it.take(90)) } }
        }
        life.start(lifecycleScope)
        // The lamp in "auto": on after a few seconds of darkness, off once the room is lit.
        // The phone's light sensor measures real light (the camera adjusts its exposure to the
        // dark, so its picture never looks dark); the camera is only the fallback.
        lifecycleScope.launch {
            var dark = 0; var bright = 0
            while (true) {
                delay(1_000)
                if (brain.lightMode != "auto" || ble.state.value != ConnState.READY) { dark = 0; bright = 0; continue }
                val l = lux
                val isDark: Boolean; val isBright: Boolean
                if (l >= 0) { isDark = l < 6f; isBright = l > 120f } // the lamp itself adds a little light
                else if (eyes.running && eyes.brightness >= 0) { isDark = eyes.brightness < 35; isBright = eyes.brightness > 150 }
                else continue
                if (!ble.lightOn) {
                    dark = if (isDark) dark + 1 else 0
                    if (dark >= 3 && !life.sleeping) { dark = 0; ble.launch { setLight(true) } }
                } else {
                    bright = if (isBright) bright + 1 else 0
                    if (bright >= 8 || life.sleeping) { bright = 0; ble.launch { setLight(false) } }
                }
            }
        }
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
                        showScreen(if (screen == Screen.DIAGNOSTICS) Screen.SETTINGS else Screen.FACE)
                    }
                    when (screen) {
                        Screen.FACE -> HomeFace(
                            face, ble, brain, life,
                            onTalk = { talk() },
                            onPhoto = { photo() },
                            onPhotos = { openPhotos() },
                            onSettings = { showScreen(Screen.SETTINGS) },
                            onConnect = { connect() },
                            onTrick = { life.wake(); lifecycleScope.launch { autonomy.trick() } },
                        )
                        Screen.SETTINGS -> SettingsScreen(ble, brain, memory, local, SettingsActions(
                            onImportModel = { importModel.launch(arrayOf("*/*")) },
                            onBack = { showScreen(Screen.FACE) },
                            onConnect = { connect() },
                            onApply = {
                                if ((brain.tracking || brain.vision) && !hasCamera()) askCamera.launch(Manifest.permission.CAMERA)
                                else applySettings()
                            },
                            onPhoto = { photo() },
                            onDiagnostics = { showScreen(Screen.DIAGNOSTICS) },
                            onLight = { mode -> ble.launch { if (mode != "auto") setLight(mode == "on") } },
                            onExport = { exportMemory.launch("stevo-pamcenje.json") },
                            onImport = { importMemory.launch(arrayOf("application/json", "*/*")) },
                        ))
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
        if (isFinishing) { local.stop(""); local.release(); brain.stop(""); tracking.stop(); eyes.stop(); ble.disconnect() }
        super.onDestroy()
    }
}
