package io.github.deganegane.looilab

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Amber = Color(0xFFFF8A2A)
private val Ink = Color(0xFF140A06)
private val Card = Color(0xFF22160F)
private val Muted = Color(0xFFB59A86)

@Composable
fun StevoTheme(content: @Composable () -> Unit) =
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Amber, onPrimary = Ink, background = Ink, surface = Card, secondaryContainer = Color(0xFF4A2A14)),
        content = content,
    )

// ===================================================================== home: the face

@Composable
fun HomeFace(
    face: FaceState,
    ble: LooiBle,
    brain: StevoBrain,
    life: Life,
    onTalk: () -> Unit,
    onPhoto: () -> Unit,
    onPhotos: () -> Unit,
    onSettings: () -> Unit,
    onConnect: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val talk by brain.state.collectAsState()
    val robot by ble.state.collectAsState()
    Box(Modifier.fillMaxSize()) {
        NixieFace(
            face,
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { if (menu) menu = false else onTalk() },
                        onLongPress = { life.touched(); face.flash(Expr.LOVE) },
                    )
                }
                .pointerInput(Unit) {
                    var dy = 0f
                    detectVerticalDragGestures(
                        onDragStart = { dy = 0f },
                        onVerticalDrag = { _, d -> dy += d },
                        onDragEnd = { if (dy < -60) menu = true else if (dy > 60) menu = false },
                    )
                },
        )
        // A small glowing dot while he listens or speaks.
        if (talk != TalkState.OFF) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(18.dp).size(10.dp)
                    .background(if (talk == TalkState.SPEAKING) Amber else Amber.copy(alpha = 0.45f), CircleShape)
            )
        }
        AnimatedVisibility(
            visible = menu, modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
        ) {
            Column(
                Modifier.padding(16.dp).widthIn(max = 720.dp).fillMaxWidth()
                    .background(Color(0xEE1A100A), RoundedCornerShape(24.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (label, color) = when (robot) {
                        ConnState.READY -> "Robot spojen" to Color(0xFF7BE07B)
                        ConnState.SCANNING, ConnState.CONNECTING -> "Tražim robota…" to Color(0xFFFFC857)
                        else -> "Robot nije spojen" to Color(0xFFFF8A80)
                    }
                    Box(Modifier.size(9.dp).background(color, CircleShape))
                    Text("  $label", color = color, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    if (robot != ConnState.READY && robot != ConnState.SCANNING && robot != ConnState.CONNECTING) {
                        TextButton(onClick = onConnect) { Text("Spoji") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    MenuButton(if (talk == TalkState.OFF) "Razgovor" else "Kraj razgovora", Modifier.weight(1f)) { menu = false; onTalk() }
                    MenuButton("Slikaj", Modifier.weight(1f)) { menu = false; onPhoto() }
                    MenuButton("Fotografije", Modifier.weight(1f)) { menu = false; onPhotos() }
                    MenuButton(if (life.sleeping) "Probudi" else "Spavaj", Modifier.weight(1f)) {
                        menu = false; if (life.sleeping) life.wake() else life.sleep()
                    }
                    MenuButton("Postavke", Modifier.weight(1f)) { menu = false; onSettings() }
                }
            }
        }
    }
}

@Composable
private fun MenuButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) {
        Text(label, fontSize = 14.sp, maxLines = 1)
    }
}

// ===================================================================== settings

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).background(Card, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = Amber, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        content()
    }
}

@Composable
private fun Toggle(title: String, hint: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            if (hint.isNotEmpty()) Text(hint, fontSize = 12.sp, color = Muted)
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun Hint(text: String) = Text(text, fontSize = 12.sp, color = Muted)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    ble: LooiBle,
    brain: StevoBrain,
    memory: Memory,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onApply: () -> Unit,
    onPhoto: () -> Unit,
    onMemory: () -> Unit,
    onDiagnostics: () -> Unit,
    onCharging: (Boolean) -> Unit,
) {
    val robot by ble.state.collectAsState()
    var name by remember { mutableStateOf(brain.name) }
    var personality by remember { mutableStateOf(brain.personality) }
    var lang by remember { mutableStateOf(brain.language) }
    var voice by remember { mutableStateOf(brain.voice) }
    var speed by remember { mutableFloatStateOf(brain.speed) }
    var pitch by remember { mutableFloatStateOf(brain.pitch) }
    var interrupt by remember { mutableStateOf(brain.allowInterrupt) }
    var level by remember { mutableIntStateOf(memory.level) }
    var keep by remember { mutableIntStateOf(memory.keepDays) }
    var tracking by remember { mutableStateOf(brain.tracking) }
    var mirror by remember { mutableStateOf(brain.mirror) }
    var vision by remember { mutableStateOf(brain.vision) }
    var screen by remember { mutableStateOf(brain.keepScreenOn) }
    var charging by remember { mutableStateOf(brain.phoneCharging) }
    var key by remember { mutableStateOf(brain.apiKey) }
    var keySaved by remember { mutableStateOf(brain.apiKey.isNotBlank()) }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Natrag", fontSize = 16.sp) }
            Text("Postavke", fontWeight = FontWeight.Bold, fontSize = 22.sp)
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
            item {
                Section("Robot") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when (robot) {
                                ConnState.READY -> "Spojen: ${ble.deviceName ?: "LOOI"}"
                                ConnState.SCANNING -> "Tražim robota…"
                                ConnState.CONNECTING -> "Spajam…"
                                else -> "Nije spojen"
                            },
                            modifier = Modifier.weight(1f), fontSize = 15.sp,
                        )
                        if (robot == ConnState.READY) OutlinedButton(onClick = { ble.disconnect() }) { Text("Odspoji") }
                        else Button(onClick = onConnect) { Text("Spoji") }
                    }
                    Toggle("Punjenje mobitela s baze", "Eksperimentalno: isključi da baza ne troši bateriju na mobitel.", charging) {
                        charging = it; brain.phoneCharging = it; onCharging(it)
                    }
                }
            }
            item {
                Section("Stevo") {
                    OutlinedTextField(name, { name = it; brain.name = it }, label = { Text("Ime") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        personality, { personality = it; brain.personality = it },
                        label = { Text("Osobnost (kako se ponaša i priča)") }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                    )
                    Hint("Npr. \"Govori kao stari radijski spiker\" ili \"Šapuće i sve mu je tajna\". Vrijedi od idućeg razgovora.")
                    TextButton(onClick = { personality = StevoBrain.DEFAULT_PERSONALITY; brain.personality = personality }) { Text("Vrati zadanu osobnost") }
                    Text("Jezik", fontSize = 13.sp, color = Muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StevoBrain.LANGUAGES.forEach { (code, label) ->
                            FilterChip(lang == code, { lang = code; brain.language = code }, { Text(label) })
                        }
                    }
                    Text("Glas", fontSize = 13.sp, color = Muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StevoBrain.VOICES.forEach { v -> FilterChip(voice == v, { voice = v; brain.voice = v }, { Text(v) }) }
                    }
                    Text("Brzina govora ${"%.2f".format(speed)}×", fontSize = 14.sp)
                    Slider(speed, { speed = it }, valueRange = 0.7f..1.6f, onValueChangeFinished = { brain.speed = speed })
                    Text("Visina glasa ${"%.2f".format(pitch)}×", fontSize = 14.sp)
                    Slider(pitch, { pitch = it }, valueRange = 0.6f..1.8f, onValueChangeFinished = { brain.pitch = pitch })
                    TextButton(onClick = { speed = 1f; pitch = 1f; brain.speed = 1f; brain.pitch = 1f }) { Text("Brzina i visina na 1×") }
                    Toggle("Mogu ga prekinuti dok govori", "Isključeno: mikrofon se gasi dok on priča (nema jeke).", interrupt) {
                        interrupt = it; brain.allowInterrupt = it
                    }
                }
            }
            item {
                Section("Pamćenje") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Memory.LEVELS.forEachIndexed { i, l -> FilterChip(level == i, { level = i; memory.level = i }, { Text(l) }) }
                    }
                    Hint(
                        when (level) {
                            0 -> "Svaki razgovor kreće od nule."
                            1 -> "Sam sprema važne činjenice i zna ih u svakom razgovoru."
                            2 -> "Činjenice + kratki sažetak svakog razgovora (zna o čemu ste pričali)."
                            else -> "Činjenice, sažeci i svi razgovori u arhivi, koju pretražuje kad spomeneš nešto od prije."
                        }
                    )
                    Text("Čuvaj razgovore i sažetke", fontSize = 13.sp, color = Muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Memory.KEEP.forEach { (d, l) -> FilterChip(keep == d, { keep = d; memory.keepDays = d }, { Text(l) }) }
                    }
                    OutlinedButton(onClick = onMemory) { Text("Što Stevo pamti →") }
                    Hint("Sve je spremljeno samo na ovom mobitelu.")
                }
            }
            item {
                Section("Kamera i ekran") {
                    Toggle("Praćenje lica", "Okreće se i gleda prema tebi (na mobitelu, bez interneta).", tracking) {
                        tracking = it; brain.tracking = it; onApply()
                    }
                    Toggle("Zrcali smjer", "Uključi ako se okreće od tebe umjesto prema tebi.", mirror) { mirror = it; brain.mirror = it }
                    Toggle("Stevo vidi", "U razgovoru Gemini dobiva sličicu s kamere svakih 1,5 s; tada sam slika lijepe stvari.", vision) {
                        vision = it; brain.vision = it; onApply()
                    }
                    Toggle("Ekran uvijek upaljen", "", screen) { screen = it; brain.keepScreenOn = it; onApply() }
                    OutlinedButton(onClick = onPhoto) { Text("Slikaj sad") }
                }
            }
            item {
                Section("Gemini") {
                    OutlinedTextField(
                        key, { key = it; keySaved = false }, label = { Text("API ključ (aistudio.google.com)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { brain.apiKey = key; keySaved = key.isNotBlank() }) { Text("Spremi") }
                        if (keySaved) Text("   ✓ spremljen", color = Color(0xFF7BE07B))
                    }
                    Hint("Model: ${StevoBrain.MODEL.removePrefix("models/")}, sažeci: ${Memory.SUMMARY_MODEL}")
                }
            }
            item {
                Section("Napredno") {
                    OutlinedButton(onClick = onDiagnostics) { Text("Dijagnostika →") }
                    Hint("Ručna vožnja, animacije, kanali, sirove komande i dnevnik Bluetootha.")
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ===================================================================== memory

private val dateFmt = SimpleDateFormat("d.M.yyyy. HH:mm", Locale.ROOT)

@Composable
fun MemoryScreen(memory: Memory, onBack: () -> Unit, onExport: () -> Unit, onImport: () -> Unit) {
    val notes by memory.notes.collectAsState()
    val summaries by memory.summaries.collectAsState()
    val conversations by memory.conversations.collectAsState()
    var confirm by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Natrag", fontSize = 16.sp) }
            Text("Što Stevo pamti", fontWeight = FontWeight.Bold, fontSize = 22.sp)
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
            item {
                Section("Bilješke (${notes.size})") {
                    if (notes.isEmpty()) Hint("Još ništa. Reci mu nešto o sebi, sam će zapamtiti.")
                }
            }
            items(notes.reversed()) { n -> Entry(n.text, dateFmt.format(Date(n.at))) { memory.deleteNote(n) } }
            item { Section("Sažeci razgovora (${summaries.size})") { if (summaries.isEmpty()) Hint("Nastaju nakon svakog razgovora (razina 2 i više).") } }
            items(summaries.reversed()) { s -> Entry(s.text, dateFmt.format(Date(s.at))) { memory.deleteSummary(s) } }
            item {
                Section("Arhiva") {
                    Hint("$conversations spremljenih razgovora (razina 3), pretražuje ih kad spomeneš nešto od prije.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onExport) { Text("Izvoz") }
                        OutlinedButton(onClick = onImport) { Text("Uvoz") }
                    }
                    Button(onClick = { confirm = true }) { Text("Zaboravi sve") }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Zaboraviti sve?") },
        text = { Text("Brišu se sve bilješke, sažeci i razgovori. Ovo se ne može vratiti (osim iz izvoza).") },
        confirmButton = { TextButton(onClick = { memory.forgetEverything(); confirm = false }) { Text("Zaboravi") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Odustani") } },
    )
}

@Composable
private fun Entry(text: String, date: String, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).background(Color(0xFF1C120C), RoundedCornerShape(12.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text, fontSize = 14.sp)
            Text(date, fontSize = 11.sp, color = Muted)
        }
        Text("✕", color = Muted, fontSize = 18.sp, modifier = Modifier.clickable(onClick = onDelete).padding(8.dp))
    }
}
