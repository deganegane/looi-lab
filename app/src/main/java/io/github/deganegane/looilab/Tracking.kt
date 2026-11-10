package io.github.deganegane.looilab

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Keeps the robot turned toward the nearest face: the eyes follow at once, the body turns
 * (FED0) and the head tilts (FED1) when the face drifts out of the middle. Back after a while
 * away: a happy face. [mirror] flips left/right if a phone's camera reports it the other way.
 */
class Tracking(
    private val eyes: CameraEyes,
    private val face: FaceState,
    private val ble: LooiBle,
    private val mirror: () -> Boolean,
    private val busy: () -> Boolean, // e.g. the joystick is in use
) {
    private var job: Job? = null
    private var lastSeen = 0L
    private var moving = false

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                delay(100)
                step()
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        if (moving) { moving = false; ble.launch { move(0, 0) } }
        face.lookX = 0f; face.lookY = 0f
    }

    private fun step() {
        val now = System.currentTimeMillis()
        val f = eyes.face.value?.takeIf { now - it.at < 500 }
        if (f == null) {
            if (moving) { moving = false; ble.launch { move(0, 0) } }
            // Slowly let the eyes return to the middle.
            face.lookX *= 0.9f; face.lookY *= 0.9f
            return
        }
        if (now - lastSeen > 20_000 && lastSeen != 0L) face.flash(Expr.HAPPY) // you're back!
        lastSeen = now

        val x = if (mirror()) -f.x else f.x // -1 = robot's left
        // On the screen the robot's left is the viewer's right.
        face.lookX = (-x * 1.1f).coerceIn(-1.2f, 1.2f)
        face.lookY = (f.y * 0.9f).coerceIn(-1f, 1f)

        if (busy() || ble.state.value != ConnState.READY) return
        val rotate = if (abs(x) > 0.18f) (-x * 40).toInt().coerceIn(-35, 35) else 0 // + = turn left
        val tilt = if (abs(f.y) > 0.25f) (f.y * 30).toInt().coerceIn(-25, 25) else 0  // - = head up
        if (rotate != 0 || tilt != 0) {
            moving = true
            ble.launch { move(rotate, tilt) }
        } else if (moving) {
            moving = false
            ble.launch { move(0, 0) }
        }
    }
}
