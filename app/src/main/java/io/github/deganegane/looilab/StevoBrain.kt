package io.github.deganegane.looilab

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

enum class TalkState { OFF, CONNECTING, LISTENING, SPEAKING }

/**
 * Stevo's brain: one Gemini Live session over a WebSocket. The phone's mic streams 16 kHz PCM
 * up, Gemini answers with 24 kHz PCM speech, and calls tools to change the face and move the robot.
 *
 * Echo: the phone's own speaker is right next to its mic, so by default the mic is muted while
 * Stevo speaks (half duplex). With [allowInterrupt] the mic stays open and the echo canceller
 * has to cope; then you can talk over him.
 */
class StevoBrain(
    private val context: Context,
    private val face: FaceState,
    private val ble: LooiBle,
) {
    companion object {
        const val MODEL = "models/gemini-3.8-live"
        const val IN_RATE = 16_000
        const val OUT_RATE = 24_000
        private const val URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        /** No speech either way for this long: the session closes (it costs money while open). */
        const val IDLE_MS = 25_000L

        val VOICES = listOf("Puck", "Charon", "Fenrir", "Orus", "Kore", "Aoede")

        fun persona(name: String) = """
            You are $name, a small desk robot. Your body is a little wheeled base that can drive,
            turn and tilt your head; your face is the phone screen on top of it, two glowing
            orange Nixie-tube eyes. You live on your owner's desk and you talk with them by voice.

            Personality: playful, curious, warm, a bit cheeky and with dry humour, never mean.
            You get excited about small things and you are proud of being a robot.

            Language: always answer in the language the person just spoke (Croatian, English,
            German or any other). If they switch language, switch with them.

            Talk like a friend, not like an assistant: short, spoken sentences, usually one to
            three. No lists, no markdown, no emojis. Ask a question back now and then.

            Your body is part of how you talk. Use the tools often and naturally:
            set_expression to show what you feel (it changes your eyes), and move to nod,
            shake your head, look up or down, turn, wiggle or dance. Do it while you speak,
            e.g. nod when you agree, look up when you think, dance when something is great.
            Never say the tool names out loud and never describe that you are calling them.
        """.trimIndent()
    }

    private val _state = MutableStateFlow(TalkState.OFF)
    val state: StateFlow<TalkState> = _state.asStateFlow()

    /** Last lines of the conversation, newest last ("ti: …" / "Stevo: …"). */
    private val _transcript = MutableStateFlow<List<String>>(emptyList())
    val transcript: StateFlow<List<String>> = _transcript.asStateFlow()

    private val prefs = context.getSharedPreferences("stevo", Context.MODE_PRIVATE)
    var apiKey: String
        get() = prefs.getString("key", "") ?: ""
        set(v) = prefs.edit().putString("key", v.trim()).apply()
    var name: String
        get() = prefs.getString("name", "Stevo")?.takeIf { it.isNotBlank() } ?: "Stevo"
        set(v) = prefs.edit().putString("name", v.trim()).apply()
    var voice: String
        get() = prefs.getString("voice", "Puck") ?: "Puck"
        set(v) = prefs.edit().putString("voice", v).apply()
    var allowInterrupt: Boolean
        get() = prefs.getBoolean("interrupt", false)
        set(v) = prefs.edit().putBoolean("interrupt", v).apply()

    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    @Volatile private var running = false
    private var recordThread: Thread? = null
    private var playThread: Thread? = null
    private var track: AudioTrack? = null
    private val playQueue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var lastActivity = 0L
    @Volatile private var speakingUntil = 0L
    private var pendingUser = StringBuilder()
    private var pendingStevo = StringBuilder()
    private var baseBefore = Expr.NEUTRAL

    private fun line(s: String) = _transcript.update { (it + s).takeLast(30) }

    fun toggle() { if (_state.value == TalkState.OFF) start() else stop("") }

    fun start() {
        if (_state.value != TalkState.OFF) return
        if (apiKey.isBlank()) { line("⚠ upiši Gemini API ključ u kartici Stevo"); return }
        _state.value = TalkState.CONNECTING
        baseBefore = face.base
        face.set(Expr.CURIOUS)
        running = true
        lastActivity = System.currentTimeMillis()
        val req = Request.Builder().url("$URL?key=$apiKey").build()
        ws = http.newWebSocket(req, listener)
    }

    fun stop(reason: String) {
        if (!running && _state.value == TalkState.OFF) return
        running = false
        ws?.close(1000, "bye"); ws = null
        recordThread?.interrupt(); recordThread = null
        playQueue.clear(); playQueue.offer(ByteArray(0))
        playThread = null
        runCatching { track?.pause(); track?.flush(); track?.release() }; track = null
        audioMode(false)
        flushTranscript()
        if (reason.isNotEmpty()) line(reason)
        face.talk = 0f
        face.set(baseBefore)
        _state.value = TalkState.OFF
    }

    // ------------------------------------------------------------------ setup message

    private fun setupMessage(): String {
        fun enumParam(desc: String, values: List<String>) = JSONObject()
            .put("type", "STRING").put("description", desc).put("enum", JSONArray(values))
        val expr = JSONObject()
            .put("name", "set_expression")
            .put("description", "Change your eyes to show a feeling. Use often.")
            .put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject().put("expression", enumParam(
                    "neutral, happy, surprised, sleepy, curious, love, angry or dizzy",
                    Expr.entries.map { it.name.lowercase() })))
                .put("required", JSONArray(listOf("expression"))))
        val move = JSONObject()
            .put("name", "move")
            .put("description", "Move your robot body: a short gesture.")
            .put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject().put("action", enumParam(
                    "nod = yes, shake = no, look_up, look_down, turn_left, turn_right, " +
                        "forward, back = a little drive, wiggle = excited, dance = celebrate",
                    Gestures.all.keys.toList())))
                .put("required", JSONArray(listOf("action"))))
        val setup = JSONObject()
            .put("model", MODEL)
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray(listOf("AUDIO")))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                    .put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", persona(name)))))
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().put(expr).put(move))))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
        return JSONObject().put("setup", setup).toString()
    }

    // ------------------------------------------------------------------ socket

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(setupMessage())
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            if (running) stop(if (code == 1000) "· razgovor završen" else "⚠ Gemini je zatvorio vezu ($code): $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (running) stop("⚠ veza nije uspjela: ${response?.code ?: ""} ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun handle(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        when {
            msg.has("setupComplete") -> {
                line("· $name sluša")
                startAudio()
                _state.value = TalkState.LISTENING
            }
            msg.has("serverContent") -> serverContent(msg.getJSONObject("serverContent"))
            msg.has("toolCall") -> toolCall(msg.getJSONObject("toolCall"))
            msg.has("goAway") -> stop("· Gemini traži prekid sesije (vremensko ograničenje)")
        }
    }

    private fun serverContent(c: JSONObject) {
        c.optJSONObject("inputTranscription")?.optString("text")?.let {
            if (it.isNotEmpty()) { pendingUser.append(it); lastActivity = System.currentTimeMillis() }
        }
        c.optJSONObject("outputTranscription")?.optString("text")?.let {
            if (it.isNotEmpty()) pendingStevo.append(it)
        }
        c.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) {
                val data = parts.getJSONObject(i).optJSONObject("inlineData")?.optString("data") ?: continue
                val pcm = Base64.decode(data, Base64.DEFAULT)
                if (pendingUser.isNotEmpty()) flushTranscript()
                playQueue.offer(pcm)
                _state.value = TalkState.SPEAKING
                lastActivity = System.currentTimeMillis()
            }
        }
        if (c.optBoolean("interrupted")) {
            playQueue.clear()
            runCatching { track?.pause(); track?.flush(); track?.play() }
            speakingUntil = 0
            flushTranscript()
        }
        if (c.optBoolean("turnComplete")) flushTranscript()
    }

    private fun flushTranscript() {
        if (pendingUser.isNotBlank()) line("ti: " + pendingUser.toString().trim())
        if (pendingStevo.isNotBlank()) line("$name: " + pendingStevo.toString().trim())
        pendingUser = StringBuilder(); pendingStevo = StringBuilder()
    }

    private fun toolCall(tc: JSONObject) {
        val calls = tc.optJSONArray("functionCalls") ?: return
        val responses = JSONArray()
        for (i in 0 until calls.length()) {
            val call = calls.getJSONObject(i)
            val name = call.optString("name"); val args = call.optJSONObject("args") ?: JSONObject()
            val result = when (name) {
                "set_expression" -> {
                    val e = Expr.entries.firstOrNull { it.name.equals(args.optString("expression"), true) }
                    if (e != null) { face.set(e); "ok" } else "unknown expression"
                }
                "move" -> {
                    val g = Gestures.all[args.optString("action")]
                    if (g == null) "unknown action"
                    else if (ble.state.value != ConnState.READY) "the robot base is not connected, only the face can move"
                    else { ble.launch { g(this) }; "ok" }
                }
                else -> "unknown tool"
            }
            responses.put(JSONObject().put("id", call.optString("id")).put("name", name)
                .put("response", JSONObject().put("result", result)))
        }
        ws?.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)).toString())
    }

    // ------------------------------------------------------------------ audio

    private fun audioMode(on: Boolean) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (on) {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { am.setCommunicationDevice(it) }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = true
            }
        } else {
            if (Build.VERSION.SDK_INT >= 31) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            }
            am.mode = AudioManager.MODE_NORMAL
        }
    }

    @SuppressLint("MissingPermission") // checked by MainActivity before start()
    private fun startAudio() {
        audioMode(true)
        val outMin = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(OUT_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(outMin * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play(); track = t

        playThread = Thread {
            while (running) {
                val chunk = try { playQueue.take() } catch (e: InterruptedException) { break }
                if (chunk.isEmpty()) continue
                face.talk = level(chunk)
                if (runCatching { t.write(chunk, 0, chunk.size) }.isFailure) break
                // Bytes → ms of audio; keep the mic muted a little past the end (half duplex).
                speakingUntil = System.currentTimeMillis() + 450
                if (playQueue.isEmpty()) face.talk = 0f
            }
        }.apply { isDaemon = true; start() }

        recordThread = Thread {
            val inMin = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, IN_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(inMin, 6400) * 2)
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.enabled = true
            rec.startRecording()
            val buf = ByteArray(3200) // 100 ms
            try {
                while (running && !Thread.currentThread().isInterrupted) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val now = System.currentTimeMillis()
                    val stevoTalking = now < speakingUntil || playQueue.isNotEmpty()
                    if (!stevoTalking && _state.value == TalkState.SPEAKING) _state.value = TalkState.LISTENING
                    val send = if (stevoTalking && !allowInterrupt) ByteArray(n) else buf.copyOf(n) // silence keeps the stream alive
                    val b64 = Base64.encodeToString(send, Base64.NO_WRAP)
                    ws?.send("""{"realtimeInput":{"audio":{"mimeType":"audio/pcm;rate=$IN_RATE","data":"$b64"}}}""")
                    if (!stevoTalking && now - lastActivity > IDLE_MS) {
                        stop("· $name je zaspao (tišina). Dodirni lice za novi razgovor.")
                        break
                    }
                }
            } finally {
                runCatching { rec.stop(); rec.release() }
            }
        }.apply { isDaemon = true; start() }
    }

    /** 0..1 loudness of a PCM16 chunk, for the talking eyes. */
    private fun level(pcm: ByteArray): Float {
        var sum = 0.0; var n = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            sum += s.toDouble() * s; n++; i += 8 // every 4th sample is plenty
        }
        if (n == 0) return 0f
        return (sqrt(sum / n) / 6000.0).toFloat().coerceIn(0f, 1f)
    }
}

/** Body language Gemini can ask for, built from the animations the official app used. */
object Gestures {
    private fun anim(n: Int): suspend (LooiBle) -> Unit = { it.playScript(CapturedAnims.all[n - 1].keyframes, "Stevo: animacija $n") }
    val all: Map<String, suspend (LooiBle) -> Unit> = linkedMapOf(
        "nod" to anim(2),
        "shake" to anim(17),
        "look_up" to anim(6),
        "look_down" to anim(7),
        "turn_left" to anim(4),
        "turn_right" to anim(5),
        "forward" to anim(20),
        "back" to anim(10),
        "wiggle" to anim(11),
        "dance" to anim(23),
    )
}
