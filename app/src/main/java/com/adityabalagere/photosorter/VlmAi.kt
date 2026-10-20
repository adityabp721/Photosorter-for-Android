package com.adityabalagere.photosorter

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

    /**
     * Downloadable on-device models (both Apache 2.0). LITE is for phones with little RAM: it only
     * writes a caption, and the folder type comes from keywords in it ([liteResult]).
     */
    enum class Variant(
        val label: String, val modelName: String, val mmprojName: String, private val repo: String,
        val downloadBytes: Long, val minModelBytes: Long, val minMmprojBytes: Long, val lite: Boolean,
    ) {
        STANDARD(
            "Qwen3-VL-2B", "Qwen3VL-2B-Instruct-Q4_K_M.gguf", "mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf",
            "Qwen/Qwen3-VL-2B-Instruct-GGUF", 1_560_000_000L, 1_000_000_000L, 400_000_000L, lite = false,
        ),
        LITE(
            "SmolVLM2-500M", "SmolVLM2-500M-Video-Instruct-Q8_0.gguf", "mmproj-SmolVLM2-500M-Video-Instruct-Q8_0.gguf",
            "ggml-org/SmolVLM2-500M-Video-Instruct-GGUF", 546_000_000L, 400_000_000L, 100_000_000L, lite = true,
        );

        val modelUrl get() = "https://huggingface.co/$repo/resolve/main/$modelName"
        val mmprojUrl get() = "https://huggingface.co/$repo/resolve/main/$mmprojName"
        val sizeText get() = if (lite) "~550 MB" else "~1.6 GB"
    }

    /** Phones under this much RAM get LITE as the default (STANDARD needs ~2.5 GB while running). */
    const val LITE_BELOW_RAM_BYTES = 6_000_000_000L

    fun totalRamBytes(ctx: Context): Long {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
    }

    fun recommended(ctx: Context): Variant =
        if (totalRamBytes(ctx) in 1 until LITE_BELOW_RAM_BYTES) Variant.LITE else Variant.STANDARD

    /** The model in use; set from settings. Changing it unloads the other one. */
    @Volatile var variant: Variant = Variant.STANDARD
        private set

    fun select(v: Variant) {
        if (v == variant) return
        if (loaded) { runCatching { nativeFree() }; loaded = false }
        variant = v
    }

    val MODEL_LABEL get() = variant.label

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

    /** Prompt for the LITE model: small models follow a plain request far better than a format. */
    const val LITE_PROMPT = "Describe this photo in one short sentence. (%s)"

    /** Typical answer length in tokens - used to estimate per-photo progress. */
    val TYPICAL_ANSWER_TOKENS get() = if (variant.lite) 18 else 24
    const val MAX_TOKENS = 60

    val nativeAvailable: Boolean = runCatching { System.loadLibrary("vlm-ai") }.isSuccess

    @Volatile private var loaded = false

    fun modelDir(ctx: Context): File {
        val d = File(ctx.getExternalFilesDir(null), "llm")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun modelFile(ctx: Context, v: Variant = variant) = File(modelDir(ctx), v.modelName)
    fun mmprojFile(ctx: Context, v: Variant = variant) = File(modelDir(ctx), v.mmprojName)

    /** Model files present (with plausible sizes). */
    fun isReady(ctx: Context, v: Variant = variant): Boolean =
        modelFile(ctx, v).length() > v.minModelBytes && mmprojFile(ctx, v).length() > v.minMmprojBytes

    /** Deletes the files of a model that is not in use, to free space. */
    fun deleteFiles(ctx: Context, v: Variant) {
        if (v == variant && loaded) return
        listOf(modelFile(ctx, v), mmprojFile(ctx, v)).forEach { f ->
            runCatching { f.delete(); File(f.parentFile, f.name + ".part").delete() }
        }
    }

    /** Frees the old model's ~3.3 GB once the new model is in place. */
    fun removeLegacyFiles(ctx: Context) {
        if (!Variant.entries.any { isReady(ctx, it) }) return
        LEGACY_FILES.forEach { runCatching { File(modelDir(ctx), it).delete() } }
    }

    /** Human-readable status for the UI. */
    fun statusText(ctx: Context): String = when {
        !nativeAvailable -> "Local AI not available in this build"
        loaded -> "$MODEL_LABEL loaded (on-device, offline)"
        isReady(ctx) -> "$MODEL_LABEL ready (${humanBytes(modelFile(ctx).length() + mmprojFile(ctx).length())}) - loads on first scan"
        else -> "$MODEL_LABEL not downloaded yet (${variant.sizeText})."
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
        val prompt = if (variant.lite) LITE_PROMPT else promptTemplate
        return withContext(Dispatchers.IO) { nativeClassify(jpeg, prompt.format(hints), MAX_TOKENS) }
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
        if (variant.lite) return liteResult(raw)
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

    /**
     * LITE answer (one caption sentence) -> type from keywords in the caption, in priority order
     * (a "pan of curry" is food even with a person in it). No place: trips still come from the
     * time zone / GPS, and are named by GPS country or month.
     */
    fun liteResult(raw: String): AiResult? {
        val caption = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?.replace(Regex("\\s+"), " ")?.take(160) ?: return null
        val text = caption.lowercase()
        val type = LITE_KEYWORDS.firstOrNull { (_, words) ->
            words.any { Regex("\\b$it(s|es)?\\b").containsMatchIn(text) }
        }?.first ?: "other"
        return AiResult(type, caption.trimEnd('.'), type, "")
    }

    private val LITE_KEYWORDS: List<Pair<String, List<String>>> = listOf(
        "screenshots" to listOf("screenshot", "screen", "app", "website", "web page", "webpage", "chat"),
        "food" to listOf(
            "food", "plate", "dish", "bowl", "meal", "curry", "noodle", "ramen", "soup", "stew", "salad",
            "pizza", "burger", "sandwich", "cake", "dessert", "bread", "rice", "sushi", "pan", "pot",
            "cooking", "batter", "baking", "fruit", "vegetable", "coffee", "drink", "breakfast", "lunch", "dinner",
        ),
        "pets" to listOf("dog", "cat", "puppy", "kitten", "pet", "hamster", "parrot"),
        "documents" to listOf("document", "receipt", "paper", "letter", "form", "invoice", "text", "page", "book", "whiteboard"),
        "shopping" to listOf("product", "shopping", "store", "shop", "price", "package", "box"),
        "events" to listOf(
            "stage", "concert", "music", "musician", "performance", "performer", "party", "festival", "wedding",
            "birthday", "crowd", "instrument", "band", "costume", "celebration", "game", "match",
        ),
        "travel" to listOf(
            "temple", "pavilion", "shrine", "pagoda", "landmark", "monument", "castle", "palace", "museum",
            "tourist", "airport", "cathedral", "tower", "ruins",
        ),
        "vehicles" to listOf("car", "truck", "bus", "train", "motorcycle", "bike", "bicycle", "plane", "airplane", "boat"),
        "art" to listOf("painting", "sculpture", "statue", "mural", "artwork", "drawing", "emblem", "mosaic"),
        "people" to listOf(
            "person", "people", "man", "men", "woman", "women", "boy", "girl", "child", "children", "kid",
            "baby", "family", "couple", "friend", "selfie", "group",
        ),
        "nature" to listOf(
            "beach", "sea", "ocean", "shore", "mountain", "forest", "tree", "sunset", "sunrise", "lake",
            "river", "flower", "sky", "garden", "field", "waterfall", "snow", "bird", "landscape",
        ),
        "city" to listOf("street", "building", "city", "skyline", "bridge", "road", "skyscraper"),
        "home" to listOf("room", "kitchen", "bedroom", "sofa", "couch", "furniture", "living room", "bathroom"),
    )
}
