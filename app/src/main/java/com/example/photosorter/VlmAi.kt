package com.example.photosorter

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device VLM (llama.cpp + Qwen3-VL-2B, ~1.6 GB of model files, Apache 2.0 - usable in a
 * commercial app) for offline photo classification. Model files live in <external-files>/llm/ -
 * either pushed via adb or downloaded in-app. Nothing leaves the phone.
 */
object VlmAi {

    const val MODEL_LABEL = "Qwen3-VL-2B"
    const val MODEL_NAME = "Qwen3VL-2B-Instruct-Q4_K_M.gguf"
    const val MMPROJ_NAME = "mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf"
    const val MODEL_URL =
        "https://huggingface.co/Qwen/Qwen3-VL-2B-Instruct-GGUF/resolve/main/Qwen3VL-2B-Instruct-Q4_K_M.gguf"
    const val MMPROJ_URL =
        "https://huggingface.co/Qwen/Qwen3-VL-2B-Instruct-GGUF/resolve/main/mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf"
    /** Total download, for messages and the free-space check. */
    const val DOWNLOAD_BYTES = 1_560_000_000L

    /** Earlier model (Qwen Research License, non-commercial) - deleted once the new one is ready. */
    private val LEGACY_FILES = listOf("Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf", "mmproj-F16.gguf")

    /**
     * Organize-tab prompt (wrapped in chat format by the native layer): the model reports WHAT the
     * photo is and, only for clear travel photos, WHERE. Folders and trips are built from that in
     * code (SmartFolders) - asking the model for folder names directly made it put everything
     * into the example trip.
     */
    const val TRIP_PROMPT =
        "Classify this photo from a personal phone gallery. EXIF hints: %s\n" +
            "Answer with ONLY three lines:\n" +
            "type: one of travel, food, people, pets, nature, city, home, shopping, documents, screenshot, vehicles, events, art, other\n" +
            "place: the country where it was taken, ONLY if the photo clearly shows travel away from home " +
            "(a famous landmark, foreign-language signs, a tourist site) or the GPS says so; otherwise none\n" +
            "description: one short sentence, max 12 words"

    /** Sort-tab prompt: topic folder + caption (no trip naming). */
    const val SORT_PROMPT =
        "File this photo from a personal gallery. EXIF hints: %s\n" +
            "Pick ONE broad lowercase folder for its main subject. Prefer exactly one of: food, people, " +
            "pets, nature, travel, city, home, shopping, documents, screenshots, vehicles, events, art, other. " +
            "Only invent a new 1-2 word kebab-case folder if none of these fits at all.\n" +
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
        modelFile(ctx).length() > 1_000_000_000L && mmprojFile(ctx).length() > 400_000_000L

    /** Frees the old model's ~3.3 GB once the new model is in place. */
    fun removeLegacyFiles(ctx: Context) {
        if (!isReady(ctx)) return
        LEGACY_FILES.forEach { runCatching { File(modelDir(ctx), it).delete() } }
    }

    /** Human-readable status for the UI. */
    fun statusText(ctx: Context): String = when {
        !nativeAvailable -> "Local AI not available in this build"
        loaded -> "$MODEL_LABEL loaded (on-device, offline)"
        isReady(ctx) -> "$MODEL_LABEL ready (${humanBytes(modelFile(ctx).length() + mmprojFile(ctx).length())}) - loads on first scan"
        else -> "$MODEL_LABEL not downloaded yet (~1.6 GB)."
    }

    /** Loads the model into memory (once). Returns false when files are missing. */
    suspend fun ensureLoaded(ctx: Context, onStatus: (String) -> Unit): Boolean {
        if (loaded) return true
        if (!nativeAvailable || !isReady(ctx)) return false
        removeLegacyFiles(ctx)
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
        var type = ""
        var place = ""
        // the model sometimes puts several "key: value" pairs on one line
        val parts = lines.flatMap { l -> l.split(Regex("(?i)(?=\\b(?:folder|description|type|place):)")).map { it.trim() } }
            .filter { it.isNotEmpty() }
        for (l in parts) {
            val v = l.substringAfter(':', "").trim()
            when {
                l.startsWith("folder:", ignoreCase = true) -> folder = v
                l.startsWith("description:", ignoreCase = true) -> desc = v
                l.startsWith("type:", ignoreCase = true) -> type = v
                l.startsWith("place:", ignoreCase = true) -> place = v
            }
        }
        if (type.isNotEmpty()) {
            val t = SmartFolders.normalizeType(type)
            return AiResult(t, desc.ifEmpty { "Photo" }, t, SmartFolders.normalizePlace(place))
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
