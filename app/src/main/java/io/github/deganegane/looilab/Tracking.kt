package io.github.deganegane.looilab

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Keeps Stevo turned toward the nearest face. The eyes always follow. The body only moves while
 * the phone sits on the robot ([bodyAllowed]: the fullscreen face, landscape):
 *  - turning: FED0 speed, proportional to how far off-centre the face is;
 *  - head: absolute positions on script channel 3, nudged a little at a time and kept between
 *    up and down; with no face for a few seconds it glides back to the middle.
 * After a gesture the body is left alone for a moment, so nods and dances are not cut short.
 */
class Tracking(
    private val eyes: CameraEyes,
    private val face: FaceState,
    private val ble: LooiBle,
    private val mirror: () -> Boolean,
    private val bodyAllowed: () -> Boolean,
) {
    private var job: Job? = null
    private var lastSeen = 0L
    private var turning = false
    private var lastHeadCmd = 0L
    private var centred = true

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
        stopTurning()
        face.lookX = 0f; face.lookY = 0f
    }

    private fun stopTurning() {
        if (turning) { turning = false; ble.launch { move(0, 0) } }
    }

    private fun headTo(pos: Int) {
        lastHeadCmd = System.currentTimeMillis()
        val p = pos.coerceIn(LooiProtocol.HEAD_UP, LooiProtocol.HEAD_DOWN)
        ble.launch { playScript(LooiProtocol.headTo(p, 6), "praćenje: glava $p", gesture = false) }
    }

    private fun step() {
        val now = System.currentTimeMillis()
        val f = eyes.face.value?.takeIf { now - it.at < 500 }
        val canMove = bodyAllowed() && !ble.manualDrive && ble.state.value == ConnState.READY &&
            now > ble.busyUntil + 500

        if (f == null) {
            stopTurning()
            face.lookX *= 0.9f; face.lookY *= 0.9f
            // Lost you for a while: head back to the middle, once.
            if (canMove && !centred && now - lastSeen > 3_000) { centred = true; headTo(LooiProtocol.HEAD_MIDDLE) }
            return
        }
        if (lastSeen != 0L && now - lastSeen > 20_000) face.flash(Expr.HAPPY) // you're back!
        lastSeen = now

        val x = if (mirror()) -f.x else f.x // -1 = robot's left
        face.lookX = (-x * 1.1f).coerceIn(-1.2f, 1.2f) // on screen the robot's left is the viewer's right
        face.lookY = (f.y * 0.9f).coerceIn(-1f, 1f)
        if (!canMove) { stopTurning(); return }

        // Turn: proportional, with a dead zone in the middle.
        // A brisk start (the base barely moves below ~15) and more speed the further off you are.
        val rotate = if (abs(x) > 0.16f) (-Math.signum(x) * (18 + abs(x) * 52)).toInt().coerceIn(-65, 65) else 0 // + = left
        if (rotate != 0) { turning = true; ble.launch { move(rotate, 0) } } else stopTurning()

        // Head: small steps toward the face, at most ~3 times a second.
        if (abs(f.y) > 0.22f && now - lastHeadCmd > 350) {
            centred = false
            headTo(ble.headPos + (f.y * 8).toInt())
        }
    }
}
