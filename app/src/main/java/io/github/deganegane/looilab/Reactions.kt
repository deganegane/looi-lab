package io.github.deganegane.looilab

import kotlinx.coroutines.delay

/**
 * Turns what the base reports into faces. Lifted: surprised, then happy while carried.
 * Put down: back to the face it had, with a short smile. Floor sensors partly in the air while
 * standing (an edge): curious. Sensor noise right after lifting or landing is ignored.
 */
class Reactions(private val face: FaceState, private val launch: (suspend () -> Unit) -> Unit) {
    var lifted = false
        private set
    private var before = Expr.NEUTRAL
    private var lastChange = 0L
    private var lastCurious = 0L
    private var allOnSince = 0L

    private fun now() = System.currentTimeMillis()

    fun on(e: ByteArray) {
        val type = e.firstOrNull()?.toInt()?.and(0xFF) ?: return
        when (type) {
            0x0A -> if (e.getOrNull(1)?.toInt() == 1) lift() else putDown()
            0x01 -> floor(e.drop(1).map { it.toInt() == 1 })
        }
    }

    private fun lift() {
        if (lifted) return
        lifted = true; lastChange = now()
        before = face.base
        face.flash(Expr.SURPRISED)
        launch {
            delay(1_300)
            if (lifted) face.set(Expr.HAPPY)
        }
    }

    private fun putDown() {
        if (!lifted) return
        lifted = false; lastChange = now()
        face.set(before)
        face.flash(Expr.HAPPY)
    }

    private fun floor(on: List<Boolean>) {
        if (on.isEmpty()) return
        val t = now()
        when {
            on.none { it } -> lift()                       // all four in the air
            on.all { it } -> {
                // Backup for a missed "put down": standing on all four for a while.
                if (allOnSince == 0L) allOnSince = t
                if (lifted && t - allOnSince > 1_500) putDown()
            }
            else -> {
                allOnSince = 0L
                if (!lifted && t - lastChange > 1_500 && t - lastCurious > 4_000) {
                    lastCurious = t
                    face.flash(Expr.CURIOUS)                // standing at an edge
                }
            }
        }
        if (!on.all { it }) allOnSince = 0L
    }
}
