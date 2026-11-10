package io.github.deganegane.looilab

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Awake or asleep. Asleep: sleepy eyes, head all the way down, the body stays still.
 * Falls asleep when a conversation ends in silence, or after a few minutes with nobody around.
 * Wakes up when you tap him, pick him up, or (with face tracking) sit in front of him.
 */
class Life(
    private val face: FaceState,
    private val ble: LooiBle,
    private val eyes: CameraEyes,
    private val talking: () -> Boolean,
) {
    var sleeping by mutableStateOf(false)
        private set
    private var sleptAt = 0L
    private var lastInteraction = System.currentTimeMillis()
    private var faceSince = 0L

    /** Anything you do with him (tap, talk, lift) counts as company. */
    fun touched() { lastInteraction = System.currentTimeMillis() }

    fun sleep() {
        if (sleeping) return
        sleeping = true; sleptAt = System.currentTimeMillis()
        face.lookX = 0f; face.lookY = 0f
        face.set(Expr.SLEEPY)
        ble.launch { move(0, 0); playScript(LooiProtocol.headTo(100, 30), "spava: glava dolje", gesture = false) }
    }

    fun wake() {
        touched()
        if (!sleeping) return
        sleeping = false
        face.set(Expr.NEUTRAL)
        face.flash(Expr.HAPPY)
        ble.launch { playScript(LooiProtocol.headTo(LooiProtocol.HEAD_MIDDLE, 14), "budi se: glava u sredinu", gesture = false) }
    }

    fun start(scope: CoroutineScope) = scope.launch {
        while (isActive) {
            delay(1_000)
            val now = System.currentTimeMillis()
            val seen = eyes.face.value?.let { now - it.at < 600 } == true
            if (seen) { if (faceSince == 0L) faceSince = now; lastInteraction = now } else faceSince = 0L
            if (talking()) { lastInteraction = now; continue }
            if (sleeping) {
                // Somebody sat down in front of him for a moment: good morning.
                if (seen && now - faceSince > 1_500 && now - sleptAt > 10_000) wake()
            } else {
                val alone = if (eyes.running) 3 * 60_000L else 6 * 60_000L
                if (now - lastInteraction > alone) sleep()
            }
        }
    }
}
