package io.github.deganegane.looilab

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Looking things up on the internet for the brains that cannot do it themselves
 * (EVO-X2, Groq, the phone). SearXNG on the EVO-X2 first (own, no limits), Tavily as the fallback
 * (free key, 1,000 searches a month).
 */
object Search {
    /** [text] is null when nothing was found; [source] says where it came from, or why it failed. */
    data class Found(val text: String?, val source: String)

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    }

    /** Blocks; call from a background thread. */
    fun run(brain: StevoBrain, query: String): Found {
        val why = ArrayList<String>()
        if (brain.searxUrl.isNotBlank()) {
            runCatching { searx(brain, query) }
                .onSuccess { if (it != null) return Found(it, "SearXNG") else why += "SearXNG: ništa" }
                .onFailure { why += "SearXNG: ${it.message?.take(60)}" }
        }
        if (brain.tavilyKey.isNotBlank()) {
            runCatching { tavily(brain, query) }
                .onSuccess { if (it != null) return Found(it, "Tavily") else why += "Tavily: ništa" }
                .onFailure { why += "Tavily: ${it.message?.take(60)}" }
        }
        return Found(null, why.joinToString("; ").ifEmpty { "nema postavljene pretrage" })
    }

    /** "100.70.141.124" → "http://100.70.141.124:8080". */
    fun searxBase(url: String): String {
        var u = url.trim().trimEnd('/')
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        if (!u.substringAfter("://").contains(':')) u = "$u:8080"
        return u
    }

    private fun searx(brain: StevoBrain, query: String): String? {
        val url = "${searxBase(brain.searxUrl)}/search".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("format", "json")
            .addQueryParameter("safesearch", "0")
            .apply { if (brain.language != "auto") addQueryParameter("language", brain.language) }
            .build()
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.code == 403) error("JSON nije uključen u settings.yml")
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val o = JSONObject(r.body?.string().orEmpty())
            val out = StringBuilder()
            o.optJSONArray("answers")?.let { a -> for (i in 0 until a.length()) out.append("Answer: ").append(a.opt(i).toString().take(400)).append('\n') }
            o.optJSONArray("infoboxes")?.let { a ->
                for (i in 0 until minOf(a.length(), 1)) a.optJSONObject(i)?.let { b ->
                    out.append(b.optString("infobox")).append(": ").append(b.optString("content").take(500)).append('\n')
                }
            }
            val res = o.optJSONArray("results")
            if (res != null) for (i in 0 until minOf(res.length(), 6)) {
                val x = res.getJSONObject(i)
                out.append("- ").append(x.optString("title")).append(": ").append(x.optString("content").take(350))
                x.optString("publishedDate").takeIf { it.isNotBlank() && it != "null" }?.let { out.append(" (").append(it.take(10)).append(')') }
                out.append('\n')
            }
            return out.toString().trim().ifEmpty { null }
        }
    }

    private fun tavily(brain: StevoBrain, query: String): String? {
        val body = JSONObject().put("query", query).put("max_results", 5).put("include_answer", true).put("search_depth", "basic")
        val req = Request.Builder().url("https://api.tavily.com/search")
            .header("Authorization", "Bearer ${brain.tavilyKey}")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            if (r.code == 401) error("ključ nije ispravan")
            if (r.code == 432 || r.code == 429) error("potrošena mjesečna kvota")
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val o = JSONObject(r.body?.string().orEmpty())
            val out = StringBuilder()
            o.optString("answer").takeIf { it.isNotBlank() && it != "null" }?.let { out.append("Answer: ").append(it).append('\n') }
            val res = o.optJSONArray("results")
            if (res != null) for (i in 0 until res.length()) {
                val x = res.getJSONObject(i)
                out.append("- ").append(x.optString("title")).append(": ").append(x.optString("content").take(350)).append('\n')
            }
            return out.toString().trim().ifEmpty { null }
        }
    }
}
