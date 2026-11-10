package io.github.deganegane.looilab

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class Note(val text: String, val at: Long)
data class Summary(val text: String, val at: Long)

/**
 * Stevo's memory, kept only on the phone (files under app storage):
 *  0 off · 1 notes he saves himself · 2 + a short summary of every conversation ·
 *  3 + every conversation in full, searched with the recall tool when something old comes up.
 * Notes and the latest summaries go into his instructions at the start of each conversation;
 * the full archive never does, it is only searched.
 */
class Memory(
    private val context: Context,
    private val apiKey: () -> String,
    /** Messages for the diagnostics log (what was learned, what failed). */
    private val log: (String) -> Unit = {},
) {
    companion object {
        val LEVELS = listOf("Isključeno", "Bilješke", "Bilješke + sažeci", "Sve (s pretraživanjem)")
        val KEEP = linkedMapOf(0 to "Zauvijek", 365 to "Godinu dana", 30 to "30 dana")
        /** Cheap text model for the summaries. */
        const val SUMMARY_MODEL = "gemini-3.1-flash-lite"
        private const val SUMMARIES_IN_PROMPT = 10
    }

    private val dir = File(context.filesDir, "memory").apply { mkdirs() }
    private val convDir = File(dir, "conversations").apply { mkdirs() }
    private val notesFile = File(dir, "notes.json")
    private val summariesFile = File(dir, "summaries.json")
    private val prefs = context.getSharedPreferences("memory", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS).build()

    var level: Int
        get() = prefs.getInt("level", 3)
        set(v) = prefs.edit().putInt("level", v).apply()
    /** Days to keep summaries and conversations; 0 = forever. Notes stay until deleted. */
    var keepDays: Int
        get() = prefs.getInt("keep", 0)
        set(v) { prefs.edit().putInt("keep", v).apply(); prune() }

    private val _notes = MutableStateFlow(loadNotes())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()
    private val _summaries = MutableStateFlow(loadSummaries())
    val summaries: StateFlow<List<Summary>> = _summaries.asStateFlow()
    private val _conversations = MutableStateFlow(convDir.listFiles()?.size ?: 0)
    val conversations: StateFlow<Int> = _conversations.asStateFlow()

    init { prune() }

    // ------------------------------------------------------------------ files

    private fun loadNotes(): List<Note> = runCatching {
        val a = JSONArray(notesFile.readText())
        List(a.length()) { a.getJSONObject(it).let { o -> Note(o.getString("text"), o.getLong("at")) } }
    }.getOrDefault(emptyList())

    private fun loadSummaries(): List<Summary> = runCatching {
        val a = JSONArray(summariesFile.readText())
        List(a.length()) { a.getJSONObject(it).let { o -> Summary(o.getString("text"), o.getLong("at")) } }
    }.getOrDefault(emptyList())

    private fun saveNotes(n: List<Note>) {
        _notes.value = n
        notesFile.writeText(JSONArray(n.map { JSONObject().put("text", it.text).put("at", it.at) }).toString())
    }

    private fun saveSummaries(s: List<Summary>) {
        _summaries.value = s
        summariesFile.writeText(JSONArray(s.map { JSONObject().put("text", it.text).put("at", it.at) }).toString())
    }

    private fun prune() {
        val days = keepDays
        if (days <= 0) return
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        saveSummaries(_summaries.value.filter { it.at >= cutoff })
        convDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        _conversations.value = convDir.listFiles()?.size ?: 0
    }

    // ------------------------------------------------------------------ for the instructions

    private val day = SimpleDateFormat("d.M.yyyy.", Locale.ROOT)

    /** What goes into Stevo's instructions at the start of a conversation. */
    fun promptSection(): String {
        val now = SimpleDateFormat("EEEE d.M.yyyy. HH:mm", Locale("hr")).format(Date())
        val sb = StringBuilder("Right now it is $now.\n")
        if (level == 0) return sb.append("You have no memory of earlier conversations.").toString()
        sb.append("\nMemory: you remember things between conversations. The moment you learn ")
            .append("something worth keeping about the person or their life (their NAME first of all, ")
            .append("family, pets, work, plans, likes, important dates), call remember right away, ")
            .append("in one short sentence, while you keep talking. If they ask you to forget ")
            .append("something, use forget. Do not save small talk. Use what you know naturally, ")
            .append("e.g. greet the person by name.\n")
        val n = _notes.value
        if (n.isNotEmpty()) sb.append("\nWhat you know (your notes):\n").append(n.joinToString("\n") { "- ${it.text}" }).append('\n')
        if (level >= 2) {
            val s = _summaries.value.takeLast(SUMMARIES_IN_PROMPT)
            if (s.isNotEmpty()) sb.append("\nYour last conversations:\n")
                .append(s.joinToString("\n") { "- ${day.format(Date(it.at))} ${it.text}" }).append('\n')
        }
        if (level >= 3) sb.append("\nEvery earlier conversation is archived. When the person refers to ")
            .append("something from before that you do not find above, search it with recall before ")
            .append("you answer.\n")
        return sb.toString()
    }

    // ------------------------------------------------------------------ tools

    fun remember(fact: String): String {
        if (level == 0) return "memory is switched off"
        val t = fact.trim().take(300)
        if (t.isEmpty()) return "nothing to save"
        if (_notes.value.any { it.text.equals(t, true) }) return "already known"
        saveNotes(_notes.value + Note(t, System.currentTimeMillis()))
        return "saved"
    }

    fun forget(about: String): String {
        val words = about.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
        if (words.isEmpty()) return "say what to forget"
        val keep = _notes.value.filterNot { n -> words.any { n.text.lowercase().contains(it) } }
        val removed = _notes.value.size - keep.size
        saveNotes(keep)
        return if (removed > 0) "forgot $removed note(s)" else "no matching note"
    }

    /** Keyword search through the archived conversations (level 3). */
    fun recall(query: String): String {
        if (level < 3) return "the archive is switched off"
        val words = query.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
        if (words.isEmpty()) return "nothing to search for"
        data class Hit(val score: Int, val date: String, val text: String)
        val hits = mutableListOf<Hit>()
        convDir.listFiles()?.sortedByDescending { it.name }?.forEach { f ->
            val lines = f.readLines()
            lines.forEachIndexed { i, l ->
                val score = words.count { l.lowercase().contains(it) }
                if (score > 0) {
                    val ctx = lines.subList(maxOf(0, i - 1), minOf(lines.size, i + 2)).joinToString(" / ")
                    hits += Hit(score, f.nameWithoutExtension.take(8), ctx)
                }
            }
        }
        if (hits.isEmpty()) return "nothing found about that"
        return hits.sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { it.date }).take(6)
            .joinToString("\n") { "[${it.date.substring(6, 8)}.${it.date.substring(4, 6)}.${it.date.substring(0, 4)}.] ${it.text}" }
            .take(1800)
    }

    // ------------------------------------------------------------------ end of a conversation

    /**
     * After a conversation: archive it (level 3), and in the background let a small text model
     * read it once more for facts Stevo forgot to save himself (level 1+) and write a short
     * summary (level 2+). So his memory does not depend on him calling remember.
     */
    fun endConversation(lines: List<String>, language: String) {
        val talk = lines.filter { it.startsWith("ti:") || (!it.startsWith("·") && !it.startsWith("⚠") && !it.startsWith("📷") && !it.startsWith("🧠")) }
        if (level == 0 || talk.none { it.startsWith("ti:") }) return
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        if (level >= 3) {
            File(convDir, "$stamp.txt").writeText(talk.joinToString("\n"))
            _conversations.value = convDir.listFiles()?.size ?: 0
        }
        if (apiKey().isBlank()) return
        scope.launch { digest(talk, language) }
    }

    private fun digest(talk: List<String>, language: String) {
        val lang = mapOf("hr" to "Croatian", "en" to "English", "de" to "German")[language] ?: "the conversation's language"
        val known = _notes.value.joinToString("\n") { "- ${it.text}" }.ifEmpty { "(nothing yet)" }
        val prompt = """
            A person talked with their desk robot. Lines starting with "ti:" are the person.
            Already known about the person:
            $known

            Return JSON: {"facts": [...], "summary": "..."}
            facts: NEW lasting facts about the person or their life that are not already known
            (their name, family, pets, work, plans, likes, important dates), each one short
            sentence in $lang, written about the person ("Zove se Ivan."). Empty list if none.
            summary: what the conversation was about, at most two short sentences in $lang.

            Conversation:
        """.trimIndent() + "\n" + talk.joinToString("\n").take(12_000)
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
            .toString()
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$SUMMARY_MODEL:generateContent?key=${apiKey()}")
            .post(body.toRequestBody("application/json".toMediaType())).build()
        val result = runCatching {
            http.newCall(req).execute().use { r ->
                val raw = r.body?.string().orEmpty()
                if (!r.isSuccessful) error("HTTP ${r.code}: ${raw.take(160)}")
                val text = JSONObject(raw).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                JSONObject(text.trim().removePrefix("```json").removeSuffix("```"))
            }
        }
        val o = result.getOrElse { log("⚠ pamćenje: sažetak nije uspio (${it.message})"); return }
        o.optJSONArray("facts")?.let { a ->
            for (i in 0 until a.length()) {
                val f = a.optString(i)
                if (remember(f) == "saved") log("🧠 zapamtio: $f")
            }
        }
        val summary = o.optString("summary").trim()
        if (level >= 2 && summary.isNotEmpty()) {
            saveSummaries(_summaries.value + Summary(summary, System.currentTimeMillis()))
            log("🧠 sažetak: $summary")
        }
    }

    // ------------------------------------------------------------------ settings screen

    fun deleteNote(n: Note) = saveNotes(_notes.value - n)
    fun deleteSummary(s: Summary) = saveSummaries(_summaries.value - s)

    fun forgetEverything() {
        saveNotes(emptyList()); saveSummaries(emptyList())
        convDir.listFiles()?.forEach { it.delete() }
        _conversations.value = 0
    }

    /** Everything in one JSON document, for a backup file. */
    fun export(): String = JSONObject()
        .put("notes", JSONArray(_notes.value.map { JSONObject().put("text", it.text).put("at", it.at) }))
        .put("summaries", JSONArray(_summaries.value.map { JSONObject().put("text", it.text).put("at", it.at) }))
        .put("conversations", JSONObject().apply {
            convDir.listFiles()?.forEach { put(it.nameWithoutExtension, it.readText()) }
        }).toString(1)

    fun import(json: String): Boolean = runCatching {
        val o = JSONObject(json)
        val n = o.optJSONArray("notes") ?: JSONArray()
        val s = o.optJSONArray("summaries") ?: JSONArray()
        val notes = List(n.length()) { n.getJSONObject(it).let { x -> Note(x.getString("text"), x.getLong("at")) } }
        val sums = List(s.length()) { s.getJSONObject(it).let { x -> Summary(x.getString("text"), x.getLong("at")) } }
        saveNotes((_notes.value + notes).distinctBy { it.text })
        saveSummaries((_summaries.value + sums).distinctBy { it.at }.sortedBy { it.at })
        o.optJSONObject("conversations")?.let { c ->
            c.keys().forEach { k -> File(convDir, "$k.txt").writeText(c.getString(k)) }
        }
        _conversations.value = convDir.listFiles()?.size ?: 0
    }.isSuccess
}
