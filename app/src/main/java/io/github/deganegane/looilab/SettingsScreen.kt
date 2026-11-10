package io.github.deganegane.looilab

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings, the way Ergbeat does them: a short overview whose rows say what is set now, and one
 * page per group behind each row. Back on a page returns to the overview.
 */
private enum class Page(val title: String) {
    CHARACTER("Osobnost"), VOICE("Glas i jezik"), MEMORY("Pamćenje"),
    ROBOT("Robot"), CAMERA("Kamera i zaslon"), GEMINI("Gemini"),
}

class SettingsActions(
    val onBack: () -> Unit,
    val onConnect: () -> Unit,
    val onApply: () -> Unit,
    val onPhoto: () -> Unit,
    val onDiagnostics: () -> Unit,
    val onCharging: (Boolean) -> Unit,
    val onExport: () -> Unit,
    val onImport: () -> Unit,
)

@Composable
fun SettingsScreen(ble: LooiBle, brain: StevoBrain, memory: Memory, a: SettingsActions) {
    var page by rememberSaveable { mutableStateOf<Page?>(null) }
    BackHandler { if (page != null) page = null else a.onBack() }
    val hubScroll = rememberScrollState()
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val forward = targetState != null
            (slideInHorizontally(tween(260)) { w -> if (forward) w / 5 else -w / 5 } + fadeIn(tween(260))) togetherWith
                (slideOutHorizontally(tween(260)) { w -> if (forward) -w / 5 else w / 5 } + fadeOut(tween(180)))
        },
        label = "settingsPage",
    ) { p ->
        if (p == null) Hub(ble, brain, memory, hubScroll, a, onOpen = { page = it })
        else SettingsPage(p.title, onBack = { page = null }) {
            when (p) {
                Page.CHARACTER -> CharacterPage(brain)
                Page.VOICE -> VoicePage(brain)
                Page.MEMORY -> MemoryPage(memory, a)
                Page.ROBOT -> RobotPage(ble, brain, a)
                Page.CAMERA -> CameraPage(brain, a)
                Page.GEMINI -> GeminiPage(brain)
            }
        }
    }
}

// ---- overview ----------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Natrag") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
private fun Hub(ble: LooiBle, brain: StevoBrain, memory: Memory, scroll: ScrollState, a: SettingsActions, onOpen: (Page) -> Unit) {
    val robot by ble.state.collectAsState()
    val notes by memory.notes.collectAsState()
    Scaffold(topBar = { TopBar("Postavke", a.onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)
                .verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            HubGroup(brain.name) {
                HubRow(Icons.Rounded.Person, "Osobnost", brain.name + " · " + brain.personality.take(60)) { onOpen(Page.CHARACTER) }
                HubRow(Icons.Rounded.Call, "Glas i jezik",
                    listOf(StevoBrain.LANGUAGES[brain.language] ?: "", brain.voice,
                        "%.2f×".format(brain.speed), "visina %.2f×".format(brain.pitch)).joinToString(" · ")) { onOpen(Page.VOICE) }
                HubRow(Icons.AutoMirrored.Rounded.List, "Pamćenje",
                    Memory.LEVELS[memory.level] + " · " + notes.size + " bilješki · " + (Memory.KEEP[memory.keepDays] ?: "")) { onOpen(Page.MEMORY) }
            }
            HubGroup("Robot") {
                HubRow(Icons.Rounded.Build, "Robot",
                    (if (robot == ConnState.READY) "Spojen" else if (robot == ConnState.SCANNING || robot == ConnState.CONNECTING) "Tražim…" else "Nije spojen") +
                        if (brain.phoneCharging) " · puni mobitel" else " · ne puni mobitel") { onOpen(Page.ROBOT) }
                HubRow(Icons.Rounded.Face, "Kamera i zaslon",
                    listOf(if (brain.tracking) "Praćenje lica" else "Bez praćenja", if (brain.vision) "vidi" else "ne vidi",
                        if (brain.keepScreenOn) "ekran upaljen" else "ekran se gasi").joinToString(" · ")) { onOpen(Page.CAMERA) }
            }
            HubGroup("Aplikacija") {
                HubRow(Icons.Rounded.Lock, "Gemini", if (brain.apiKey.isBlank()) "Upiši API ključ" else "Ključ spremljen · ${StevoBrain.MODEL.removePrefix("models/")}") { onOpen(Page.GEMINI) }
                HubRow(Icons.Rounded.Info, "Dijagnostika", "Ručna vožnja, animacije, dnevnik Bluetootha") { a.onDiagnostics() }
            }
            Text(
                "Stevo ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun HubGroup(label: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        label.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp),
    )
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainer), content = content)
}

@Composable
private fun HubRow(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(cs.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = cs.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = cs.onSurfaceVariant)
    }
}

// ---- pages and their parts ------------------------------------------------------------------------

@Composable
private fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(topBar = { TopBar(title, onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun AppCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content) }
}

@Composable
private fun CardTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium)

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** One choice out of a few, as segmented buttons (labels short enough to fit). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segments(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected, onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, onDone: () -> Unit) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("%.2f×".format(value), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, onValueChangeFinished = onDone)
    }
}

// ---- pages ----------------------------------------------------------------------------------------

@Composable
private fun CharacterPage(brain: StevoBrain) {
    var name by remember { mutableStateOf(brain.name) }
    var personality by remember { mutableStateOf(brain.personality) }
    AppCard {
        CardTitle("Ime")
        OutlinedTextField(name, { name = it; brain.name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Hint("Ovako se predstavlja i na ovo ime se javlja.")
    }
    AppCard {
        CardTitle("Kakav je")
        OutlinedTextField(personality, { personality = it; brain.personality = it }, minLines = 4, modifier = Modifier.fillMaxWidth())
        Hint("Opiši ga svojim riječima, može i na hrvatskom: \"Govori kao stari radijski spiker\", \"Šapuće i sve mu je tajna\", \"Pomalo je mrzovoljan prije kave\". Vrijedi od idućeg razgovora.")
        TextButton(onClick = { personality = StevoBrain.DEFAULT_PERSONALITY; brain.personality = personality }) { Text("Vrati zadano") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoicePage(brain: StevoBrain) {
    var lang by remember { mutableStateOf(brain.language) }
    var voice by remember { mutableStateOf(brain.voice) }
    var speed by remember { mutableFloatStateOf(brain.speed) }
    var pitch by remember { mutableFloatStateOf(brain.pitch) }
    var interrupt by remember { mutableStateOf(brain.allowInterrupt) }
    AppCard {
        CardTitle("Jezik")
        Segments(StevoBrain.LANGUAGES.map { it.key to if (it.key == "auto") "Auto" else it.value }, lang) { lang = it; brain.language = it }
        Hint(if (lang == "auto") "Odgovara na jeziku na kojem mu se obratiš." else "Uvijek priča ${StevoBrain.LANGUAGES[lang]?.lowercase()}, dok ga ne zamoliš drugačije.")
    }
    AppCard {
        CardTitle("Glas")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StevoBrain.VOICES.forEach { v -> FilterChip(voice == v, { voice = v; brain.voice = v }, { Text(v) }) }
        }
        Spacer(Modifier.height(4.dp))
        SliderRow("Brzina", speed, 0.7f..1.6f, { speed = it }) { brain.speed = speed }
        SliderRow("Visina", pitch, 0.6f..1.8f, { pitch = it }) { brain.pitch = pitch }
        Hint("Brzina i visina se mijenjaju odmah, i usred razgovora.")
        TextButton(onClick = { speed = 1f; pitch = 1f; brain.speed = 1f; brain.pitch = 1f }) { Text("Vrati na 1×") }
    }
    AppCard {
        SwitchRow("Mogu ga prekinuti", "Mikrofon ostaje otvoren dok on priča. Isključi ako sam sebe čuje (jeka).", interrupt) {
            interrupt = it; brain.allowInterrupt = it
        }
    }
}

private val dateFmt = SimpleDateFormat("d.M.yyyy. HH:mm", Locale.ROOT)

@Composable
private fun MemoryPage(memory: Memory, a: SettingsActions) {
    var level by remember { mutableIntStateOf(memory.level) }
    var keep by remember { mutableIntStateOf(memory.keepDays) }
    var confirm by remember { mutableStateOf(false) }
    val notes by memory.notes.collectAsState()
    val summaries by memory.summaries.collectAsState()
    val conversations by memory.conversations.collectAsState()
    AppCard {
        CardTitle("Koliko pamti")
        Segments(listOf(0 to "Ništa", 1 to "Bilješke", 2 to "+ Sažeci", 3 to "Sve"), level) { level = it; memory.level = it }
        Hint(
            when (level) {
                0 -> "Svaki razgovor kreće od nule."
                1 -> "Pamti važne činjenice o tebi (ime, obitelj, planove) iz razgovora u razgovor."
                2 -> "Činjenice i kratki sažetak svakog razgovora: zna o čemu ste pričali."
                else -> "Činjenice, sažeci i svi razgovori u arhivi, koju pretražuje kad spomeneš nešto od prije."
            }
        )
        Spacer(Modifier.height(4.dp))
        CardTitle("Čuvaj razgovore")
        Segments(Memory.KEEP.map { it.key to it.value }, keep) { keep = it; memory.keepDays = it }
        Hint("Bilješke ostaju dok ih ne obrišeš. Sve je spremljeno samo na ovom mobitelu.")
    }
    AppCard {
        CardTitle("Što zna o tebi (${notes.size})")
        if (notes.isEmpty()) Hint("Još ništa. Reci mu nešto o sebi.")
        notes.reversed().forEachIndexed { i, n ->
            if (i > 0) HorizontalDivider()
            Entry(n.text, dateFmt.format(Date(n.at))) { memory.deleteNote(n) }
        }
    }
    AppCard {
        CardTitle("Razgovori (${summaries.size})")
        if (summaries.isEmpty()) Hint("Sažetak nastaje nakon svakog razgovora.")
        summaries.reversed().take(30).forEachIndexed { i, s ->
            if (i > 0) HorizontalDivider()
            Entry(s.text, dateFmt.format(Date(s.at))) { memory.deleteSummary(s) }
        }
    }
    AppCard {
        CardTitle("Arhiva")
        Hint("$conversations spremljenih razgovora.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = a.onExport) { Text("Izvoz") }
            OutlinedButton(onClick = a.onImport) { Text("Uvoz") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { confirm = true }) { Text("Zaboravi sve", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Zaboraviti sve?") },
        text = { Text("Brišu se sve bilješke, sažeci i razgovori. Ne može se vratiti, osim iz izvoza.") },
        confirmButton = { TextButton(onClick = { memory.forgetEverything(); confirm = false }) { Text("Zaboravi") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Odustani") } },
    )
}

@Composable
private fun Entry(text: String, date: String, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Text(date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDelete) { Icon(Icons.Rounded.Close, contentDescription = "Obriši", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun RobotPage(ble: LooiBle, brain: StevoBrain, a: SettingsActions) {
    val robot by ble.state.collectAsState()
    var charging by remember { mutableStateOf(brain.phoneCharging) }
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CardTitle(if (robot == ConnState.READY) ble.deviceName ?: "LOOI" else "Robot")
                Hint(when (robot) {
                    ConnState.READY -> "Spojen"
                    ConnState.SCANNING -> "Tražim…"
                    ConnState.CONNECTING -> "Spajam…"
                    else -> "Nije spojen. Službena LOOI aplikacija mora biti zatvorena."
                })
            }
            if (robot == ConnState.READY) OutlinedButton(onClick = { ble.disconnect() }) { Text("Odspoji") }
            else FilledTonalButton(onClick = a.onConnect) { Text("Spoji") }
        }
    }
    AppCard {
        SwitchRow("Punjenje mobitela", "Eksperimentalno. Isključi da baza ne troši svoju bateriju na mobitel.", charging) {
            charging = it; brain.phoneCharging = it; a.onCharging(it)
        }
    }
}

@Composable
private fun CameraPage(brain: StevoBrain, a: SettingsActions) {
    var tracking by remember { mutableStateOf(brain.tracking) }
    var mirror by remember { mutableStateOf(brain.mirror) }
    var vision by remember { mutableStateOf(brain.vision) }
    var screen by remember { mutableStateOf(brain.keepScreenOn) }
    AppCard {
        SwitchRow("Praćenje lica", "Okreće se i gleda prema tebi. Radi na mobitelu, bez interneta.", tracking) { tracking = it; brain.tracking = it; a.onApply() }
        HorizontalDivider()
        SwitchRow("Zrcali smjer", "Uključi ako se okreće od tebe umjesto prema tebi.", mirror) { mirror = it; brain.mirror = it }
        HorizontalDivider()
        SwitchRow("Stevo vidi", "U razgovoru dobiva sličicu s kamere svakih 1,5 s, pa komentira i sam slika lijepe stvari. Troši više.", vision) {
            vision = it; brain.vision = it; a.onApply()
        }
    }
    AppCard {
        SwitchRow("Ekran uvijek upaljen", "Ekran se ne gasi ni izvan lica.", screen) { screen = it; brain.keepScreenOn = it; a.onApply() }
    }
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CardTitle("Fotografije")
                Hint("Spremaju se u galeriju, mapa Pictures/Stevo.")
            }
            FilledTonalButton(onClick = a.onPhoto) { Text("Slikaj") }
        }
    }
}

@Composable
private fun GeminiPage(brain: StevoBrain) {
    var key by remember { mutableStateOf(brain.apiKey) }
    var saved by remember { mutableStateOf(brain.apiKey.isNotBlank()) }
    AppCard {
        CardTitle("API ključ")
        OutlinedTextField(
            key, { key = it; saved = false }, singleLine = true, placeholder = { Text("AIza…") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = { brain.apiKey = key; saved = key.isNotBlank() }) { Text("Spremi") }
            if (saved) Text("   ✓ spremljen", color = MaterialTheme.colorScheme.primary)
        }
        Hint("Besplatno ga napraviš na aistudio.google.com → Get API key. Ostaje samo na ovom mobitelu.")
    }
    AppCard {
        CardTitle("Modeli")
        Hint("Razgovor: ${StevoBrain.MODEL.removePrefix("models/")}\nPamćenje (sažeci): ${Memory.SUMMARY_MODEL}")
    }
}
