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
    private val eyes: CameraEyes,
    private val memory: Memory,
    /** Takes a photo; the callback gets the file name or null. */
    private val takePhoto: ((String?) -> Unit) -> Unit,
    /** The conversation ended because nobody spoke: time to sleep. */
    private val onIdle: () -> Unit = {},
    /** A new expression: the body may follow it (see BodyLanguage). */
    private val onExpression: (Expr) -> Unit = {},
) {
    companion object {
        const val MODEL = "models/gemini-3.8-live"
        /** Thinks before it answers: smarter, a little slower. */
        const val MODEL_SMART = "models/gemini-3.8-live-extended-thinking"
        const val IN_RATE = 16_000
        const val OUT_RATE = 24_000
        private const val URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        /** No speech either way for this long: the session closes (it costs money while open). */
        const val IDLE_MS = 40_000L

        /** Conversation language: a fixed one (default) or "auto". */
        val LANGUAGES = linkedMapOf("hr" to "Hrvatski", "en" to "English", "de" to "Deutsch", "auto" to "Automatski")

        private fun languageRule(code: String): String = when (code) {
            "auto" -> "Language: answer in the language the person speaks to you. Only switch " +
                "language when they clearly switch for a whole sentence, never because of a single " +
                "foreign word or an accent. When unsure, stay in the language you used last."
            else -> {
                val lang = mapOf("hr" to "Croatian", "en" to "English", "de" to "German")[code] ?: "Croatian"
                "Language: ALWAYS speak $lang, in every reply, even if you think you heard another " +
                    "language. The person speaks $lang with a regional accent; similar languages " +
                    "(Serbian, Bosnian, Slovenian, Russian, Polish, Czech) are mishearings of $lang, " +
                    "never answer in them. Switch to another language only when the person explicitly " +
                    "asks you to (e.g. 'let us speak English'), and stay in it until asked to switch back."
            }
        }

        val VOICES = listOf("Puck", "Charon", "Fenrir", "Orus", "Kore", "Aoede")

        private fun cameraRule(vision: Boolean) = if (vision)
            "You can see through the camera in your face: you receive a picture every second or two. " +
                "Look at the person and comment on what you see when it fits. When you see something " +
                "beautiful, funny or special you may take a photo with take_photo on your own (at most " +
                "once every few minutes), and always when the person asks for a photo."
        else
            "You cannot see right now, but you have a camera: when the person asks for a photo, " +
                "take it with take_photo."

        const val DEFAULT_PERSONALITY = "Playful, curious, warm, a bit cheeky and with dry humour, " +
            "never mean. You get excited about small things and you are proud of being a robot."

        private fun swearRule(on: Boolean) = if (on)
            "Crude language is fine with your owner: you may swear and use rude, cheeky humour the way " +
                "close friends do, especially when they swear or ask you to. Never slurs, never hateful, " +
                "never aimed to really hurt someone."
        else
            "Keep your language clean unless the person explicitly asks you to swear; then you may, playfully."

        fun persona(name: String, personality: String, language: String, vision: Boolean, swearing: Boolean, memory: String) = """
            You are $name, a small desk robot. Your body is a little wheeled base that can drive,
            turn and tilt your head; your face is the phone screen on top of it, two glowing
            orange Nixie-tube eyes. You live on your owner's desk and you talk with them by voice.

            Personality: ${personality.ifBlank { DEFAULT_PERSONALITY }}

            ${languageRule(language)}

            ${cameraRule(vision)}

            ${swearRule(swearing)}

            Talk like a friend, not like an assistant: short, spoken sentences, usually one to
            three. No lists, no markdown, no emojis. Ask a question back now and then.

            Your body is part of how you talk, and you are a lively little robot: move a lot,
            in almost every reply, the way an excited puppy would. Use the tools often:
            set_expression to show what you feel, and change it as the conversation changes:
            happy or laugh at jokes, wink when you tease, thinking while you consider something,
            sad or worried at bad news, excited at great news, confused when you do not get it,
            shy at compliments, bored when teased about being boring, love when they are sweet,
            sleepy at night, then back to neutral. Use move to nod, shake your head, look up or
            down, turn, drive a little, wiggle or dance while you speak. You also have a lamp
            (set_light) to see better in the dark or for fun.
            Your floor sensors stop you at the edge of the table: if a move is refused because
            you are at the edge, say so in your own words (e.g. that you would fall off).
            Never say the tool names out loud and never describe that you are calling them.
        """.trimIndent() + "\n\n" + memory
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
    /** Free text: how Stevo behaves and talks (style, tone, quirks). */
    var personality: String
        get() = prefs.getString("personality", DEFAULT_PERSONALITY) ?: DEFAULT_PERSONALITY
        set(v) = prefs.edit().putString("personality", v).apply()
    var vision: Boolean
        get() = prefs.getBoolean("vision", false)
        set(v) = prefs.edit().putBoolean("vision", v).apply()
    var tracking: Boolean
        get() = prefs.getBoolean("tracking", true)
        set(v) = prefs.edit().putBoolean("tracking", v).apply()
    var mirror: Boolean
        get() = prefs.getBoolean("mirror", false)
        set(v) = prefs.edit().putBoolean("mirror", v).apply()
    /** The base's lamp: "off", "on" or "auto" (on in the dark, seen through the camera). */
    var lightMode: String
        get() = prefs.getString("light", "auto") ?: "auto"
        set(v) = prefs.edit().putString("light", v).apply()
    /** Send audio (and pictures) to Gemini only around your voice, not the silence in between. */
    var saveTokens: Boolean
        get() = prefs.getBoolean("saver", true)
        set(v) = prefs.edit().putBoolean("saver", v).apply()
    @Volatile private var seeUntil = 0L

    /** The thinking model: better answers, a bit more delay. */
    var smart: Boolean
        get() = prefs.getBoolean("smart", false)
        set(v) = prefs.edit().putBoolean("smart", v).apply()
    /** Google Search during conversations (weather, news, anything current). */
    var search: Boolean
        get() = prefs.getBoolean("search", true)
        set(v) = prefs.edit().putBoolean("search", v).apply()
    /** How much he moves on his own: 0 calm, 1 normal, 2 lively. */
    var liveliness: Int
        get() = prefs.getInt("lively", 2)
        set(v) = prefs.edit().putInt("lively", v).apply()

    /** On his own: 0 stays still, 1 moves on the spot, 2 also wanders over the desk. */
    var autonomy: Int
        get() = prefs.getInt("autonomy", 2)
        set(v) = prefs.edit().putInt("autonomy", v).apply()

    /** He may swear and be crude when it fits (only if the owner wants that). */
    var swearing: Boolean
        get() = prefs.getBoolean("swearing", false)
        set(v) = prefs.edit().putBoolean("swearing", v).apply()
    var keepScreenOn: Boolean
        get() = prefs.getBoolean("screen", true)
        set(v) = prefs.edit().putBoolean("screen", v).apply()
    var language: String
        get() = prefs.getString("lang", "hr") ?: "hr"
        set(v) = prefs.edit().putString("lang", v).apply()
    var voice: String
        get() = prefs.getString("voice", "Puck") ?: "Puck"
        set(v) = prefs.edit().putString("voice", v).apply()
    /** Voice speed and pitch, applied on the phone to Gemini's audio (1.0 = as Gemini speaks). */
    var speed: Float
        get() = prefs.getFloat("speed", 1.0f)
        set(v) { prefs.edit().putFloat("speed", v).apply(); applyVoiceParams() }
    var pitch: Float
        get() = prefs.getFloat("pitch", 1.0f)
        set(v) { prefs.edit().putFloat("pitch", v).apply(); applyVoiceParams() }

    private fun applyVoiceParams() {
        val t = track ?: return
        runCatching {
            t.playbackParams = android.media.PlaybackParams().setSpeed(speed).setPitch(pitch)
        }
    }

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

    private val session = mutableListOf<String>()
    private fun line(s: String) {
        synchronized(session) { session += s }
        _transcript.update { (it + s).takeLast(30) }
    }

    /** Tells Stevo (only while talking) about something that happened to his body. */
    fun notify(event: String) {
        if (!socketReady || _state.value == TalkState.OFF) return
        ws?.send(JSONObject().put("realtimeInput", JSONObject().put("text", "[$event]")).toString())
    }

    fun toggle() { if (_state.value == TalkState.OFF) start() else stop("") }

    /** Groq: free plan with big open models and Whisper. */
    var groqKey: String
        get() = prefs.getString("groqKey", "") ?: ""
        set(v) = prefs.edit().putString("groqKey", v.trim()).apply()
    var groqModel: String
        get() = prefs.getString("groqModel", "openai/gpt-oss-120b")?.takeIf { it.isNotBlank() } ?: "openai/gpt-oss-120b"
        set(v) = prefs.edit().putString("groqModel", v.trim()).apply()

    /** "gemini", "groq", "local" (a model on the phone) or "auto" (Gemini, then Groq, then the phone). */
    var brainMode: String
        get() = prefs.getString("brain", "auto") ?: "auto"
        set(v) = prefs.edit().putString("brain", v).apply()

    /** Which model thinks on the phone: "gemma" (downloaded) or "nano" (built into the phone). */
    var localModel: String
        get() = prefs.getString("localModel", "gemma") ?: "gemma"
        set(v) = prefs.edit().putString("localModel", v).apply()

    /** Gemini could not be reached (quota, no internet, bad key) before anything was said. */
    var onFailed: (() -> Unit)? = null
    @Volatile private var heardUser = false

    // ---- The on-device brain runs its own conversation but keeps the books here, so the
    // ---- face, the body and the memory see one conversation whoever is thinking.
    fun beginExternal() {
        baseBefore = face.base
        face.set(Expr.CURIOUS)
        heardUser = true
        _state.value = TalkState.CONNECTING
    }
    fun publish(s: TalkState) { _state.value = s }
    fun log(s: String) = line(s)

    fun start() {
        if (_state.value != TalkState.OFF) return
        if (apiKey.isBlank()) { line("⚠ Upiši Gemini API ključ u Postavkama."); onFailed?.invoke(); return }
        heardUser = false
        _state.value = TalkState.CONNECTING
        baseBefore = face.base
        face.set(Expr.CURIOUS)
        running = true
        resumeHandle = null; resumes = 0; socketReady = false
        lastActivity = System.currentTimeMillis()
        val req = Request.Builder().url("$URL?key=$apiKey").build()
        ws = http.newWebSocket(req, listener)
    }

    fun stop(reason: String) {
        if (!running && _state.value == TalkState.OFF) return
        running = false
        eyes.onFrame = null
        ws?.close(1000, "bye"); ws = null
        recordThread?.interrupt(); recordThread = null
        playQueue.clear(); playQueue.offer(ByteArray(0))
        playThread = null
        runCatching { track?.pause(); track?.flush(); track?.release() }; track = null
        audioMode(false)
        flushTranscript()
        val failed = reason.startsWith("⚠") && !heardUser
        if (reason.isNotEmpty()) line(if ("quota" in reason) "⚠ Potrošena je Gemini kvota. Probaj kasnije." else reason)
        val lines = synchronized(session) { session.toList().also { session.clear() } }
        runCatching { memory.endConversation(lines, language) }
        face.talk = 0f; face.mic = 0f
        face.set(baseBefore)
        _state.value = TalkState.OFF
        if (failed) { face.flash(Expr.SAD); onFailed?.invoke() }
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
                    Expr.entries.joinToString(", ") { it.name.lowercase() },
                    Expr.entries.map { it.name.lowercase() })))
                .put("required", JSONArray(listOf("expression"))))
        val move = JSONObject()
            .put("name", "move")
            .put("description", "Move your robot body: a short gesture.")
            .put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject().put("action", enumParam(
                    "nod = yes, shake = no, look_up, look_down, turn_left, turn_right, " +
                        "forward, back = a little drive, wiggle = excited, dance = celebrate, " +
                        "spin = a full turn, bow = thanks or greeting, stretch, look_around",
                    Gestures.all.keys.toList())))
                .put("required", JSONArray(listOf("action"))))
        val photo = JSONObject()
            .put("name", "take_photo")
            .put("description", "Take a photo with your face camera and save it to the gallery.")
            .put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject().put("reason", JSONObject().put("type", "STRING")
                    .put("description", "Why: what is in the photo, in a few words"))))
        fun textTool(name: String, desc: String, param: String, pdesc: String) = JSONObject()
            .put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject().put(param, JSONObject().put("type", "STRING").put("description", pdesc)))
                .put("required", JSONArray(listOf(param))))
        val light = JSONObject()
            .put("name", "set_light")
            .put("description", "Switch the lamp on your base on or off.")
            .put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject().put("on", JSONObject().put("type", "BOOLEAN")))
                .put("required", JSONArray(listOf("on"))))
        val tools = JSONArray().put(expr).put(move).put(photo).put(light)
        if (memory.level >= 1) {
            tools.put(textTool("remember", "Save something worth remembering about the person, for future conversations.",
                "fact", "One short sentence, e.g. 'Has a cat called Mici.'"))
            tools.put(textTool("forget", "Delete saved notes about something, when the person asks you to forget it.",
                "about", "What to forget, in a few words"))
        }
        if (memory.level >= 3) tools.put(textTool("recall", "Search all earlier conversations for something.",
            "query", "Key words to look for"))
        // The thinking model reasons in the background and wants its tools declared non-blocking.
        if (smart) for (i in 0 until tools.length()) tools.getJSONObject(i).put("behavior", "NON_BLOCKING")
        val toolList = JSONArray().put(JSONObject().put("functionDeclarations", tools))
        if (search) toolList.put(JSONObject().put("googleSearch", JSONObject()))
        val setup = JSONObject()
            .put("model", if (smart) MODEL_SMART else MODEL)
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray(listOf("AUDIO")))
                .apply { if (smart) put("thinkingConfig", JSONObject().put("thinkingLevel", "LOW")) }
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                    .put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", persona(name, personality, language, vision, swearing, memory.promptSection())))))
            .put("tools", toolList)
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("realtimeInputConfig", JSONObject().put("automaticActivityDetection", JSONObject()
                .put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                .put("silenceDurationMs", 900)))
            .put("sessionResumption", JSONObject().apply { resumeHandle?.let { put("handle", it) } })
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
        return JSONObject().put("setup", setup).toString()
    }

    // ------------------------------------------------------------------ socket

    /**
     * Gemini ends a live connection after some minutes (it says so with goAway first). The
     * session itself lives on: we reconnect with the last resumption handle and carry on, so a
     * long chat is never cut off.
     */
    @Volatile private var resumeHandle: String? = null
    /** False between opening a socket and its setupComplete: nothing may be sent then. */
    @Volatile private var socketReady = false
    private var resumes = 0

    private fun resume(why: String) {
        val h = resumeHandle
        if (!running || h == null || resumes >= 20) { stop(why); return }
        resumes++
        socketReady = false
        val old = ws
        ws = http.newWebSocket(Request.Builder().url("$URL?key=$apiKey").build(), listener)
        old?.close(1000, "resume")
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(setupMessage())
        }

        override fun onMessage(webSocket: WebSocket, text: String) { if (webSocket == ws) handle(text) }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { if (webSocket == ws) handle(bytes.utf8()) }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            if (webSocket != ws || !running) return
            if (code == 1000) stop("· razgovor završen")
            else resume("⚠ Gemini je zatvorio vezu ($code): $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket != ws || !running) return
            if (_state.value == TalkState.CONNECTING) stop("⚠ veza nije uspjela: ${response?.code ?: ""} ${t.message ?: t.javaClass.simpleName}")
            else resume("⚠ veza je pukla: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun handle(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        msg.optJSONObject("sessionResumptionUpdate")?.let { u ->
            if (u.optBoolean("resumable", true)) u.optString("newHandle").takeIf { it.isNotEmpty() }?.let { resumeHandle = it }
        }
        when {
            msg.has("setupComplete") && _state.value != TalkState.CONNECTING -> socketReady = true // resumed, audio keeps running
            msg.has("setupComplete") -> {
                socketReady = true
                line("· $name sluša" + if (vision) " i gleda" else "")
                if (vision) eyes.onFrame = { jpg ->
                    val b64 = Base64.encodeToString(jpg, Base64.NO_WRAP)
                    // With the saver, pictures only go up around the moments you speak.
                    if (socketReady && (!saveTokens || System.currentTimeMillis() < seeUntil)) ws?.send("""{"realtimeInput":{"video":{"mimeType":"image/jpeg","data":"$b64"}}}""")
                }
                startAudio()
                _state.value = TalkState.LISTENING
            }
            msg.has("serverContent") -> serverContent(msg.getJSONObject("serverContent"))
            msg.has("toolCall") -> toolCall(msg.getJSONObject("toolCall"))
            msg.has("goAway") -> resume("· Gemini je zatvorio sesiju (vremensko ograničenje)")
        }
    }

    private fun serverContent(c: JSONObject) {
        c.optJSONObject("inputTranscription")?.optString("text")?.let {
            if (it.isNotEmpty()) { pendingUser.append(it); heardUser = true; lastActivity = System.currentTimeMillis() }
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
                    if (e != null) { face.set(e); onExpression(e); "ok" } else "unknown expression"
                }
                "move" -> Gestures.perform(ble, args.optString("action"))
                "take_photo" -> {
                    face.flash(Expr.SURPRISED)
                    takePhoto { file ->
                        if (file != null) { line("📷 $file"); face.flash(Expr.HAPPY) }
                        else line("⚠ fotografija nije uspjela (je li kamera uključena?)")
                    }
                    "photo taken"
                }
                "set_light" -> {
                    if (ble.state.value != ConnState.READY) "the robot base is not connected"
                    else { val on = args.optBoolean("on"); ble.launch { setLight(on) }; if (on) "lamp on" else "lamp off" }
                }
                "remember" -> memory.remember(args.optString("fact")).also { if (it == "saved") line("🧠 ${args.optString("fact")}") }
                "forget" -> memory.forget(args.optString("about"))
                "recall" -> memory.recall(args.optString("query"))
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
        track = t
        applyVoiceParams()
        t.play()

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
            // Half duplex needs no echo canceller, and the recognition source keeps the voice cleaner
            // (communication processing smears consonants, which hurts Croatian a lot).
            val source = if (allowInterrupt) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
            val rec = AudioRecord(source, IN_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(inMin, 6400) * 2)
            if (allowInterrupt) {
                if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true
                if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.enabled = true
            }
            rec.startRecording()
            val buf = ByteArray(3200) // 100 ms
            // Saver: Gemini bills audio by the second, silence included. So only what is around
            // your voice goes up: a little before it (so the first word is whole), the speech,
            // and a second after it (so Gemini hears that you stopped). Nothing in between.
            val saver = saveTokens
            val before = ArrayDeque<ByteArray>()
            var noise = 0.01f; var loud = 0; var voiceUntil = 0L; var streaming = false
            fun up(pcm: ByteArray) {
                val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
                if (socketReady) ws?.send("""{"realtimeInput":{"audio":{"mimeType":"audio/pcm;rate=$IN_RATE","data":"$b64"}}}""")
            }
            try {
                while (running && !Thread.currentThread().isInterrupted) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val now = System.currentTimeMillis()
                    val stevoTalking = now < speakingUntil || playQueue.isNotEmpty()
                    val heard = level(buf.copyOf(n))
                    val mic = if (stevoTalking) 0f else heard
                    face.mic = mic
                    if (!stevoTalking && _state.value == TalkState.SPEAKING) _state.value = TalkState.LISTENING
                    val muted = stevoTalking && !allowInterrupt
                    // Is somebody speaking? Louder than the room for two frames in a row.
                    val threshold = maxOf(0.018f, noise * 2.5f + 0.008f)
                    if (!muted && heard > threshold) { if (++loud >= 2) voiceUntil = now + 1_300 }
                    else { loud = 0; if (!muted && now > voiceUntil) noise = noise * 0.95f + heard * 0.05f }
                    val voice = now < voiceUntil
                    if (voice) { lastActivity = now; seeUntil = now + 4_000 }
                    if (!saver) {
                        up(if (muted) ByteArray(n) else buf.copyOf(n))
                    } else if (voice && !muted) {
                        if (!streaming) { streaming = true; for (p in before) up(p); before.clear() }
                        up(buf.copyOf(n))
                    } else {
                        if (streaming) {
                            streaming = false
                            if (socketReady) ws?.send("""{"realtimeInput":{"audioStreamEnd":true}}""")
                        }
                        if (!muted) { before.addLast(buf.copyOf(n)); if (before.size > 4) before.removeFirst() } else before.clear()
                    }
                    if (!stevoTalking && now - lastActivity > IDLE_MS) {
                        stop("· $name je zaspao (tišina). Dodirni lice za novi razgovor.")
                        onIdle()
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
    /** Does the gesture if it is safe; the answer tells the model what happened. */
    fun perform(ble: LooiBle, action: String): String {
        val g = all[action]
        return if (g == null) "unknown action"
        else if (ble.state.value != ConnState.READY) "the robot base is not connected, only the face can move"
        else if (action in setOf("forward", "back", "dance") && ble.atEdge)
            "refused: you are standing at the edge of the table, the floor sensors see the drop. Tell the person you would fall off."
        else { ble.launch { g(this) }; "ok" }
    }

    private fun move(label: String, k: (LooiBle) -> ByteArray): suspend (LooiBle) -> Unit = { it.playScript(k(it), "Stevo: $label") }
    private fun anim(n: Int): suspend (LooiBle) -> Unit = { it.playScript(CapturedAnims.all[n - 1].keyframes, "Stevo: animacija $n") }
    val all: Map<String, suspend (LooiBle) -> Unit> = linkedMapOf(
        "nod" to move("kimanje", Moves::nod),
        "shake" to move("odmahivanje", Moves::shake),
        "look_up" to move("pogled gore", Moves::lookUp),
        "look_down" to move("pogled dolje", Moves::lookDown),
        "turn_left" to anim(4),
        "turn_right" to anim(5),
        "forward" to anim(15),
        "back" to anim(3),
        "wiggle" to move("migoljenje", Moves::wiggle),
        "dance" to anim(23),
        "spin" to move("vrtnja", Moves::spin),
        "bow" to move("naklon", Moves::bow),
        "stretch" to move("protezanje", Moves::stretch),
        "look_around" to move("gleda okolo") { Moves.lookAround(it, 1) },
    )
}
