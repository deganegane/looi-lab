package io.github.deganegane.looilab

import java.util.Calendar
import java.util.Locale
import java.util.UUID

/**
 * What we know about the LOOI base, decoded from a Bluetooth HCI snoop log of the official app
 * (2026-11-10). Things marked "guess" are still to be confirmed with this app.
 */
object LooiProtocol {
    private fun uuid16(short: Int) =
        UUID.fromString(String.format(Locale.ROOT, "0000%04x-0000-1000-8000-00805f9b34fb", short))

    val SERVICE: UUID = uuid16(0x00FF)
    val CCCD: UUID = uuid16(0x2902)

    /** Rotation, written without response every ~50 ms while held: + = left, - = right (confirmed). */
    val ROTATE: UUID = uuid16(0xFED0)
    /** Head tilt, written right after ROTATE: - = up, + = down (confirmed). */
    val TILT: UUID = uuid16(0xFED1)
    /** Write-only, never used by the official app (to try). */
    val FED3: UUID = uuid16(0xFED3)
    /** Written 00 by the app now and then (guess: some mode switch). */
    val FED2: UUID = uuid16(0xFED2)
    /** Motion scripts ("animations"), written with response in 20-byte fragments. */
    val SCRIPT: UUID = uuid16(0xFE00)
    /** Read by the app every 30 s, answers 00 01 (guess: status / keep-alive). */
    val STATUS: UUID = uuid16(0xFED8)
    /** Events from the base: sensors, script finished, ... (notify). */
    val EVENTS: UUID = uuid16(0xFED9)
    /** Control: 03 = hello, 81 01 = start (sent during the handshake). */
    val CONTROL: UUID = uuid16(0xFEDA)
    /** Clock: "A2026 11 10 20 41 39", the base answers "Y" (notify). */
    val CLOCK: UUID = uuid16(0xFEF0)

    val ALL_KNOWN = listOf(
        "FED0 rotacija" to ROTATE, "FED1 glava" to TILT, "FED2" to FED2, "FED3" to FED3,
        "FE00 skripte" to SCRIPT, "FED8 status" to STATUS, "FED9 događaji" to EVENTS,
        "FEDA kontrola" to CONTROL, "FEF0 sat" to CLOCK,
    )

    val HELLO = byteArrayOf(0x03)
    val START = byteArrayOf(0x81.toByte(), 0x01)

    fun clockCommand(now: Calendar = Calendar.getInstance()): ByteArray = String.format(
        Locale.ROOT,
        "A%04d %02d %02d %02d %02d %02d",
        now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH),
        now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.SECOND),
    ).toByteArray(Charsets.US_ASCII)

    /** The app sent 00 <signed byte>; -128..127. */
    fun driveValue(v: Int): ByteArray = byteArrayOf(0, v.coerceIn(-127, 127).toByte())

    /** Stop whatever script is running (the app sends this before every new animation). */
    val STOP_SCRIPT = byteArrayOf(0x00, 0x00, 0x01, 0xFF.toByte(), 0x00, 0x01)

    /**
     * A script is a list of keyframes (tick, value, channel); one tick ≈ 40 ms, value is a signed
     * byte, tick 0xFF ends that channel. Channels (confirmed by replaying the animations):
     * 1 = drive (+ forward, - back), 2 = rotation (+ left, - right),
     * 3 = head position, absolute (about 0 = up, 100 = down, 50 = middle). Sent as fragments: [fragment index, script id, up to 18 bytes].
     */
    fun scriptFragments(id: Int, keyframes: ByteArray): List<ByteArray> =
        keyframes.toList().chunked(18).mapIndexed { i, chunk ->
            byteArrayOf(i.toByte(), id.toByte()) + chunk.toByteArray()
        }

    /** One channel held at [value] for [ticks], then ended. */
    fun holdScript(channel: Int, value: Int, ticks: Int): ByteArray {
        val v = value.coerceIn(-127, 127).toByte()
        val c = channel.toByte()
        return byteArrayOf(0, v, c, ticks.coerceIn(1, 254).toByte(), v, c, 0xFF.toByte(), 0, c)
    }

    /** Human-readable meaning of a notification from EVENTS. */
    fun describeEvent(b: ByteArray): String {
        if (b.isEmpty()) return "prazno"
        val u = { i: Int -> b.getOrNull(i)?.toInt()?.and(0xFF) }
        return when (u(0)) {
            0x01 -> "senzori: " + (1 until b.size).joinToString(" ") { if (u(it) == 1) "■" else "□" } +
                "  (rub stola?)"
            0x02 -> if (b.size >= 3) {
                val s = ((u(1)!! shl 8) or u(2)!!).toShort().toInt()
                "analogno: $s  (nagib?)"
            } else "analogno"
            0x07 -> "skripta #${u(1)} gotova"
            0x09 -> "događaj 09 = ${u(1)}"
            0x0A -> "događaj 0A = ${u(1)}"
            0x0B -> "događaj 0B = ${u(1)}"
            0x11 -> "status 11: ${b.drop(1).joinToString(" ") { "%02x".format(it) }}"
            else -> "nepoznato"
        }
    }
}

fun ByteArray.hex(): String = joinToString(" ") { "%02x".format(it) }

fun String.parseHex(): ByteArray? {
    val clean = filter { !it.isWhitespace() }
    if (clean.isEmpty() || clean.length % 2 != 0) return null
    return try {
        ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    } catch (e: NumberFormatException) {
        null
    }
}

/** An animation the official app played, replayed byte for byte. */
data class CapturedAnim(
    val number: Int,
    val channels: String,
    val ticks: Int,
    val timesSeen: Int,
    val keyframesHex: String,
) {
    val keyframes: ByteArray get() = keyframesHex.parseHex()!!
    val name: String get() = CapturedAnims.names.getOrNull(number - 1) ?: "?"
    val seconds: Double get() = ticks * 0.04
}

object CapturedAnims {
    /** What each one does, as seen on the robot (2026-11-10). */
    val names = listOf(
        "naprijed-nazad", "klimanje", "natrag", "lijevo", "desno", "glava gore", "glava dolje",
        "naprijed, glava", "lagano natrag", "malo iza", "gore, lijevo-desno", "lijevo, kimne, natrag",
        "glava gore", "glava gore-dolje, naprijed-nazad", "naprijed", "gore desno, lijevo dolje",
        "lijevo-desno-lijevo", "naprijed, gore-dolje, natrag", "naprijed-nazad ×2", "naprijed",
        "gore, kimne", "gore, kimne", "dolje, lijevo-desno, gore",
    )

    val all = listOf(
        CapturedAnim(1, "1", 90, 17, "0002011f0d014bef015500015a0001ff0001"),
        CapturedAnim(2, "3", 35, 2, "034603093c03103c03144603194603233c03ff0003"),
        CapturedAnim(3, "1", 17, 4, "00b00111b001ff0001"),
        CapturedAnim(4, "2", 33, 1, "003202213202ff0002"),
        CapturedAnim(5, "2", 33, 1, "00ce0221ce02ff0002"),
        CapturedAnim(6, "3", 25, 1, "003203071c030f0d03190803ff0003"),
        CapturedAnim(7, "3", 25, 2, "0032030532030c4e03196203ff0003"),
        CapturedAnim(8, "3", 25, 5, "004b03194b03ff0003"),
        CapturedAnim(9, "1", 66, 4, "00010109fb011bfd0123fe0127fe012e0501420101ff0001"),
        CapturedAnim(10, "1", 3, 3, "00000101f501030001ff0001"),
        CapturedAnim(11, "2+3", 67, 1, "000002003203013203061e020923030a1e020f00021200021414021614021800021c00021e140221140223000232000232230336e102393203ff00033de102430002ff0002"),
        CapturedAnim(12, "2+3", 125, 1, "0000020032030200020714021014021300021900022832032c3c03303203383203401e03640002641e036aeb0271eb0273320379f5027d0002ff00027d3203ff0003"),
        CapturedAnim(13, "3", 10, 1, "0032030232030a2303ff0003"),
        CapturedAnim(14, "1+3", 175, 1, "0000010032030b14010b230319000119230326eb01263c03320001323c033f14013f23034b00014b2303563c0357eb01640001643c037114017123037d00017d230388eb01893c03960001ff0001a03c03af3203ff0003"),
        CapturedAnim(15, "1", 17, 3, "005001115001ff0001"),
        CapturedAnim(16, "2+3", 67, 2, "00000200320301320306e1020923030ae1020f000212000214eb0216eb021800021c00021eeb0221eb02230002320002322303361e02393203ff00033d1e02430002ff0002"),
        CapturedAnim(17, "2+3", 95, 1, "0000020032030750021700021932031a0002ff00031fa1023c0002400102482f025f0002ff0002"),
        CapturedAnim(18, "1+3", 50, 3, "0000010032030a0a010a32030f0a010f2a0319fe011e2a0323f401233203ff000328f50132fe01ff0001"),
        CapturedAnim(19, "1", 150, 2, "0000010b0a0119000126f5013200013f0a014b000157f501640001710a017d000188f501960001ff0001"),
        CapturedAnim(20, "1", 38, 1, "000001031e01261e01ff0001"),
        CapturedAnim(21, "1+3", 100, 1, "003203020f03021f0a020001190f03250003282d032c0003300003340f03360f033b32034b00050000005832035d2303643203ff0003"),
        CapturedAnim(22, "3", 25, 3, "003203071903193203ff0003"),
        CapturedAnim(23, "2+3", 125, 1, "000002003203020002023203051e020928020b5003101e02130002145f0332000235eb0239eb023c00024500024814024f14025200026c00026e5f0370e10274460375d7027ae1027d0002ff00027d3203ff0003"),
        CapturedAnim(24, "2", 80, 1, "0000020200020521020b000221000226eb0228eb022e0002450002491402500002ff0002"),
        CapturedAnim(25, "1+3", 10, 1, "0000010032030a0001ff00010a3203ff0003"),
    )
}
