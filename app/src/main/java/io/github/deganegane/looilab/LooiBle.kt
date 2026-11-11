package io.github.deganegane.looilab

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class ConnState { IDLE, SCANNING, CONNECTING, READY, DISCONNECTED }

data class LogLine(val time: String, val dir: String, val what: String, val hex: String, val meaning: String)

/**
 * Talks to the LOOI base over BLE. Android allows one GATT operation at a time, so every
 * operation goes through [op], which waits for its callback before the next one starts.
 * Permissions are checked by MainActivity before any of this is called.
 */
@SuppressLint("MissingPermission")
class LooiBle(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val _state = MutableStateFlow(ConnState.IDLE)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<LogLine>>(emptyList())
    val log: StateFlow<List<LogLine>> = _log.asStateFlow()

    /** Latest decoded reading per event type, for the live sensor panel. */
    private val _sensors = MutableStateFlow<Map<String, String>>(emptyMap())
    val sensors: StateFlow<Map<String, String>> = _sensors.asStateFlow()

    /** Every FED9 notification, for whoever wants to react (the face). */
    private val _events = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val events: SharedFlow<ByteArray> = _events

    /** True while the joystick drives: face tracking keeps its hands off the motors. */
    @Volatile var manualDrive = false

    var deviceName: String? = null
        private set

    private var gatt: BluetoothGatt? = null
    private val opLock = Mutex()
    private var pending: CompletableDeferred<ByteArray?>? = null
    private var keepAlive: Job? = null
    private var scanTimeout: Job? = null

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    private fun nameOf(uuid: UUID): String =
        LooiProtocol.ALL_KNOWN.firstOrNull { it.second == uuid }?.first
            ?: uuid.toString().substring(4, 8).uppercase()

    fun note(dir: String, what: String, bytes: ByteArray = ByteArray(0), meaning: String = "") {
        val line = LogLine(timeFmt.format(Date()), dir, what, bytes.hex(), meaning)
        _log.update { (listOf(line) + it).take(400) }
    }

    fun clearLog() = _log.update { emptyList() }

    // ---------------------------------------------------------------- scan & connect

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            if (!name.contains("LOOI", ignoreCase = true)) return
            if (_state.value != ConnState.SCANNING) return
            stopScan()
            note("··", "pronađen $name ${result.device.address} (${result.rssi} dBm)")
            connect(result.device, name)
        }

        override fun onScanFailed(errorCode: Int) {
            note("!!", "skeniranje nije uspjelo (kod $errorCode)")
            _state.value = ConnState.IDLE
        }
    }

    fun scanAndConnect() {
        if (adapter == null || !adapter.isEnabled) {
            note("!!", "Bluetooth je isključen")
            return
        }
        disconnect()
        _state.value = ConnState.SCANNING
        note("··", "tražim LOOI… (zatvori službenu LOOI aplikaciju)")
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        adapter.bluetoothLeScanner?.startScan(null, settings, scanCallback)
        scanTimeout = scope.launch {
            delay(15_000)
            if (_state.value == ConnState.SCANNING) {
                stopScan()
                note("!!", "LOOI nije pronađen. Je li upaljen i odspojen od službene aplikacije?")
                _state.value = ConnState.IDLE
            }
        }
    }

    private fun stopScan() {
        scanTimeout?.cancel()
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private fun connect(device: BluetoothDevice, name: String) {
        deviceName = name
        _state.value = ConnState.CONNECTING
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        stopScan()
        keepAlive?.cancel()
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        pending?.complete(null)
        if (_state.value != ConnState.IDLE) _state.value = ConnState.DISCONNECTED
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                note("··", "spojen, tražim servise…")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                note("!!", "odspojen (status $status)")
                keepAlive?.cancel()
                pending?.complete(null)
                g.close()
                if (gatt == g) gatt = null
                _state.value = ConnState.DISCONNECTED
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g.getService(LooiProtocol.SERVICE) == null) {
                note("!!", "servis 00FF nije pronađen; je li ovo stvarno LOOI?")
                return
            }
            scope.launch { handshake() }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) ByteArray(0) else null)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) ByteArray(0) else null)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }

        @Deprecated("Android 12 and older")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) c.value else null)
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onNotify(c.uuid, value)
        }

        @Deprecated("Android 12 and older")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                onNotify(c.uuid, c.value ?: ByteArray(0))
            }
        }
    }

    private fun onNotify(uuid: UUID, value: ByteArray) {
        if (uuid == LooiProtocol.EVENTS) {
            val meaning = LooiProtocol.describeEvent(value)
            val key = value.firstOrNull()?.let { "%02x".format(it) } ?: "?"
            _sensors.update { it + (key to "${value.hex()}  →  $meaning") }
            if (value.firstOrNull()?.toInt() == 0x01) floor = value.drop(1).map { it.toInt() == 1 }
            _events.tryEmit(value)
            note("◀", nameOf(uuid), value, meaning)
        } else if (uuid == LooiProtocol.CLOCK) {
            note("◀", nameOf(uuid), value, "\"" + value.toString(Charsets.US_ASCII) + "\"")
        } else {
            note("◀", nameOf(uuid), value)
        }
    }

    // ---------------------------------------------------------------- operations

    private fun ch(uuid: UUID): BluetoothGattCharacteristic? =
        gatt?.getService(LooiProtocol.SERVICE)?.getCharacteristic(uuid)

    private suspend fun op(start: (BluetoothGatt) -> Boolean): ByteArray? = opLock.withLock {
        val g = gatt ?: return null
        val d = CompletableDeferred<ByteArray?>()
        pending = d
        if (!start(g)) {
            pending = null
            return null
        }
        val result = withTimeoutOrNull(3_000) { d.await() }
        pending = null
        result
    }

    suspend fun write(uuid: UUID, value: ByteArray, withResponse: Boolean = true, quiet: Boolean = false): Boolean {
        val c = ch(uuid) ?: run { note("!!", "nema karakteristike ${nameOf(uuid)}"); return false }
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val ok = op { g ->
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, value, type) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { c.writeType = type; c.value = value; g.writeCharacteristic(c) }
            }
        } != null
        if (!quiet || !ok) note(if (ok) "▶" else "!!", nameOf(uuid), value, if (ok) "" else "upis nije uspio")
        return ok
    }

    suspend fun read(uuid: UUID): ByteArray? {
        val c = ch(uuid) ?: return null
        val v = op { g -> g.readCharacteristic(c) }
        note("◀", "${nameOf(uuid)} (čitanje)", v ?: ByteArray(0), if (v == null) "nije uspjelo" else "")
        return v
    }

    private suspend fun enableNotify(uuid: UUID): Boolean {
        val c = ch(uuid) ?: return false
        val g = gatt ?: return false
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(LooiProtocol.CCCD) ?: return false
        val on = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return op { gg ->
            if (Build.VERSION.SDK_INT >= 33) {
                gg.writeDescriptor(d, on) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { d.value = on; gg.writeDescriptor(d) }
            }
        } != null
    }

    /** The same start-up the official app does. */
    private suspend fun handshake() {
        note("··", "pozdravljam bazu…")
        enableNotify(LooiProtocol.EVENTS)
        enableNotify(LooiProtocol.CLOCK)
        write(LooiProtocol.CONTROL, LooiProtocol.HELLO)
        write(LooiProtocol.CLOCK, LooiProtocol.clockCommand())
        write(LooiProtocol.CONTROL, LooiProtocol.START)
        _state.value = ConnState.READY
        note("··", "spreman ✓")
        playScript(LooiProtocol.headTo(LooiProtocol.HEAD_MIDDLE, 12), "glava u sredinu", gesture = false)
        onReady?.invoke(this)
        keepAlive = scope.launch {
            while (isActive) {
                delay(30_000)
                if (_state.value == ConnState.READY) read(LooiProtocol.STATUS)
            }
        }
    }

    // ---------------------------------------------------------------- robot actions

    private var scriptId = 0x40

    /** The lamp on the base. */
    @Volatile var lightOn = false
        private set
    suspend fun setLight(on: Boolean): Boolean {
        val ok = write(LooiProtocol.LIGHT, byteArrayOf(if (on) 1 else 0))
        if (ok) lightOn = on
        return ok
    }

    /** Latest floor sensors (true = table under it); empty until the base reports. */
    @Volatile var floor: List<Boolean> = emptyList()
        private set
    /** Standing, but part of him over the edge. */
    val atEdge: Boolean get() = floor.isNotEmpty() && floor.any { !it } && floor.any { it }

    /** Runs once the handshake is done (e.g. settings that live on the base). */
    var onReady: (suspend LooiBle.() -> Unit)? = null

    /** [rotate]: + = left; [tilt]: + = head down. */
    suspend fun move(rotate: Int, tilt: Int) {
        if (_state.value != ConnState.READY) return
        write(LooiProtocol.ROTATE, LooiProtocol.driveValue(rotate), withResponse = false, quiet = true)
        write(LooiProtocol.TILT, LooiProtocol.driveValue(tilt), withResponse = false, quiet = true)
    }

    suspend fun stopScript() = write(LooiProtocol.SCRIPT, LooiProtocol.STOP_SCRIPT)

    /** When the last gesture (not face tracking) started. */
    @Volatile var lastGestureAt = 0L
    /** Until when the last gesture runs: tracking and idle behaviours wait for it. */
    @Volatile var busyUntil = 0L
    /** Where the head is (0 up … 100 down), as far as our own scripts moved it. */
    @Volatile var headPos = LooiProtocol.HEAD_MIDDLE
    /** Driving (script channel 1): until when, and which way (+1 forward, -1 back). */
    @Volatile var driveUntil = 0L
    @Volatile var lastDriveDir = 0

    suspend fun playScript(keyframes: ByteArray, label: String, gesture: Boolean = true) {
        if (_state.value != ConnState.READY) return
        val now = System.currentTimeMillis()
        // What this script does: how long it runs, where the head ends, whether it drives.
        var maxTick = 0; var headTick = -1; var dir = 0
        var i = 0
        while (i + 2 < keyframes.size) {
            val t = keyframes[i].toInt() and 0xFF; val v = keyframes[i + 1].toInt(); val c = keyframes[i + 2].toInt()
            if (t != 0xFF) {
                if (t > maxTick) maxTick = t
                if (c == 3 && t >= headTick) { headTick = t; headPos = v.coerceIn(0, 100) }
                if (c == 1 && v != 0 && dir == 0) dir = if (v > 0) 1 else -1
            }
            i += 3
        }
        if (gesture) { lastGestureAt = now; busyUntil = now + maxTick * 40L }
        if (dir != 0) { lastDriveDir = dir; driveUntil = now + maxTick * 40L }
        scriptId = if (scriptId >= 0xF0) 0x40 else scriptId + 1
        note("··", "$label → skripta #${"%02x".format(scriptId)}")
        stopScript()
        for (frag in LooiProtocol.scriptFragments(scriptId, keyframes)) {
            if (!write(LooiProtocol.SCRIPT, frag)) break
        }
    }

    fun launch(block: suspend LooiBle.() -> Unit) = scope.launch { block() }
}
