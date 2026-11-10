package io.github.deganegane.looilab

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val Accent = Color(0xFF5CE1E6)
private val Panel = Color(0xFF1B2230)

@Composable
fun LabScreen(ble: LooiBle, face: FaceState, brain: StevoBrain, onTalk: () -> Unit, onConnect: () -> Unit, onFullscreen: () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Color(0xFF0E1116), surface = Panel)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().padding(12.dp)) {
                Header(ble, onConnect)
                Spacer(Modifier.height(8.dp))
                var tab by remember { mutableIntStateOf(0) }
                ScrollableTabRow(selectedTabIndex = tab, containerColor = Color.Transparent, edgePadding = 0.dp) {
                    listOf("Stevo", "Lice", "Pokret", "Animacije", "Kanali", "Sirovo").forEachIndexed { i, t ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontSize = 13.sp, maxLines = 1) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().weight(0.55f)) {
                    when (tab) {
                        0 -> StevoTab(brain, onTalk, onFullscreen)
                        1 -> FaceTab(face, onFullscreen)
                        2 -> DriveTab(ble, face)
                        3 -> AnimTab(ble)
                        4 -> ChannelTab(ble)
                        else -> RawTab(ble)
                    }
                }
                SensorPanel(ble)
                LogPanel(ble, Modifier.weight(0.45f))
            }
        }
    }
}

@Composable
private fun Header(ble: LooiBle, onConnect: () -> Unit) {
    val state by ble.state.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        val (label, color) = when (state) {
            ConnState.IDLE -> "nije spojen" to Color.Gray
            ConnState.SCANNING -> "tražim…" to Color(0xFFFFC857)
            ConnState.CONNECTING -> "spajam…" to Color(0xFFFFC857)
            ConnState.READY -> (ble.deviceName ?: "LOOI") + " ✓" to Color(0xFF7BE07B)
            ConnState.DISCONNECTED -> "odspojen" to Color(0xFFFF6B6B)
        }
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("LOOI Lab", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(label, color = color, fontSize = 13.sp)
        }
        if (state == ConnState.READY) {
            OutlinedButton(onClick = { ble.disconnect() }) { Text("Odspoji") }
        } else {
            Button(onClick = onConnect, enabled = state != ConnState.SCANNING && state != ConnState.CONNECTING) {
                Text("Spoji")
            }
        }
    }
}

// ------------------------------------------------------------------ Stevo (Gemini Live)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StevoTab(brain: StevoBrain, onTalk: () -> Unit, onFullscreen: () -> Unit) {
    val state by brain.state.collectAsState()
    val lines by brain.transcript.collectAsState()
    var key by remember { mutableStateOf(brain.apiKey) }
    var name by remember { mutableStateOf(brain.name) }
    var voice by remember { mutableStateOf(brain.voice) }
    var interrupt by remember { mutableStateOf(brain.allowInterrupt) }
    LazyColumn {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onTalk, enabled = state != TalkState.CONNECTING) {
                    Text(if (state == TalkState.OFF) "🎙 Razgovaraj" else "■ Kraj")
                }
                Text(
                    when (state) {
                        TalkState.OFF -> "ne sluša"
                        TalkState.CONNECTING -> "spajam se na Gemini…"
                        TalkState.LISTENING -> "sluša te"
                        TalkState.SPEAKING -> "govori"
                    },
                    color = if (state == TalkState.OFF) Color.Gray else Accent, fontSize = 13.sp,
                )
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onFullscreen) { Text("Lice") }
            }
            Spacer(Modifier.height(6.dp))
            lines.takeLast(8).forEach { l ->
                Text(l, fontSize = 13.sp, color = if (l.startsWith("ti:")) Color.White else if (l.startsWith("·") || l.startsWith("⚠")) Color.Gray else Accent)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = key, onValueChange = { key = it }, singleLine = true,
                label = { Text("Gemini API ključ (aistudio.google.com)") },
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { brain.apiKey = key }) { Text("Spremi ključ") }
                if (brain.apiKey.isNotBlank()) Text("✓ spremljen", color = Color(0xFF7BE07B), fontSize = 12.sp)
            }
            OutlinedTextField(
                value = name, onValueChange = { name = it; brain.name = it }, singleLine = true,
                label = { Text("Ime robota") }, modifier = Modifier.fillMaxWidth(),
            )
            Text("Glas", fontSize = 12.sp, color = Color.Gray)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StevoBrain.VOICES.forEach { v ->
                    FilterChip(selected = voice == v, onClick = { voice = v; brain.voice = v }, label = { Text(v, fontSize = 12.sp) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mogu ga prekinuti dok govori", fontSize = 13.sp)
                    Text("Isključeno: mikrofon se gasi dok Stevo priča (nema jeke).", fontSize = 11.sp, color = Color.Gray)
                }
                Switch(checked = interrupt, onCheckedChange = { interrupt = it; brain.allowInterrupt = it })
            }
            Text("Glas i postavke vrijede od sljedećeg razgovora. Na licu: dodir = razgovor.", fontSize = 11.sp, color = Color.Gray)
        }
    }
}

// ------------------------------------------------------------------ face

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FaceTab(face: FaceState, onFullscreen: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f).clip(RoundedCornerShape(16.dp))
                .border(6.dp, Color(0xFF2B1D14), RoundedCornerShape(16.dp))
        ) { NixieFace(face, Modifier.fillMaxSize()) }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Expr.entries.forEach { e ->
                FilterChip(selected = face.base == e, onClick = { face.set(e) }, label = { Text(e.label, fontSize = 12.sp) })
            }
        }
        Spacer(Modifier.height(6.dp))
        Button(onClick = onFullscreen) { Text("Cijeli zaslon (stavi mobitel na robota)") }
        Text(
            "Na cijelom zaslonu: dodir = razgovor, dugi dodir = zaljubljeno, natrag = izlaz.",
            fontSize = 12.sp, color = Color.Gray,
        )
    }
}

// ------------------------------------------------------------------ drive

@Composable
private fun DriveTab(ble: LooiBle, face: FaceState) {
    var stick by remember { mutableStateOf(Offset.Zero) } // -1..1 each axis
    var active by remember { mutableStateOf(false) }
    var maxSpeed by remember { mutableFloatStateOf(60f) }

    // While the stick is held: speed + turn every 60 ms (the official app does ~50 ms).
    LaunchedEffect(active) {
        if (active) {
            while (true) {
                // The eyes look where the robot is going.
                face.lookX = stick.x; face.lookY = stick.y
                // Stick right = rotate right, stick up = head up.
                val rotate = (-stick.x * maxSpeed).roundToInt()
                val tilt = (stick.y * maxSpeed).roundToInt()
                ble.launch { move(rotate, tilt) }
                delay(60)
            }
        } else {
            face.lookX = 0f; face.lookY = 0f
            repeat(3) { ble.launch { move(0, 0) }; delay(60) }
        }
    }

    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        val radius = 75.dp
        Box(
            Modifier.size(radius * 2).background(Panel, CircleShape).border(1.dp, Accent.copy(alpha = 0.3f), CircleShape)
                .pointerInput(Unit) {
                    val r = size.width / 2f
                    fun set(p: Offset) {
                        var v = Offset((p.x - r) / r, (p.y - r) / r)
                        val len = sqrt(v.x * v.x + v.y * v.y)
                        if (len > 1f) v = v / len
                        stick = v
                    }
                    detectDragGestures(
                        onDragStart = { set(it); active = true },
                        onDragEnd = { stick = Offset.Zero; active = false },
                        onDragCancel = { stick = Offset.Zero; active = false },
                        onDrag = { change, _ -> set(change.position) },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            val knob = 44.dp
            Box(
                Modifier.offset { IntOffset((stick.x * (radius - knob / 2).toPx()).roundToInt(), (stick.y * (radius - knob / 2).toPx()).roundToInt()) }
                    .size(knob).background(Accent, CircleShape)
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text("Rotacija: ${(-stick.x * maxSpeed).roundToInt()}", fontFamily = FontFamily.Monospace)
            Text("Glava: ${(stick.y * maxSpeed).roundToInt()}", fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(12.dp))
            Text("Najveća snaga: ${maxSpeed.roundToInt()} / 127", fontSize = 13.sp)
            Slider(value = maxSpeed, onValueChange = { maxSpeed = it }, valueRange = 10f..127f)
            Text(
                "← → okretanje u mjestu, ↑ ↓ glava gore/dolje.",
                fontSize = 12.sp, color = Color.Gray,
            )
            Spacer(Modifier.height(8.dp))
            // Driving goes through a short script on channel 1 (the joystick characteristics
            // only rotate and tilt).
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = {
                    val v = (maxSpeed * 0.6f).roundToInt()
                    ble.launch { playScript(LooiProtocol.holdScript(1, v, 15), "Naprijed $v") }
                }) { Text("▲ Naprijed") }
                Button(onClick = {
                    val v = -(maxSpeed * 0.6f).roundToInt()
                    ble.launch { playScript(LooiProtocol.holdScript(1, v, 15), "Natrag $v") }
                }) { Text("▼ Natrag") }
            }
            var head by remember { mutableFloatStateOf(50f) }
            Text("Glava (položaj): ${head.roundToInt()}", fontSize = 13.sp)
            Slider(
                value = head, onValueChange = { head = it }, valueRange = 0f..100f,
                onValueChangeFinished = {
                    val h = head.roundToInt()
                    ble.launch { playScript(LooiProtocol.holdScript(3, h, 10), "Glava na $h") }
                },
            )
        }
    }
}

// ------------------------------------------------------------------ captured animations

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnimTab(ble: LooiBle) {
    LazyColumn {
        item {
            Text(
                "Animacije koje je službena aplikacija slala, ponovljene bajt po bajt. " +
                    "Zapiši što koja radi!",
                fontSize = 12.sp, color = Color.Gray,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { ble.launch { stopScript() } }) { Text("■ Stop") }
            Spacer(Modifier.height(6.dp))
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CapturedAnims.all.forEach { a ->
                    Button(
                        onClick = { ble.launch { playScript(a.keyframes, "Animacija ${a.number} (${a.name})") } },
                        colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = Color.White),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("#${a.number} ${a.name}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("k${a.channels} · ${"%.1f".format(a.seconds)}s", fontSize = 11.sp, color = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ channel tester

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChannelTab(ble: LooiBle) {
    var channel by remember { mutableIntStateOf(1) }
    var value by remember { mutableFloatStateOf(30f) }
    var ticks by remember { mutableFloatStateOf(25f) }
    Column {
        Text(
            "Drži jedan kanal na vrijednosti neko vrijeme. Tako otkrivamo što je koji kanal " +
                "(1 = vožnja, 2 = okret, 3 = položaj glave 0–100).",
            fontSize = 12.sp, color = Color.Gray,
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..6).forEach { c ->
                FilterChip(selected = channel == c, onClick = { channel = c }, label = { Text("Kanal $c") })
            }
        }
        Text("Vrijednost: ${value.roundToInt()}", fontFamily = FontFamily.Monospace)
        Slider(value = value, onValueChange = { value = it }, valueRange = -100f..100f)
        Text("Trajanje: ${ticks.roundToInt()} tikova ≈ ${"%.1f".format(ticks * 0.04)} s", fontFamily = FontFamily.Monospace)
        Slider(value = ticks, onValueChange = { ticks = it }, valueRange = 2f..150f)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val v = value.roundToInt(); val t = ticks.roundToInt(); val c = channel
                ble.launch { playScript(LooiProtocol.holdScript(c, v, t), "Kanal $c = $v, $t tikova") }
            }) { Text("▶ Pošalji") }
            OutlinedButton(onClick = { ble.launch { stopScript() } }) { Text("■ Stop") }
        }
    }
}

// ------------------------------------------------------------------ raw writes

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RawTab(ble: LooiBle) {
    var target by remember { mutableStateOf(LooiProtocol.ALL_KNOWN.first()) }
    var text by remember { mutableStateOf("00 00") }
    var withResponse by remember { mutableStateOf(true) }
    LazyColumn {
        item {
            Text("Upiši bilo koje bajtove u bilo koju karakteristiku (hex).", fontSize = 12.sp, color = Color.Gray)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LooiProtocol.ALL_KNOWN.forEach { k ->
                    FilterChip(selected = target == k, onClick = { target = k }, label = { Text(k.first, fontSize = 12.sp) })
                }
            }
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                label = { Text("hex, npr. 00 1e") }, modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = withResponse, onClick = { withResponse = !withResponse }, label = { Text("s odgovorom") })
                Button(onClick = {
                    val bytes = text.parseHex()
                    if (bytes == null) ble.note("!!", "neispravan hex: $text")
                    else ble.launch { write(target.second, bytes, withResponse) }
                }) { Text("Upiši") }
                OutlinedButton(onClick = { ble.launch { read(target.second) } }) { Text("Pročitaj") }
            }
        }
    }
}

// ------------------------------------------------------------------ sensors & log

@Composable
private fun SensorPanel(ble: LooiBle) {
    val sensors by ble.sensors.collectAsState()
    if (sensors.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).background(Panel, RoundedCornerShape(10.dp)).padding(8.dp)
    ) {
        Text("Zadnje od robota", fontSize = 12.sp, color = Accent)
        sensors.toSortedMap().forEach { (_, v) ->
            Text(v, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun LogPanel(ble: LooiBle, modifier: Modifier) {
    val log by ble.log.collectAsState()
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Dnevnik", fontSize = 12.sp, color = Accent, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { ble.clearLog() }, contentPadding = ButtonDefaults.TextButtonContentPadding) {
                Text("Očisti", fontSize = 12.sp)
            }
        }
        LazyColumn(Modifier.fillMaxSize().background(Color(0xFF080A0E), RoundedCornerShape(8.dp)).padding(6.dp)) {
            items(log) { l ->
                val color = when (l.dir) {
                    "◀" -> Color(0xFF7BE07B)
                    "▶" -> Accent
                    "!!" -> Color(0xFFFF6B6B)
                    else -> Color.Gray
                }
                Text(
                    buildString {
                        append(l.time.substring(3)); append(' '); append(l.dir); append(' '); append(l.what)
                        if (l.hex.isNotEmpty()) { append("  "); append(l.hex) }
                        if (l.meaning.isNotEmpty()) { append("  → "); append(l.meaning) }
                    },
                    color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 14.sp,
                )
            }
        }
    }
}
