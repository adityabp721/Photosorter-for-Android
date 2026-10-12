package com.example.photosorter

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device VLM (llama.cpp + Qwen2.5-VL-3B, ~3.3 GB of model files) for offline photo
 * classification. Model files live in <external-files>/llm/ - either pushed via adb or
 * downloaded in-app. Nothing leaves the phone.
 */
object VlmAi {

    const val MODEL_NAME = "Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf"
    const val MMPROJ_NAME = "mmproj-F16.gguf"
    const val MODEL_URL =
        "https://huggingface.co/unsloth/Qwen2.5-VL-3B-Instruct-GGUF/resolve/main/Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf"
    const val MMPROJ_URL =
        "https://huggingface.co/unsloth/Qwen2.5-VL-3B-Instruct-GGUF/resolve/main/mmproj-F16.gguf"

    /** Inner prompt sent to the model (wrapped in chat format by the native layer). */
    const val TRIP_PROMPT =
        "You are sorting a personal photo gallery into folders per trip/place (like japan-trip, swiss-trip). " +
            "Look at the photo and its EXIF hints. If the GPS identifies a location, trust it over your visual guess.\n" +
            "EXIF hints: %s\n" +
            "If the photo is from a trip or travel to a place, the folder must be 1-3 word lowercase kebab-case " +
            "using the country or main region plus -trip (Tokyo and Kyoto are both japan-trip). " +
            "If it is NOT a travel photo, use exactly one of: food, animals, family, work, documents, screenshots, other.\n" +
            "Reply with ONLY two lines:\n" +
            "folder: <folder name>\n" +
            "description: <one short sentence, max 12 words>"

    /** Sort-tab prompt: topic folder + caption (no trip naming). */
    const val SORT_PROMPT =
        "File this photo from a personal gallery. EXIF hints: %s\n" +
            "Pick a 1-3 word lowercase kebab-case folder for its main subject, e.g. beach, dog, food, " +
            "documents, screenshots, new-york, birthday, products. Use other if nothing fits.\n" +
            "Reply with ONLY two lines:\n" +
            "folder: <folder name>\n" +
            "description: <one short sentence, max 15 words>"

    /** Typical answer length in tokens - used to estimate per-photo progress. */
    const val TYPICAL_ANSWER_TOKENS = 24
    const val MAX_TOKENS = 60

    val nativeAvailable: Boolean = runCatching { System.loadLibrary("vlm-ai") }.isSuccess

    @Volatile private var loaded = false

    fun modelDir(ctx: Context): File {
        val d = File(ctx.getExternalFilesDir(null), "llm")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun modelFile(ctx: Context) = File(modelDir(ctx), MODEL_NAME)
    fun mmprojFile(ctx: Context) = File(modelDir(ctx), MMPROJ_NAME)

    /** Model files present (with plausible sizes). */
    fun isReady(ctx: Context): Boolean =
        modelFile(ctx).length() > 1_500_000_000L && mmprojFile(ctx).length() > 500_000_000L

    /** Human-readable status for the UI. */
    fun statusText(ctx: Context): String = when {
        !nativeAvailable -> "Local AI not available in this build"
        loaded -> "Local AI model loaded (on-device, offline)"
        isReady(ctx) -> "Local AI model found (${humanBytes(modelFile(ctx).length() + mmprojFile(ctx).length())}) - will load on first scan"
        else -> "Local AI model missing (~3.3 GB). Download it below, or push to Android/data/com.example.photosorter/files/llm/"
    }

    /** Loads the model into memory (once). Returns false when files are missing. */
    suspend fun ensureLoaded(ctx: Context, onStatus: (String) -> Unit): Boolean {
        if (loaded) return true
        if (!nativeAvailable || !isReady(ctx)) return false
        onStatus("Loading local AI model into memory - takes about a minute the first time...")
        val rc = withContext(Dispatchers.IO) {
            nativeInit(
                modelFile(ctx).absolutePath,
                mmprojFile(ctx).absolutePath,
                4096,
                Runtime.getRuntime().availableProcessors().coerceIn(2, 6),
            )
        }
        loaded = rc == 0
        return loaded
    }

    /**
     * Classifies one JPEG with [promptTemplate] (TRIP_PROMPT or SORT_PROMPT, `%s` = EXIF hints).
     * Returns raw model text, or null on failure / abort.
     */
    suspend fun classify(jpeg: ByteArray, hints: String, promptTemplate: String = TRIP_PROMPT): String? {
        if (!loaded) return null
        return withContext(Dispatchers.IO) { nativeClassify(jpeg, promptTemplate.format(hints), MAX_TOKENS) }
    }

    /** (stage, tokens): stage 0 = idle, 1 = reading image, 2 = writing answer. */
    fun progress(): Pair<Int, Int> {
        if (!loaded) return 0 to 0
        val v = runCatching { nativeProgress() }.getOrDefault(0)
        return (v / 1000) to (v % 1000)
    }

    /** Interrupts the in-flight classify (Stop button). Safe to call anytime. */
    fun abort() {
        if (loaded) runCatching { nativeAbort() }
    }

    /** Parses the model's two-line answer into a folder + description. */
    fun parseOutput(raw: String): AiResult? {
        // tolerate markdown the model sometimes adds ("**folder:** x", "- folder: x")
        val lines = raw.lineSequence()
            .map { it.replace("**", "").trim().trimStart('-', '*', '`', ' ').trim() }
            .filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return null
        var folder = ""
        var desc = ""
        for (l in lines) {
            when {
                l.startsWith("folder:", ignoreCase = true) -> folder = l.removePrefix("folder:").trim()
                l.startsWith("description:", ignoreCase = true) -> desc = l.removePrefix("description:").trim()
            }
        }
        if (folder.isEmpty()) folder = lines[0]
        if (desc.isEmpty() && lines.size > 1) desc = lines[1]
        val f = folder.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48).trim('-')
        if (f.isEmpty()) return null
        return AiResult(f, desc.ifEmpty { "Photo" })
    }

    private external fun nativeInit(modelPath: String, mmprojPath: String, nCtx: Int, nThreads: Int): Int
    private external fun nativeClassify(jpeg: ByteArray, innerPrompt: String, maxTokens: Int): String?
    private external fun nativeFree()
    private external fun nativeProgress(): Int
    private external fun nativeAbort()
}
