package io.github.deganegane.looilab

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Stevo's body follows his face and his voice on its own, so he is lively even when Gemini
 * forgets to call move:
 *  - every new expression gets a matching gesture (laugh → wiggle, sad → head down, …);
 *  - while he speaks, small head bobs and turns now and then, like hands while talking.
 * [level]: 0 calm (nothing automatic), 1 normal, 2 lively.
 */
class BodyLanguage(
    private val ble: LooiBle,
    private val level: () -> Int,
    private val speaking: () -> Boolean,
    private val asleep: () -> Boolean,
) {
    private var lastAuto = 0L

    private fun ready(minGapMs: Long): Boolean {
        val now = System.currentTimeMillis()
        if (level() == 0 || asleep() || ble.state.value != ConnState.READY || ble.manualDrive) return false
        // Do not cut into a gesture that is still running, nor into the last automatic one.
        if (now < ble.busyUntil + 300 || now - lastAuto < minGapMs) return false
        lastAuto = now
        return true
    }

    private fun play(label: String, k: ByteArray) { ble.launch { playScript(k, "tijelo: $label") } }
    private fun anim(n: Int, label: String) = play(label, CapturedAnims.all[n - 1].keyframes)

    fun onExpression(e: Expr) {
        if (!ready(1_200)) return
        val edge = ble.atEdge
        when (e) {
            Expr.HAPPY -> play("sretno", Moves.nod(ble))
            Expr.LAUGH -> play("smijeh", Moves.wiggle(ble))
            Expr.EXCITED -> if (!edge && level() >= 2) anim(23, "oduševljen ples") else play("oduševljeno", Moves.wiggle(ble))
            Expr.LOVE, Expr.WINK -> play("kimanje", Moves.nod(ble))
            Expr.ANGRY -> play("odmahuje", Moves.shake(ble))
            Expr.SAD -> play("tužno", Moves.droop(ble))
            Expr.BORED, Expr.SLEEPY -> play("dosadno", Moves.droop(ble))
            Expr.THINKING -> play("razmišlja", Moves.think(ble))
            Expr.SURPRISED -> play("iznenađen", Moves.startle(ble, driveBack = !edge && ble.floor.isNotEmpty()))
            Expr.WORRIED -> play("zabrinut", Moves.peek(ble))
            Expr.CONFUSED -> play("zbunjen", Moves.doubleTake(ble))
            Expr.SHY -> play("sramežljiv", Moves.tiltAway(ble))
            Expr.CURIOUS -> play("znatiželjan", Moves.perkUp(ble))
            Expr.DIZZY -> play("ošamućen", Moves.shake(ble))
            Expr.NEUTRAL -> {}
        }
    }

    /** Small movements while he talks. */
    fun start(scope: CoroutineScope) = scope.launch {
        while (isActive) {
            delay(500)
            if (!speaking()) continue
            val gap = if (level() >= 2) Random.nextLong(2_500, 5_000) else Random.nextLong(5_000, 9_000)
            if (!ready(gap)) continue
            val h = ble.headPos
            when (Random.nextInt(5)) {
                0, 1 -> play("govor: kimne", Move(h).head(h - 8, 4).head(h + 7, 5).head(h, 5).build())
                2 -> play("govor: okret", Move(h).turn(16, 7).wait(3).turn(-16, 7).build())
                3 -> play("govor: glava gore", Move(h).head(h - 14, 7).wait(14).head(h, 8).build())
                else -> play("govor: nagib i okret", Move(h).turn(-14, 6).wait(8).turn(14, 6).at(0).head(h + 8, 6).wait(8).head(h, 6).build())
            }
        }
    }
}
