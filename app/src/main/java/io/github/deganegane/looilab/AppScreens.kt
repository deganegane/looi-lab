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
        colorScheme = darkColorScheme(
            primary = Amber, onPrimary = Ink,
            secondaryContainer = Color(0xFF4A2A14), onSecondaryContainer = Color(0xFFFFDCC6),
            background = Color(0xFF120B07), surface = Color(0xFF120B07),
            surfaceContainer = Color(0xFF1F1510), surfaceContainerHigh = Color(0xFF2A1D16),
            surfaceContainerHighest = Color(0xFF35251C),
            onSurface = Color(0xFFF2E6DE), onSurfaceVariant = Color(0xFFBFA898),
            outline = Color(0xFF6B5446), outlineVariant = Color(0xFF3F2F25),
        ),
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

