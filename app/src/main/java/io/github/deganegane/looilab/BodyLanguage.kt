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
    private fun K(a: IntArray) = LooiProtocol.keys(*a)

    private fun ready(minGapMs: Long): Boolean {
        val now = System.currentTimeMillis()
        if (level() == 0 || asleep() || ble.state.value != ConnState.READY || ble.manualDrive) return false
        // Do not cut into a gesture Gemini just asked for, nor into the last automatic one.
        if (now - ble.lastGestureAt < 1_800 || now - lastAuto < minGapMs) return false
        lastAuto = now
        return true
    }

    private fun play(label: String, k: ByteArray) = ble.launch { playScript(k, "tijelo: $label") }
    private fun anim(n: Int, label: String) = play(label, CapturedAnims.all[n - 1].keyframes)

    fun onExpression(e: Expr) {
        if (!ready(1_200)) return
        val edge = ble.atEdge
        when (e) {
            Expr.HAPPY -> anim(22, "sretno")
            Expr.LAUGH -> anim(11, "smijeh")
            Expr.EXCITED -> if (!edge && level() >= 2) anim(23, "oduševljen ples") else anim(11, "oduševljeno")
            Expr.LOVE, Expr.WINK -> anim(2, "kimanje")
            Expr.ANGRY -> anim(17, "odmahuje")
            Expr.SAD -> play("tužno", K(intArrayOf(0, 50, 3, 14, 80, 3, 60, 80, 3, 75, 55, 3)))
            Expr.BORED, Expr.SLEEPY -> play("dosadno", K(intArrayOf(0, 50, 3, 20, 72, 3, 50, 72, 3, 65, 55, 3)))
            Expr.THINKING -> play("razmišlja", K(intArrayOf(0, 50, 3, 10, 26, 3, 40, 26, 3, 52, 50, 3, 0, 0, 2, 4, 22, 2, 12, 0, 2)))
            Expr.SURPRISED -> play("iznenađen", K(intArrayOf(0, 50, 3, 4, 30, 3, 25, 30, 3, 35, 50, 3) + if (edge) intArrayOf() else intArrayOf(0, -35, 1, 5, 0, 1)))
            Expr.WORRIED -> if (edge) anim(13, "zabrinut") else anim(9, "zabrinut, malo natrag")
            Expr.CONFUSED -> play("zbunjen", K(intArrayOf(0, 18, 2, 6, 0, 2, 10, -18, 2, 16, 0, 2, 0, 50, 3, 8, 38, 3, 22, 50, 3)))
            Expr.SHY -> play("sramežljiv", K(intArrayOf(0, 25, 2, 8, 0, 2, 0, 50, 3, 10, 70, 3, 45, 70, 3, 58, 52, 3)))
            Expr.CURIOUS -> play("znatiželjan", K(intArrayOf(0, 50, 3, 8, 38, 3, 30, 38, 3, 40, 50, 3)))
            Expr.DIZZY -> anim(17, "ošamućen")
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
            when (Random.nextInt(5)) {
                0, 1 -> play("govor: kimne", K(intArrayOf(0, 50, 3, 5, 42, 3, 10, 56, 3, 16, 50, 3)))
                2 -> play("govor: okret", K(intArrayOf(0, 16, 2, 7, 0, 2, 9, -16, 2, 16, 0, 2)))
                3 -> play("govor: glava gore", K(intArrayOf(0, 50, 3, 8, 36, 3, 22, 36, 3, 30, 50, 3)))
                else -> play("govor: nagib i okret", K(intArrayOf(0, -14, 2, 6, 0, 2, 0, 50, 3, 6, 58, 3, 14, 50, 3)))
            }
        }
    }
}
