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

data class AiResult(val folder: String, val description: String)

object Gemini {

    const val DEFAULT_MODEL = "gemini-2.5-flash"
    private const val URL = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent"

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val PROMPT = """
        File this photo. Return ONLY a JSON object with exactly two keys:
        - "folder": a 1-3 word lowercase kebab-case folder name, e.g. "beach", "dog", "food", "documents", "screenshots", "new-york", "birthday", "products". Use "other" if nothing fits.
        - "description": one short sentence (at most 15 words) describing the photo. Use the EXIF hints below when helpful.
    """.trimIndent()

    private val TRIP_CLASSIFY_PROMPT = """
        You are sorting a personal photo gallery into folders per trip/place (like "japan-trip", "swiss-trip").
        Look at the photo and its EXIF hints (date, GPS coordinates). If the GPS identifies a location, trust it over your visual guess.
        Return ONLY a JSON object with exactly two keys:
        - "folder":
          * If the photo is from a trip/vacation/travel to a place: a 1-3 word lowercase kebab-case name of that trip. Use the country or main region + "-trip" (e.g. Tokyo and Kyoto photos are both "japan-trip"; Geneva and Zermatt are both "swiss-trip").
          * If it is NOT a travel photo: exactly one of "food", "animals", "family", "work", "documents", "screenshots", "other".
        - "description": one short sentence (at most 15 words) describing the photo. Use the EXIF hints below when helpful.
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

    private fun looksLikeToolUnsupportedError(resp: HttpResp): Boolean =
        resp.code != 200 &&
            ("google_search" in resp.body ||
                ("tool" in resp.body.lowercase() && "invalid" in resp.body.lowercase()))

    private fun call(
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
            .put("generationConfig", JSONObject()
                .put("temperature", 0.2)
                .put("maxOutputTokens", 150)
                .put("responseMimeType", "application/json"))
        if (useWebSearch) {
            // Lets the model do a lightweight live search to identify landmarks, people, products.
            body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        }
        val url = Uri.parse(URL.format(model))
            .buildUpon()
            .appendQueryParameter("key", apiKey.trim())
            .build()
            .toString()
        val req = Request.Builder()
            .url(url)
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
        val parts = candArray.getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
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
        val folder = sanitizeFolder(json.optString("folder"))
        val desc = json.optString("description").trim()
        if (desc.isEmpty()) throw IllegalStateException("Model returned an empty description")
        return AiResult(folder, desc)
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
