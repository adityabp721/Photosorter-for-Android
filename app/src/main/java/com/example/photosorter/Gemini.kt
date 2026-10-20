package com.example.photosorter

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * One photo's AI answer. Sort tab: [folder] + [description]. Organize tab: also [type]
 * (food, screenshots, travel...) and [place] (a country, only for clear travel photos).
 */
data class AiResult(val folder: String, val description: String, val type: String = "", val place: String = "")

/** Rate limit / daily quota still exhausted after retries - the caller should stop the batch. */
class QuotaExceededException(msg: String) : Exception(msg)

object Gemini {

    const val DEFAULT_MODEL = "gemini-2.5-flash"
    private const val URL = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent"

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val PROMPT = """
        File this photo. Return ONLY a JSON object with exactly two keys:
        - "folder": ONE broad lowercase folder for the main subject. Prefer exactly one of "food", "people", "pets", "nature", "travel", "city", "home", "shopping", "documents", "screenshots", "vehicles", "events", "art", "other"; only invent a new 1-2 word kebab-case name if none fits at all.
        - "description": one short sentence (at most 15 words) describing the photo. Use the EXIF hints below when helpful.
    """.trimIndent()

    private val TRIP_CLASSIFY_PROMPT = """
        Classify this photo from a personal phone gallery. Use the EXIF hints below when helpful.
        Return ONLY a JSON object with exactly three keys:
        - "type": one of "travel", "food", "people", "pets", "nature", "city", "home", "shopping", "documents", "screenshot", "vehicles", "events", "art", "other".
        - "place": the country where it was taken, ONLY if the photo clearly shows travel away from home (a famous landmark, foreign-language signs, a tourist site) or the GPS says so; otherwise "none".
        - "description": one short sentence (at most 12 words) describing the photo.
    """.trimIndent()

    /** Per-photo trip classification: one call per photo -> {folder, description}. */
    suspend fun classifyTrip(
        apiKey: String,
        model: String,
        dataB64: String,
        hints: String,
        useWebSearch: Boolean
    ): AiResult = withContext(Dispatchers.IO) {
        val first = call(apiKey, model, listOf(dataB64), TRIP_CLASSIFY_PROMPT, hints, useWebSearch)
        if (useWebSearch && looksLikeToolUnsupportedError(first)) {
            return@withContext parseBody(call(apiKey, model, listOf(dataB64), TRIP_CLASSIFY_PROMPT, hints, false))
        }
        parseBody(first)
    }

    private class HttpResp(val code: Int, val body: String)

    suspend fun describe(
        apiKey: String,
        model: String,
        dataB64: String,
        hints: String,
        useWebSearch: Boolean
    ): AiResult = withContext(Dispatchers.IO) {
        val first = call(apiKey, model, listOf(dataB64), PROMPT, hints, useWebSearch)
        if (useWebSearch && looksLikeToolUnsupportedError(first)) {
            // Model/plan does not support Google Search grounding -> retry without the tool.
            return@withContext parseBody(call(apiKey, model, listOf(dataB64), PROMPT, hints, false))
        }
        parseBody(first)
    }

    /** Gemini 2.5 Flash thinks by default and thinking tokens count against maxOutputTokens. */
    private fun canDisableThinking(model: String) = model.startsWith("gemini-2.5-flash")

    private fun looksLikeToolUnsupportedError(resp: HttpResp): Boolean =
        resp.code != 200 &&
            ("google_search" in resp.body ||
                ("tool" in resp.body.lowercase() && "invalid" in resp.body.lowercase()))

    /** One request with retries on rate limits (429) and transient server errors (5xx). */
    private suspend fun call(
        apiKey: String,
        model: String,
        dataB64s: List<String>,
        prompt: String,
        hints: String,
        useWebSearch: Boolean
    ): HttpResp {
        var attempt = 0
        while (true) {
            val r = try {
                callOnce(apiKey, model, dataB64s, prompt, hints, useWebSearch)
            } catch (e: java.io.IOException) {
                if (attempt >= 2) throw IllegalStateException("Network error: ${e.message ?: e.javaClass.simpleName}")
                HttpResp(-1, e.message ?: "")
            }
            val retryable = r.code == 429 || r.code == -1 || r.code in 500..599
            if (!retryable) return r
            if (attempt >= 3) {
                if (r.code == 429) {
                    throw QuotaExceededException(
                        "Gemini quota/rate limit reached" +
                            (if ("PerDay" in r.body || "per day" in r.body.lowercase()) " (daily limit)" else "") +
                            ". Try again later, use a paid key, or switch to on-device mode."
                    )
                }
                return r
            }
            kotlinx.coroutines.delay(retryDelayMs(r.body, attempt))
            attempt++
        }
    }

    /** Honors the server's "retryDelay": "17s" hint when present, else 4 s, 8 s, 16 s backoff. */
    private fun retryDelayMs(body: String, attempt: Int): Long {
        val hinted = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+)(?:\\.\\d+)?s\"").find(body)
            ?.groupValues?.get(1)?.toLongOrNull()
        return ((hinted ?: (4L shl attempt)).coerceIn(1, 60)) * 1000L
    }

    private fun callOnce(
        apiKey: String,
        model: String,
        dataB64s: List<String>,
        prompt: String,
        hints: String,
        useWebSearch: Boolean
    ): HttpResp {
        val parts = JSONArray()
        for (b64 in dataB64s) {
            parts.put(JSONObject().put("inline_data", JSONObject()
                .put("mime_type", "image/jpeg")
                .put("data", b64)))
        }
        parts.put(JSONObject().put("text", "$prompt\nEXIF hints: $hints"))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("systemInstruction", JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("text", "You are a photo filing assistant. Reply with only the JSON object."))))
        val gen = JSONObject()
            .put("temperature", 0.2)
            .put("maxOutputTokens", 1024)
        // JSON mode can't be combined with tools (the API rejects it) - the reply is still
        // parsed out of the text below.
        if (!useWebSearch) gen.put("responseMimeType", "application/json")
        if (canDisableThinking(model)) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        body.put("generationConfig", gen)
        if (useWebSearch) {
            // Lets the model do a lightweight live search to identify landmarks, people, products.
            body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        }
        val url = Uri.parse(URL.format(Uri.encode(model))).toString()
        val req = Request.Builder()
            .url(url)
            // header instead of ?key= so the key never lands in URL logs
            .header("x-goog-api-key", apiKey.trim())
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return http.newCall(req).execute().use { r ->
            HttpResp(r.code, r.body?.string() ?: "")
        }
    }

    private fun parseBody(resp: HttpResp): AiResult {
        if (resp.code != 200) {
            throw IllegalStateException("HTTP ${resp.code}: ${resp.body.take(240)}")
        }
        val root = try {
            JSONObject(resp.body)
        } catch (e: Exception) {
            throw IllegalStateException("Bad response: ${resp.body.take(240)}")
        }
        val candArray = root.optJSONArray("candidates")
        if (candArray == null || candArray.length() == 0) {
            throw IllegalStateException("No candidates: ${resp.body.take(240)}")
        }
        val cand = candArray.getJSONObject(0)
        val parts = cand.optJSONObject("content")?.optJSONArray("parts")
            ?: throw IllegalStateException(
                "Empty answer (finishReason: ${cand.optString("finishReason", "unknown")})"
            )
        var out = ""
        for (i in 0 until parts.length()) {
            out += parts.optJSONObject(i)?.optString("text") ?: ""
        }
        val first = out.indexOf('{')
        val last = out.lastIndexOf('}')
        val json = try {
            JSONObject(if (first in 0 until (last - 1)) out.substring(first, last + 1) else out.trim())
        } catch (e: Exception) {
            throw IllegalStateException("Model did not return JSON: ${out.take(240)}")
        }
        val desc = json.optString("description").trim()
        if (desc.isEmpty()) throw IllegalStateException("Model returned an empty description")
        val type = json.optString("type").trim()
        if (type.isNotEmpty()) {
            val t = SmartFolders.normalizeType(type)
            return AiResult(t, desc, t, SmartFolders.normalizePlace(json.optString("place")))
        }
        return AiResult(sanitizeFolder(json.optString("folder")), desc)
    }

    private fun sanitizeFolder(raw: String): String {
        val s = raw.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(48)
            .trim('-')
        return if (s.isEmpty()) "other" else s
    }
}
