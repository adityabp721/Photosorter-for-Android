package com.example.photosorter

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * On-device (offline, private) folder + one-line description.
 *
 * Uses a MediaPipe EfficientNet-Lite0 image classifier (ImageNet labels) plus EXIF hints.
 * Captions are template-based ("a photo of X, taken ..."), deliberately simpler than
 * the cloud Gemini mode - this is the price of staying offline and free.
 */
object LocalAi {

    const val ASSET_NAME = "labeler.tflite"
    const val MODEL_DOWNLOAD_URL =
        "https://storage.googleapis.com/mediapipe-models/image_classifier/efficientnet_lite0/float32/latest/efficientnet_lite0.tflite"

    private val loadMutex = Mutex()
    private var model: ImageClassifier? = null
    private val classifyLock = Any()

    /** folder rules: (folder name, keyword set matched against lowercased labels) */
    private val FOLDER_RULES: List<Pair<String, Set<String>>> = listOf(
        "food" to setOf("food", "pizza", "burger", "cake", "bread", "cheese", "chicken", "coffee", "banana", "apple", "soup", "toast", "donut", "salad", "sandwich", "egg", "juice", "coffee cup", "hot dog", "ice cream", "ice lolly", "lemon", "orange", "pear", "pepper", "strawberry", "tomato", "zucchini"),
        "animals" to setOf("dog", "cat", "hamster", "horse", "bird", "swan", "wolf", "lion", "tiger", "bear", "elephant", "dolphin", "puppy", "kitten", "zebra", "ox", "cow", "pig", "sheep", "goat", "frog", "snake", "lizard", "spider"),
        "nature" to setOf("flower", "tree", "plant", "mountain", "beach", "sunset", "sky", "cactus", "waterfall", "river", "meadow", "forest", "leaf", "grass", "boat", "canoe"),
        "fashion" to setOf("face", "person", "man", "woman", "kid", "baby", "man's", "woman's", "shirt", "dress", "jeans", "hat", "glasses", "sunglasses", "shoe", "boot", "sneaker", "backpack", "handbag", "suit", "t-shirt"),
        "city" to setOf("cityscape", "city", "building", "skyscraper", "church", "window", "bridge", "street", "road", "house", "lighthouse", "eiffel tower"),
        "vehicles" to setOf("car", "truck", "bus", "bicycle", "motorcycle", "scooter", "train", "railcar", "airplane", "helicopter", "submarine", "sailboat", "picket fence", "suv"),
        "sports" to setOf("snowboard", "tennis ball", "soccer ball", "volleyball", "baseball", "basketball", "golf ball", "ski", "yoga", "bottle", "pitcher"),
        "documents" to setOf("notebook", "envelope", "paper", "desk", "keyboard", "screen", "monitor", "paper towel", "phone", "cell phone", "router", "printer"),
        "home" to setOf("kitchen", "bed", "lamp", "tv", "oven", "microwave", "refrigerator", "toilet", "bathtub", "curtain", "fountain pen", "piano", "vase", "cup", "bowl", "plate", "spoon", "fork", "knife", "bottle"),
    )

    /** Top ImageNet labels for a bitmap (used by offline trip naming). */
    suspend fun topLabels(ctx: Context, bitmap: Bitmap): List<Pair<String, Float>> =
        withContext(Dispatchers.IO) {
            val clf = ensureModel(ctx)
            ArrayList<Pair<String, Float>>(4).apply {
                // ImageClassifier is not thread-safe; callers run up to 3 photos in parallel
                synchronized(classifyLock) { clf.classify(BitmapImageBuilder(bitmap).build()) }
                    .classificationResult().classifications()
                    .flatMap { it.categories() }.take(4)
                    .forEach { add(it.categoryName() to it.score()) }
            }
        }

    suspend fun describeImage(
        ctx: Context,
        bitmap: Bitmap,
        dateLabel: String,
        hasGps: Boolean,
    ): AiResult = withContext(Dispatchers.IO) {
        val top = topLabels(ctx, bitmap)

        var folder = "other"
        for ((fname, words) in FOLDER_RULES) {
            if (top.any { (label, _) -> label.lowercase() in words }) { folder = fname; break }
        }
        val names = top.map { it.first.trim().replaceFirstChar { ch -> ch.uppercase() } }
        val ofClause = when {
            names.isEmpty() -> ""
            names.size == 1 -> "a photo of ${names[0]}"
            else -> "a photo of ${names.joinToString(", ")}"
        }
        val whenClause = if (dateLabel.isNotBlank() && dateLabel != "unknown") ", taken $dateLabel" else ""
        val gpsClause = if (hasGps) ", with GPS location" else ""
        val description = if (ofClause.isEmpty()) "Photo$whenClause$gpsClause" else "$ofClause$whenClause$gpsClause"
        AiResult(folder, description.take(120))
    }

    private suspend fun ensureModel(ctx: Context): ImageClassifier = loadMutex.withLock {
        model?.also { return@withLock it }
        val picker = ContextAssetPicker(ctx)
        if (!picker.has(ASSET_NAME)) {
            throw IllegalStateException(
                "On-device model missing from assets. Rebuild the app, or download it to app/src/main/assets/labeler.tflite: " + MODEL_DOWNLOAD_URL
            )
        }
        val built = ImageClassifier.createFromOptions(
            ctx,
            ImageClassifier.ImageClassifierOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(ASSET_NAME).build())
                .setMaxResults(5)
                .build()
        )
        model = built
        built
    }

    /** tiny asset-existence check that avoids throwing on the API side. */
    private class ContextAssetPicker(private val ctx: Context) {
        fun has(name: String): Boolean = try {
            ctx.assets.open(name).close()
            true
        } catch (e: Exception) {
            false
        }
    }
}
