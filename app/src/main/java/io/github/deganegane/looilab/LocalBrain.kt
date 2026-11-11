package io.github.deganegane.looilab

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** Where the model file stands: not there, coming down (0..1), or ready. */
data class ModelState(val ready: Boolean, val progress: Float?, val note: String)

/**
 * Stevo's brain on the phone itself: free, no quota, his thinking needs no internet.
 *   your voice → Android speech recognition → Gemma (LiteRT-LM) → Android text-to-speech.
 * The model has no tools; it steers face and body with tags at the start of its reply
 * ("[happy][nod] Naravno!"), which are acted on and never spoken.
 * The conversation is reported through [brain], so face, body language, memory and the rest
 * of the app do not care which brain is thinking.
 */
class LocalBrain(
    private val context: Context,
    private val brain: StevoBrain,
    private val face: FaceState,
    private val ble: LooiBle,
    private val memory: Memory,
    private val takePhoto: ((String?) -> Unit) -> Unit,
    private val onIdle: () -> Unit,
    private val onExpression: (Expr) -> Unit,
) {
    companion object {
        const val MODEL_NAME = "Gemma 4 E2B"
        const val MODEL_FILE = "gemma-4-E2B-it.litertlm"
        const val MODEL_URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/$MODEL_FILE?download=true"
        const val MODEL_SIZE_TEXT = "2,6 GB"
        private const val IDLE_MS = 45_000L
    }

    private val file = File(File(context.filesDir, "models").apply { mkdirs() }, MODEL_FILE)
    private val part = File(file.parentFile, "$MODEL_FILE.part")
    val modelReady: Boolean get() = file.exists() && file.length() > 100_000_000L

    private val _model = MutableStateFlow(ModelState(modelReady, null, ""))
    val model: StateFlow<ModelState> = _model.asStateFlow()

    // The engine and the conversation live on one thread of their own.
    private val llm = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + llm)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var thinking: Job? = null

    @Volatile var running = false
        private set
    @Volatile private var lastHeard = 0L
    private var errorsInRow = 0
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private val speaking = AtomicInteger(0)
    @Volatile private var generating = false

    // ------------------------------------------------------------------ Gemini Nano (built into the phone)

    /** True: Gemini Nano through ML Kit; false: Gemma through LiteRT-LM. */
    private val nanoMode: Boolean get() = brain.localModel == "nano"
    private val nano by lazy { Generation.getClient() }
    @Volatile private var nanoOk = false
    private val _nano = MutableStateFlow("")
    /** What the phone says about Gemini Nano, for the settings page. */
    val nanoState: StateFlow<String> = _nano.asStateFlow()
    private val history = ArrayList<Pair<String, String>>()

    /** The chosen on-device model can be used right now. */
    val ready: Boolean get() = if (nanoMode) nanoOk else modelReady

    fun refreshNano(download: Boolean = false) {
        io.launch {
            runCatching {
                when (nano.checkStatus()) {
                    FeatureStatus.AVAILABLE -> { nanoOk = true; _nano.value = "✓ Gemini Nano je spreman na ovom mobitelu." }
                    FeatureStatus.DOWNLOADING -> { nanoOk = false; _nano.value = "Mobitel upravo preuzima Gemini Nano, probaj malo kasnije." }
                    FeatureStatus.DOWNLOADABLE -> {
                        nanoOk = false
                        if (!download) _nano.value = "Gemini Nano se može preuzeti na ovaj mobitel."
                        else nano.download().collect { st ->
                            when (st) {
                                is DownloadStatus.DownloadProgress -> _nano.value = "Preuzimam… %.0f MB".format(st.totalBytesDownloaded / 1e6)
                                is DownloadStatus.DownloadFailed -> _nano.value = "Preuzimanje nije uspjelo: ${st.e.message}"
                                DownloadStatus.DownloadCompleted -> { nanoOk = true; _nano.value = "✓ Gemini Nano je spreman na ovom mobitelu." }
                                else -> _nano.value = "Preuzimam Gemini Nano…"
                            }
                        }
                    }
                    else -> { nanoOk = false; _nano.value = "Gemini Nano nije dostupan na ovom mobitelu (ili AICore još nije spreman)." }
                }
            }.onFailure { nanoOk = false; _nano.value = "Gemini Nano: ${it.message ?: it.javaClass.simpleName}" }
        }
    }

    init { refreshNano() }

    private fun nanoPrompt(text: String): String = buildString {
        append(persona())
        if (history.isNotEmpty()) {
            append("\n\nThe conversation so far:\n")
            history.takeLast(6).forEach { (u, a) -> append("Person: ").append(u).append('\n').append("You: ").append(a).append('\n') }
        }
        append("\nPerson: ").append(text).append("\nYou:")
    }

    // ------------------------------------------------------------------ the model file

    private var download: Job? = null

    fun downloadModel() {
        if (download?.isActive == true || modelReady) return
        download = io.launch {
            val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
            try {
                var have = if (part.exists()) part.length() else 0L
                val req = Request.Builder().url(MODEL_URL).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
                http.newCall(req).execute().use { r ->
                    if (r.code == 401 || r.code == 403) error("Hugging Face traži prijavu za ovaj model. Preuzmi datoteku ručno pa je učitaj gumbom ispod.")
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    if (r.code != 206) have = 0
                    val total = have + (r.body?.contentLength() ?: -1L)
                    RandomAccessFile(part, "rw").use { out ->
                        out.seek(have)
                        val buf = ByteArray(1 shl 16)
                        val src = r.body!!.byteStream()
                        var lastShown = 0L
                        while (isActive) {
                            val n = src.read(buf); if (n < 0) break
                            out.write(buf, 0, n); have += n
                            if (have - lastShown > 8_000_000) {
                                lastShown = have
                                _model.value = ModelState(false, if (total > 0) have / total.toFloat() else 0f,
                                    "%.0f MB".format(have / 1e6) + if (total > 0) " / %.0f MB".format(total / 1e6) else "")
                            }
                        }
                        if (total > 0 && have < total) error("preuzimanje prekinuto, nastavi ga ponovno")
                    }
                }
                part.renameTo(file)
                _model.value = ModelState(true, null, "Model je spreman.")
            } catch (t: Throwable) {
                _model.value = ModelState(false, null, "Nije uspjelo: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    fun cancelDownload() { download?.cancel(); _model.value = ModelState(modelReady, null, "Preuzimanje zaustavljeno; nastavlja se odakle je stalo.") }

    /** A model file picked by hand (downloaded in a browser, or a fine-tuned one). */
    fun importModel(uri: Uri) {
        io.launch {
            _model.value = ModelState(false, 0f, "Kopiram datoteku…")
            runCatching {
                context.contentResolver.openInputStream(uri)!!.use { src -> part.outputStream().use { src.copyTo(it, 1 shl 16) } }
                release(); file.delete(); part.renameTo(file)
            }.onSuccess { _model.value = ModelState(modelReady, null, if (modelReady) "Model je učitan." else "Datoteka ne izgleda kao model.") }
                .onFailure { _model.value = ModelState(modelReady, null, "Nije uspjelo: ${it.message}") }
        }
    }

    fun deleteModel() {
        release(); file.delete(); part.delete()
        _model.value = ModelState(false, null, "")
    }

    // ------------------------------------------------------------------ conversation

    private fun languageTag() = when (brain.language) { "hr" -> "hr-HR"; "en" -> "en-US"; "de" -> "de-DE"; else -> Locale.getDefault().toLanguageTag() }
    private fun languageName() = when (brain.language) { "hr" -> "Croatian"; "en" -> "English"; "de" -> "German"; else -> "the language the person speaks" }

    private fun persona(): String = """
        You are ${brain.name}, a small desk robot with two glowing orange eyes. You talk by voice with your owner.
        Personality: ${brain.personality.ifBlank { StevoBrain.DEFAULT_PERSONALITY }}
        Always answer in ${languageName()}. One or two short spoken sentences, like a friend. No lists, no emojis, no markdown.
        ${if (brain.swearing) "Rude, cheeky language is fine with your owner, never hateful." else "Keep your language clean."}

        Begin EVERY answer with one feeling tag in square brackets, one of:
        ${Expr.entries.joinToString(" ") { "[${it.name.lowercase()}]" }}
        You may add one body tag after it: ${Gestures.all.keys.joinToString(" ") { "[$it]" }}
        Other tags you can use when it fits: [photo] takes a photo, [light_on] and [light_off] switch your lamp,
        [remember: one short fact] saves something important about the person (their name first of all).
        Tags are acted on and never spoken. Example: [happy][nod] Naravno, baš sam dobro!
    """.trimIndent() + "\n\n" + memory.promptSection(tools = false)

    fun start() {
        if (running) return
        if (!ready) { brain.log("⚠ Model na mobitelu nije spreman (Postavke → Mozak)."); face.say("Model na mobitelu nije spreman"); return }
        running = true; errorsInRow = 0; lastHeard = System.currentTimeMillis()
        history.clear()
        brain.beginExternal()
        brain.log("· ${brain.name} razmišlja na mobitelu (${if (nanoMode) "Gemini Nano" else MODEL_NAME})")
        if (tts == null) tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) = spoken()
                @Deprecated("old API") override fun onError(id: String?) = spoken()
            })
        }
        if (nanoMode) { listen(); return }
        scope.launch {
            if (engine == null) face.say("Učitavam model…")
            if (!ensureEngine()) { stop("⚠ Model se nije učitao. Probaj ga ponovno preuzeti."); return@launch }
            runCatching { conversation?.close() }
            conversation = runCatching {
                engine!!.createConversation(ConversationConfig(
                    systemInstruction = Contents.of(persona()),
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8),
                ))
            }.getOrElse { stop("⚠ Model: ${it.message}"); return@launch }
            listen()
        }
    }

    private fun ensureEngine(): Boolean {
        if (engine != null) return true
        for (gpu in listOf(true, false)) {
            try {
                val e = Engine(EngineConfig(modelPath = file.absolutePath, backend = if (gpu) Backend.GPU() else Backend.CPU(), cacheDir = context.cacheDir.path))
                e.initialize()
                engine = e
                brain.log("· model učitan (${if (gpu) "GPU" else "CPU"})")
                return true
            } catch (t: Throwable) {
                brain.log("· ${if (gpu) "GPU" else "CPU"} ne ide: ${t.message?.take(80)}")
            }
        }
        return false
    }

    /** Frees the model's memory. */
    fun release() {
        scope.launch {
            runCatching { conversation?.close() }; conversation = null
            runCatching { engine?.close() }; engine = null
        }
    }

    fun stop(reason: String) {
        if (!running) return
        running = false
        main.post { runCatching { recognizer?.cancel(); recognizer?.destroy() }; recognizer = null }
        runCatching { tts?.stop() }; speaking.set(0); generating = false
        val job = thinking
        if (nanoMode) job?.cancel()
        scope.launch {
            // Stop a reply that is still being written, then drop the conversation; the engine stays loaded.
            var tries = 0
            while (job?.isActive == true && tries++ < 30) { runCatching { conversation?.cancelProcess() }; delay(100) }
            runCatching { conversation?.close() }; conversation = null
        }
        brain.stop(reason)
    }

    // ---- listening

    private fun listen() = main.post {
        if (!running) return@post
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { stop("⚠ Na mobitelu nema prepoznavanja govora."); return@post }
        brain.publish(TalkState.LISTENING)
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it; it.setRecognitionListener(listener) }
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() { lastHeard = System.currentTimeMillis() }
        override fun onRmsChanged(rmsdB: Float) { face.mic = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { face.mic = 0f }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            errorsInRow = 0
            if (text.isEmpty()) relisten() else { lastHeard = System.currentTimeMillis(); answer(text) }
        }

        override fun onError(error: Int) {
            if (!running) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> relisten()
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> main.postDelayed({ relisten() }, 500)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> stop("⚠ Nema dozvole za mikrofon.")
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                    stop("⚠ Prepoznavanje govora treba internet (ili preuzmi hrvatski za govor bez mreže u postavkama mobitela).")
                else -> if (++errorsInRow > 5) stop("⚠ Prepoznavanje govora ne radi (greška $error).") else main.postDelayed({ relisten() }, 400)
            }
        }
    }

    private fun relisten() {
        if (!running) return
        if (System.currentTimeMillis() - lastHeard > IDLE_MS) {
            stop("· ${brain.name} je zaspao (tišina). Dodirni lice za novi razgovor."); onIdle()
        } else listen()
    }

    // ---- thinking and speaking

    private val tagRe = Regex("\\[([^\\]]{1,120})]")

    private fun answer(text: String) {
        brain.log("ti: $text")
        brain.publish(TalkState.CONNECTING) // the little dot: he is thinking
        face.flash(Expr.THINKING); onExpression(Expr.THINKING)
        generating = true
        thinking = scope.launch {
            val all = StringBuilder(); var consumed = 0; val said = StringBuilder()
            fun flush(final: Boolean) {
                val pending = all.substring(consumed)
                var cut = if (final) pending.length - 1 else pending.indexOfLast { it == '.' || it == '!' || it == '?' || it == '\n' }
                // Never cut inside a tag that is still open.
                if (!final && cut >= 0 && pending.lastIndexOf('[', cut) > pending.lastIndexOf(']', cut)) cut = -1
                if (cut < 0) return
                val piece = pending.substring(0, cut + 1); consumed += cut + 1
                tagRe.findAll(piece).forEach { act(it.groupValues[1].trim()) }
                val speech = piece.replace(tagRe, " ").replace(Regex("[*_#`]"), "").replace(Regex("\\s+"), " ").trim()
                if (speech.isNotEmpty()) { said.append(speech).append(' '); say(speech) }
            }
            runCatching {
                val chunks: Flow<String> =
                    if (nanoMode) nano.generateContentStream(nanoPrompt(text)).map { it.candidates.firstOrNull()?.text ?: "" }
                    else conversation!!.sendMessageAsync(text).map { it.toString() }
                chunks.collect { chunk ->
                    // Some versions send the whole text so far, others only what is new.
                    if (all.isNotEmpty() && chunk.startsWith(all)) { all.setLength(0); all.append(chunk) } else all.append(chunk)
                    if (running) flush(false)
                }
            }.onFailure { if (running) brain.log("⚠ Model: ${it.message?.take(100)}") }
            if (running) flush(true)
            generating = false
            if (said.isNotBlank()) { brain.log("${brain.name}: ${said.toString().trim()}"); history += text to said.toString().trim() }
            if (running && speaking.get() == 0) listen()
        }
    }

    private fun act(tag: String) {
        val t = tag.lowercase()
        val expr = Expr.entries.firstOrNull { it.name.lowercase() == t }
        when {
            expr != null -> { face.set(expr); onExpression(expr) }
            t in Gestures.all -> Gestures.perform(ble, t)
            t == "photo" -> { face.flash(Expr.SURPRISED); takePhoto { f -> if (f != null) { brain.log("📷 $f"); face.say("Slika spremljena") } } }
            t == "light_on" -> ble.launch { setLight(true) }
            t == "light_off" -> ble.launch { setLight(false) }
            t.startsWith("remember") -> tag.substringAfter(':', "").trim().takeIf { it.length > 2 }?.let {
                if (memory.remember(it) == "saved") brain.log("🧠 $it")
            }
        }
    }

    private fun say(text: String) {
        val t = tts
        if (t == null || !ttsReady) return
        if (speaking.getAndIncrement() == 0) {
            brain.publish(TalkState.SPEAKING)
            runCatching { t.setLanguage(Locale.forLanguageTag(languageTag())) }
            t.setSpeechRate(brain.speed); t.setPitch(brain.pitch)
            // No sound level from the phone's voice, so the eyes pulse to a made-up rhythm.
            io.launch { while (speaking.get() > 0 && running) { face.talk = 0.25f + Random.nextFloat() * 0.5f; delay(90) }; face.talk = 0f }
        }
        t.speak(text, TextToSpeech.QUEUE_ADD, null, "s${System.nanoTime()}")
    }

    private fun spoken() {
        if (speaking.decrementAndGet() <= 0) {
            speaking.set(0)
            lastHeard = System.currentTimeMillis() // the silence clock starts when he stops talking
            if (running && !generating) listen()
        }
    }
}
