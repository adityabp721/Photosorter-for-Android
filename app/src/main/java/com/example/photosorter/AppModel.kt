package com.example.photosorter

import android.Manifest
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.exp
import kotlin.math.min

/**
 * All app state + job orchestration. One instance per process (see [PhotoSorterApp]); jobs run
 * in [scope], not in the UI's scope, so they keep going when the activity is closed while
 * [WorkService] keeps the process alive and shows progress in a notification.
 */
class AppModel(app: Context) {

    private val ctx = app.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val resolver = ctx.contentResolver
    private val repo = PhotoRepo(resolver)
    private val sp = ctx.getSharedPreferences("photosorter", Context.MODE_PRIVATE)

    // ---- index of filed photos (shared by all tabs) ----

    private val index = Organizer.loadIndex(ctx)
    private val indexLock = Any()
    private val indexSaveLock = Any()
    private var indexDirty = false
    private var lastIndexSave = 0L

    private fun indexGet(id: Long): DoneRec? = synchronized(indexLock) { index[id] }

    private fun indexPut(id: Long, rec: DoneRec) {
        synchronized(indexLock) {
            index[id] = rec
            indexDirty = true
        }
        saveIndex(force = false)
    }

    /** Throttled to one write per 2 s while a batch runs; forced at the end of every job. */
    private fun saveIndex(force: Boolean) {
        synchronized(indexSaveLock) {
            val snap: Map<Long, DoneRec>
            synchronized(indexLock) {
                if (!indexDirty) return
                val now = SystemClock.elapsedRealtime()
                if (!force && now - lastIndexSave < 2000) return
                lastIndexSave = now
                indexDirty = false
                snap = LinkedHashMap(index)
            }
            Organizer.saveIndex(ctx, snap)
        }
    }

    // ---- general ----

    var tab: String by mutableStateOf("sort")                       // "sort" | "trips" | "dups"
    var mode: String by mutableStateOf(sp.getString("mode", "cloud") ?: "cloud") // "cloud" | "local"
    var apiKey: String by mutableStateOf(sp.getString("key", "") ?: "")
    var modelName: String by mutableStateOf(sp.getString("model", Gemini.DEFAULT_MODEL) ?: Gemini.DEFAULT_MODEL)
    var limitText: String by mutableStateOf("50")
    var useWebSearch: Boolean by mutableStateOf(sp.getBoolean("search", true))
    var status: String by mutableStateOf("")
    var busy: Boolean by mutableStateOf(false)
    var permissions: Boolean by mutableStateOf(false)
    var partialAccess: Boolean by mutableStateOf(false)

    /** Set by MainActivity in onStart/onStop. Finished-notifications are only posted when false. */
    var appVisible: Boolean by mutableStateOf(false)

    /** Overall progress of the running job (UI bar + notification). */
    var job: JobProgress? by mutableStateOf(null)
        private set

    val anyBusy: Boolean get() = busy || tripBusy || dupBusy || vlmBusy

    private val _rows = mutableStateOf<List<Row>>(emptyList())
    val rows: List<Row> get() = _rows.value

    @Volatile private var stopping = false

    // ---- trips ----

    var tripBusy: Boolean by mutableStateOf(false)
    var tripStatus: String by mutableStateOf("")
    var tripLimitText: String by mutableStateOf("200")
    var tripMove: Boolean by mutableStateOf(sp.getBoolean("tripmove", false))
    /** On-device mode: also look up country names for GPS photos (sends coordinates only to Google). */
    var geocodeLocal: Boolean by mutableStateOf(sp.getBoolean("geocode", false))
    private val geoNames = java.util.concurrent.ConcurrentHashMap<Long, String>()
    private val _trips = mutableStateOf<List<TripCluster>>(emptyList())
    val trips: List<TripCluster> get() = _trips.value
    private val _tripRows = mutableStateOf<List<Row>>(emptyList())
    val tripRows: List<Row> get() = _tripRows.value

    /** AI answers from earlier scans this session - re-scans only analyze photos missing here. */
    private val tripResults = Collections.synchronizedMap(HashMap<Long, AiResult>())

    @Volatile private var tripStopping = false

    // ---- duplicates ----

    var dupBusy: Boolean by mutableStateOf(false)
    var dupStatus: String by mutableStateOf("")
    var lastFreed: Long by mutableStateOf(0L)
    private val _clusters = mutableStateOf<List<DupCluster>>(emptyList())
    val clusters: List<DupCluster> get() = _clusters.value
    var selected: Set<Long> by mutableStateOf(emptySet())

    @Volatile private var dupStopping = false

    val selectedBytes: Long
        get() = _clusters.value.flatMap { it.photos }.filter { it.id in selected }.sumOf { it.sizeBytes }

    // ---- deletion (Android 11+ needs one system confirmation for other apps' photos) ----

    /** Photos waiting to be deleted: originals of a MOVE, or selected duplicates. */
    class PendingDelete(val photos: List<Photo>, val purpose: String) // "move" | "dups"

    var pendingDelete: PendingDelete? by mutableStateOf(null)
        private set

    /** The system confirmation to show. MainActivity launches it while visible, then clears it. */
    var deleteRequest: IntentSender? by mutableStateOf(null)

    // ---- local VLM model download ----

    var vlmBusy: Boolean by mutableStateOf(false)
    var vlmStatus: String by mutableStateOf("")

    @Volatile private var downloadStopping = false

    private val vlmHttp = okhttp3.OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // running estimates for per-photo progress bars (ms)
    @Volatile private var avgVlmEncodeMs = 12_000.0
    @Volatile private var avgCloudMs = 5_000.0

    init {
        refreshPermissions()
        restorePendingMove()
    }

    // ---- permissions ----

    private fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun refreshPermissions() {
        val full = when {
            Build.VERSION.SDK_INT >= 33 -> has(Manifest.permission.READ_MEDIA_IMAGES)
            Build.VERSION.SDK_INT >= 29 -> has(Manifest.permission.READ_EXTERNAL_STORAGE)
            else -> has(Manifest.permission.READ_EXTERNAL_STORAGE) && has(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val partial = !full && Build.VERSION.SDK_INT >= 34 &&
            has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        permissions = full || partial
        partialAccess = partial
    }

    /** Everything the permission screen asks for (photos + notifications). */
    fun permissionsToRequest(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.READ_MEDIA_IMAGES)
        else add(Manifest.permission.READ_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        if (Build.VERSION.SDK_INT < 29) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else add(Manifest.permission.ACCESS_MEDIA_LOCATION) // GPS in EXIF - big help for trip names
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    /** Photo access was granted before the app asked for photo locations. */
    val missingLocation: Boolean
        get() = permissions && Build.VERSION.SDK_INT >= 29 && !has(Manifest.permission.ACCESS_MEDIA_LOCATION)

    fun onPermissionsChanged(@Suppress("UNUSED_PARAMETER") granted: Map<String, Boolean>) = refreshPermissions()

    private fun requirePhotoAccess(report: (String) -> Unit): Boolean {
        refreshPermissions()
        if (permissions) return true
        report(
            "Photo access is not allowed. Press \"Allow photo access\", choose ALL photos " +
                "(not just selected ones), then try again."
        )
        return false
    }

    fun savePrefs() {
        sp.edit()
            .putString("key", apiKey)
            .putString("model", modelName)
            .putString("mode", mode)
            .putBoolean("search", useWebSearch)
            .putBoolean("tripmove", tripMove)
            .putBoolean("geocode", geocodeLocal)
            .apply()
    }

    // ---- job plumbing ----

    private suspend fun onMain(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    private fun beginJob(title: String) {
        job = JobProgress(title)
        Notifications.cancelResult(ctx)
        WorkService.start(ctx)
    }

    private fun jobProgress(done: Long, total: Long, detail: String) {
        job = job?.copy(done = done, total = total, detail = detail)
    }

    private fun endJob(title: String, summary: String) {
        job = null
        if (!appVisible) Notifications.showResult(ctx, title, summary)
    }

    /** Stop button in the notification: stops whatever is running. */
    fun stopAll() {
        if (busy) stop()
        if (tripBusy) stopTrips()
        if (dupBusy) stopDups()
        if (vlmBusy) stopDownload()
    }

    /** Thrown when a photo's work was interrupted by Stop (the photo goes back to "queued"). */
    private class StoppedException : Exception("stopped")

    /** Runs [work] over [items] with [parallel] workers, pulling items in order, until [stopped]. */
    private suspend fun <T> pool(
        items: List<T>,
        parallel: Int,
        stopped: () -> Boolean,
        work: suspend (T) -> Unit,
    ) = coroutineScope {
        val next = AtomicInteger(0)
        repeat(parallel.coerceIn(1, maxOf(1, items.size))) {
            launch(Dispatchers.Default) {
                while (!stopped()) {
                    val i = next.getAndIncrement()
                    if (i >= items.size) break
                    work(items[i])
                }
            }
        }
    }

    private suspend fun Row.show(what: String, fraction: Float) = onMain {
        state = RowState.Progress(what, fraction)
    }

    /** Runs [block] while a ticker refreshes [row]'s progress bar from [progressAt] (elapsed ms). */
    private suspend fun <T> tracked(
        row: Row,
        progressAt: (Long) -> RowState.Progress,
        block: suspend () -> T,
    ): T = coroutineScope {
        val t0 = SystemClock.elapsedRealtime()
        val ticker = launch(Dispatchers.Main) {
            while (true) {
                row.state = progressAt(SystemClock.elapsedRealtime() - t0)
                delay(250)
            }
        }
        try {
            block()
        } finally {
            ticker.cancel()
        }
    }

    /** 0..1 approaching 1 as [elapsed] passes [expected] - for steps without real progress. */
    private fun ease(elapsed: Long, expected: Double): Float =
        (1.0 - exp(-elapsed / expected.coerceAtLeast(500.0))).toFloat()

    private fun errText(e: Exception): String = when (e) {
        is SecurityException -> "Android blocked access to this photo"
        else -> e.message ?: e.javaClass.simpleName
    }

    // ---- per-photo AI ----

    private enum class Engine(val label: String) {
        CLOUD("cloud"), VLM("on-device AI"), LABELS("on-device labels")
    }

    /** Cloud, or (local mode) the on-device VLM when its files are present, else the labeler. */
    private suspend fun pickEngine(report: (String) -> Unit): Engine {
        if (mode != "local") return Engine.CLOUD
        if (VlmAi.ensureLoaded(ctx) { s -> scope.launch { report(s) } }) return Engine.VLM
        report("On-device AI model not available - using basic content labels (no place names).")
        return Engine.LABELS
    }

    private fun cloudConfigError(): String? = when {
        mode == "local" -> null
        apiKey.isBlank() -> "Enter your Gemini API key first (free at aistudio.google.com), or switch to on-device mode."
        modelName.isBlank() -> "Enter a model name."
        else -> null
    }

    /** Classifies one photo, updating [row]'s progress bar along the way. */
    private suspend fun analyze(p: Photo, engine: Engine, trip: Boolean, row: Row): AiResult =
        when (engine) {
            Engine.LABELS -> {
                row.show("reading photo", 0.15f)
                val bmp = withContext(Dispatchers.IO) { ImageTools.decodeSmall(resolver, p.uri, 256) }
                row.show("labeling on-device", 0.55f)
                try {
                    LocalAi.describeImage(ctx, bmp, p.dateLabel, p.latitude != null)
                } finally {
                    bmp.recycle()
                }
            }
            Engine.VLM -> {
                row.show("preparing image", 0.04f)
                val bytes = withContext(Dispatchers.IO) { ImageTools.toJpegBytes(resolver, p.uri, 768) }
                if (stoppingFor(trip)) throw StoppedException()
                var encodeMs = -1L
                val raw = tracked(row, { elapsed ->
                    val (stage, tokens) = VlmAi.progress()
                    if (stage == 2) {
                        if (encodeMs < 0) {
                            encodeMs = elapsed
                            avgVlmEncodeMs = 0.7 * avgVlmEncodeMs + 0.3 * elapsed
                        }
                        RowState.Progress(
                            "writing answer",
                            0.65f + 0.3f * min(1f, tokens / VlmAi.TYPICAL_ANSWER_TOKENS.toFloat()),
                        )
                    } else {
                        // no real progress inside the image encoder: estimate from earlier photos,
                        // and show elapsed time so a slow photo still visibly moves
                        RowState.Progress(
                            "reading image (on-device AI) - ${elapsed / 1000} s",
                            0.05f + 0.6f * ease(elapsed, avgVlmEncodeMs),
                        )
                    }
                }) {
                    VlmAi.classify(bytes, buildHints(p), if (trip) VlmAi.TRIP_PROMPT else VlmAi.SORT_PROMPT)
                }
                if (raw == null && stoppingFor(trip)) throw StoppedException()
                raw ?: throw IllegalStateException("Local AI returned no answer")
                VlmAi.parseOutput(raw)
                    ?: throw IllegalStateException("Could not parse local AI answer: ${raw.take(80)}")
            }
            Engine.CLOUD -> {
                row.show("shrinking photo", 0.08f)
                val b64 = withContext(Dispatchers.IO) { ImageTools.toBase64Jpeg(resolver, p.uri) }
                if (stoppingFor(trip)) throw StoppedException()
                val label = if (useWebSearch) "asking AI + web search" else "asking AI"
                val t0 = SystemClock.elapsedRealtime()
                val r = tracked(row, { e -> RowState.Progress(label, 0.2f + 0.65f * ease(e, avgCloudMs)) }) {
                    if (trip) Gemini.classifyTrip(apiKey.trim(), modelName.trim(), b64, buildHints(p), useWebSearch)
                    else Gemini.describe(apiKey.trim(), modelName.trim(), b64, buildHints(p), useWebSearch)
                }
                avgCloudMs = 0.7 * avgCloudMs + 0.3 * (SystemClock.elapsedRealtime() - t0)
                r
            }
        }

    private fun stoppingFor(trip: Boolean) = if (trip) tripStopping else stopping

    private fun buildHints(p: Photo): String {
        val sb = StringBuilder()
        if (p.dateTakenMs > 0) sb.append("Taken: ${p.dateLabel}. ")
        if (p.latitude != null && p.longitude != null) {
            sb.append("GPS: ").append(String.format(java.util.Locale.US, "%.5f, %.5f", p.latitude, p.longitude)).append(". ")
        }
        geoNames[p.id]?.let { sb.append("Place from GPS: $it. ") }
        sb.append("Pixel size: ${p.width}x${p.height}.")
        return sb.toString()
    }

    /** Country names for GPS photos via the phone's geocoder (cached per ~10 km cell). */
    private suspend fun lookupCountries(photos: List<Photo>) = withContext(Dispatchers.IO) {
        if (!(mode == "cloud" || geocodeLocal) || !android.location.Geocoder.isPresent()) return@withContext
        val geo = android.location.Geocoder(ctx, java.util.Locale.ENGLISH)
        val cache = HashMap<String, String?>()
        for (p in photos) {
            val lat = p.latitude ?: continue
            val lon = p.longitude ?: continue
            val key = String.format(java.util.Locale.US, "%.1f,%.1f", lat, lon)
            val country = cache.getOrPut(key) {
                @Suppress("DEPRECATION")
                runCatching { geo.getFromLocation(lat, lon, 1)?.firstOrNull()?.countryName }.getOrNull()
            }
            if (country != null) geoNames[p.id] = country
        }
    }

    private val homeCountry: String?
        get() = java.util.Locale.getDefault().getDisplayCountry(java.util.Locale.ENGLISH).ifBlank { null }

    /** Samsung/Pixel screenshots carry the app in the name: "Screenshot_20261019_173200_Amazon Shopping.jpg". */
    private fun screenshotResult(p: Photo): AiResult {
        val app = Regex("^Screenshot_\\d{8}[-_]\\d{6}_(.+)\\.\\w+$", RegexOption.IGNORE_CASE).find(p.title)
            ?.groupValues?.get(1)?.trim()
        return AiResult("screenshots", if (app.isNullOrBlank()) "Screenshot" else "Screenshot from $app", "screenshots")
    }

    private val hasLocation: Boolean
        get() = Build.VERSION.SDK_INT >= 29 && has(Manifest.permission.ACCESS_MEDIA_LOCATION)

    private suspend fun withGps(photos: List<Photo>, onProgress: suspend (Int) -> Unit): List<Photo> {
        val out = ArrayList<Photo>(photos.size)
        for ((i, p) in photos.withIndex()) {
            val e = withContext(Dispatchers.IO) { ImageTools.exifInfo(resolver, p.uri, original = hasLocation) }
            out.add(p.copy(latitude = e.lat, longitude = e.lon, utcOffsetMin = e.utcOffsetMin))
            if (i % 50 == 0) onProgress(i)
        }
        return out
    }

    // ---- sort & describe ----

    fun scan() = scope.launch {
        if (anyBusy) return@launch
        savePrefs()
        if (!requirePhotoAccess { status = it }) return@launch
        busy = true
        try {
            status = "Scanning photo library..."
            val n = limitText.toIntOrNull()?.coerceIn(1, 500) ?: 50
            val base = withContext(Dispatchers.IO) { repo.recent(n) }
            val photos = withGps(base) { i -> status = "Reading photo details: $i/${base.size}" }
            val newRows = photos.map { p -> Row(p, indexGet(p.id)) }
            _rows.value = newRows
            val cached = newRows.count { it.state is RowState.Cached }
            status = "Loaded ${newRows.size} photos (latest first). $cached already filed, will be skipped."
        } catch (e: Exception) {
            status = "Scan failed: ${errText(e)}"
        } finally {
            busy = false
        }
    }

    fun runSort() = scope.launch {
        if (anyBusy) return@launch
        savePrefs()
        if (!requirePhotoAccess { status = it }) return@launch
        cloudConfigError()?.let { status = it; return@launch }
        val todo = rows.filter { it.state is RowState.Todo }
        if (todo.isEmpty()) {
            status = "Nothing to file - scan first (already-filed photos are skipped, press Retry errors for failed ones)."
            return@launch
        }
        busy = true
        stopping = false
        beginJob("Sorting ${todo.size} photos")
        var filed = 0
        var failed = 0
        var quotaMsg: String? = null
        var engine = Engine.CLOUD
        try {
            engine = pickEngine { status = it }
            val parallel = if (engine == Engine.VLM) 1 else 3
            status = "Filing ${todo.size} photos (${engine.label}, $parallel at a time)..."
            jobProgress(0, todo.size.toLong(), "")
            pool(todo, parallel, { stopping }) { row ->
                try {
                    val ai = analyze(row.photo, engine, trip = false, row)
                    row.show("filing into folder", 0.95f)
                    val rec = withContext(Dispatchers.IO) { Organizer.copyAndDescribe(ctx, resolver, row.photo, ai) }
                    indexPut(row.photo.id, rec)
                    onMain {
                        row.state = RowState.Done(rec)
                        filed++
                        status = "Filed $filed/${todo.size} -> ${rec.folder}/ - ${rec.description}"
                        jobProgress((filed + failed).toLong(), todo.size.toLong(), "${row.photo.title} -> ${rec.folder}/")
                    }
                } catch (e: StoppedException) {
                    onMain { row.state = RowState.Todo }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: QuotaExceededException) {
                    stopping = true
                    onMain { row.state = RowState.Todo; quotaMsg = e.message }
                } catch (e: Exception) {
                    onMain {
                        row.state = RowState.Failed(errText(e))
                        failed++
                        status = if (e is SecurityException) {
                            "Photo access not allowed for some images. Open Settings > Apps > PhotoSorter > " +
                                "Photos and videos and choose \"All photos\", then press Retry errors."
                        } else "Last error: ${errText(e)}"
                        jobProgress((filed + failed).toLong(), todo.size.toLong(), "${row.photo.title}: error")
                    }
                }
            }
        } finally {
            saveIndex(force = true)
            rows.forEach { if (it.state is RowState.Progress) it.state = RowState.Todo }
            busy = false
            val left = rows.count { it.state is RowState.Todo }
            val summary = when {
                quotaMsg != null -> "$quotaMsg Filed $filed so far; $left left for later."
                stopping -> "Stopped after filing $filed. Press Organize again to continue with the remaining $left."
                else -> "Finished: filed $filed photos" + if (failed > 0) ", $failed failed - press Retry errors." else "."
            }
            status = summary
            endJob(if (stopping || quotaMsg != null) "Sorting stopped" else "Sorting finished", summary)
        }
    }

    fun stop() {
        stopping = true
        VlmAi.abort()
        status = "Stopping - finishing the photos already in progress..."
    }

    fun retryFailed() {
        rows.forEach { if (it.state is RowState.Failed) it.state = RowState.Todo }
        status = "Failed photos are queued again - press Organize to retry."
    }

    fun openCopy(outPath: String) {
        Organizer.openCopy(ctx, outPath)
    }

    // ---- trips: AI-scan each photo, file into per-trip folders ----

    /**
     * Reads the library (newest first), then AI-scans every not-yet-filed photo: the model sees
     * the image + EXIF date/GPS and returns a folder like "japan-trip" or a category for
     * non-travel photos. Results are grouped into folders for review/organizing.
     */
    fun scanTrips() = scope.launch {
        if (anyBusy) return@launch
        savePrefs()
        if (!requirePhotoAccess { tripStatus = it }) return@launch
        cloudConfigError()?.let { tripStatus = it; return@launch }
        tripBusy = true
        tripStopping = false
        _trips.value = emptyList()
        beginJob("Scanning photos for trips")
        var title = "Trip scan finished"
        var summary = ""
        try {
            val limit = tripLimitText.toIntOrNull()?.coerceIn(1, 20000) ?: 200
            tripStatus = "Reading photo library (up to $limit newest)..."
            val base = withContext(Dispatchers.IO) { repo.all(limit, excludeOwnCopies = true) }
            // already-filed photos are skipped - lets you run this in batches within API quotas
            val unfiled = base.filter { indexGet(it.id) == null }
            if (unfiled.isEmpty()) {
                summary = "All ${base.size} newest photos are already filed - nothing to scan."
                _tripRows.value = emptyList()
                return@launch
            }
            val photos = withGps(unfiled) { i ->
                tripStatus = "Reading location data: $i/${unfiled.size}"
                jobProgress(i.toLong(), unfiled.size.toLong(), "Reading location data")
            }
            if (tripStopping) {
                title = "Trip scan stopped"
                summary = "Stopped."
                return@launch
            }
            lookupCountries(photos)
            val rowsNew = photos.map { p ->
                Row(p, null).apply { tripResults[p.id]?.let { state = RowState.Scanned(it) } }
            }
            _tripRows.value = rowsNew
            val todo = rowsNew.filter { it.state is RowState.Todo }
            val engine = if (todo.isEmpty()) Engine.CLOUD else pickEngine { tripStatus = it }
            val parallel = if (engine == Engine.VLM) 1 else 3
            if (todo.isNotEmpty()) {
                tripStatus = "AI-scanning ${todo.size} photos (${engine.label}" +
                    (if (todo.size < photos.size) ", ${photos.size - todo.size} already scanned" else "") +
                    ") - this can take a while, you can leave the app or press Stop anytime."
            }
            var done = 0
            var failed = 0
            var quotaMsg: String? = null
            jobProgress(0, todo.size.toLong(), "")
            pool(todo, parallel, { tripStopping }) { row ->
                try {
                    // screenshots and WhatsApp images are recognized from where they live - no AI call needed
                    val ai = when {
                        row.photo.isScreenshot -> screenshotResult(row.photo)
                        row.photo.isWhatsApp -> AiResult("whatsapp", "Image received on WhatsApp", "whatsapp")
                        else -> analyze(row.photo, engine, trip = true, row)
                    }
                    tripResults[row.photo.id] = ai
                    onMain {
                        row.state = RowState.Scanned(ai)
                        done++
                        tripStatus = "Scanned ${done + failed} of ${todo.size} - last: ${ai.type.ifEmpty { ai.folder }}"
                        jobProgress((done + failed).toLong(), todo.size.toLong(), "${row.photo.title} -> ${ai.folder}")
                    }
                } catch (e: StoppedException) {
                    onMain { row.state = RowState.Todo }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: QuotaExceededException) {
                    tripStopping = true
                    onMain { row.state = RowState.Todo; quotaMsg = e.message }
                } catch (e: Exception) {
                    onMain {
                        row.state = RowState.Failed(errText(e))
                        failed++
                        tripStatus = "Error on ${row.photo.title}: ${errText(e)}"
                        jobProgress((done + failed).toLong(), todo.size.toLong(), "${row.photo.title}: error")
                    }
                }
            }
            rowsNew.forEach { if (it.state is RowState.Progress) it.state = RowState.Todo }
            val results = synchronized(tripResults) { HashMap(tripResults) }
            val folders = SmartFolders.assign(photos, results, HashMap(geoNames), homeCountry)
            val groups = Trips.group(photos, results, folders)
            _trips.value = groups
            // show each photo's final folder (a food photo from a trip lands in that trip)
            rowsNew.forEach { r ->
                val s = r.state
                val f = folders[r.photo.id]
                if (s is RowState.Scanned && f != null) r.state = RowState.Scanned(s.ai.copy(folder = f))
            }
            val scanned = photos.count { results.containsKey(it.id) }
            val missing = photos.size - scanned
            if (tripStopping) title = "Trip scan stopped"
            summary = (quotaMsg?.let { "$it " } ?: "") +
                "Scanned $scanned/${photos.size} photos into ${groups.size} folders." +
                (if (missing > 0) " $missing not scanned yet - press Scan photos again to do just those." else "") +
                (if (tripMove) " Organize will MOVE them (originals get deleted)."
                else " Organize will COPY them (originals stay put).")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            title = "Trip scan failed"
            summary = "Scan failed: ${errText(e)}"
        } finally {
            tripBusy = false
            tripStatus = summary
            endJob(title, summary)
        }
    }

    /** Copies or moves every photo of each group into Pictures/PhotoSorter/<folder>/ (skips already-filed). */
    fun organizeTrips() = scope.launch {
        if (anyBusy) return@launch
        val list = _trips.value
        if (list.isEmpty()) {
            tripStatus = "No groups yet - press Scan photos first."
            return@launch
        }
        if (pendingDelete != null) {
            tripStatus = "Finish the pending delete first (button below)."
            return@launch
        }
        val move = tripMove
        val clusterOf = HashMap<Long, TripCluster>()
        list.forEach { t -> t.photos.forEach { clusterOf[it.id] = t } }
        val todo = list.flatMap { it.photos }.filter { indexGet(it.id) == null }
        if (todo.isEmpty()) {
            tripStatus = "Everything is already filed - nothing to do."
            return@launch
        }
        // copies need room for the whole batch (for a move, originals are deleted only at the end)
        val need = todo.sumOf { it.sizeBytes }
        val free = Organizer.freeBytes()
        if (free >= 0 && free < need + 50L * 1024 * 1024) {
            tripStatus = "Not enough free space for ${humanBytes(need)} of copies (free: ${humanBytes(free)}). " +
                "Free up space, or organize fewer photos."
            return@launch
        }

        tripBusy = true
        tripStopping = false
        val verb = if (move) "Moving" else "Copying"
        beginJob("$verb ${todo.size} photos into folders")
        tripStatus = "$verb ${todo.size} photos into folders (3 at a time)..."
        val rowOf = tripRows.associateBy { it.photo.id }
        val toDelete = Collections.synchronizedList(ArrayList<Photo>())
        var done = 0
        var failed = 0
        var summary = ""
        try {
            jobProgress(0, todo.size.toLong(), "")
            pool(todo, 3, { tripStopping }) { p ->
                val t = clusterOf.getValue(p.id)
                val row = rowOf[p.id]
                try {
                    row?.show(if (move) "copying (move)" else "copying", 0.4f)
                    val rec = withContext(Dispatchers.IO) {
                        val r = Organizer.copyToFolder(ctx, resolver, p, t.folder, t.summary)
                        if (move) {
                            row?.show("verifying copy", 0.8f)
                            Organizer.verifyCopy(resolver, p, r)
                        }
                        r
                    }
                    indexPut(p.id, rec)
                    if (move) toDelete.add(p)
                    onMain {
                        t.filed++
                        row?.state = RowState.Done(rec)
                        done++
                        jobProgress((done + failed).toLong(), todo.size.toLong(), "${p.title} -> ${t.folder}/")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onMain {
                        row?.state = RowState.Failed(errText(e))
                        failed++
                        tripStatus = "Error ${verb.lowercase()} ${p.title}: ${errText(e)}"
                        jobProgress((done + failed).toLong(), todo.size.toLong(), "${p.title}: error")
                    }
                }
            }
        } finally {
            saveIndex(force = true)
            tripRows.forEach { if (it.state is RowState.Progress) it.state = RowState.Todo }
            tripBusy = false
            summary = (if (tripStopping) "Stopped. " else "") +
                "Copied $done photos into ${list.count { it.filed > 0 }} folders under " +
                "Pictures/PhotoSorter/." + (if (failed > 0) " $failed failed (see the list)." else "")
            if (move && toDelete.isNotEmpty()) {
                val photos = ArrayList(toDelete)
                saveMoveIds(photos.map { it.id })
                requestDelete(photos, "move")
                summary += " Last step: confirm the system prompt to delete the ${photos.size} originals " +
                    "(copies are verified)."
            } else if (!move) {
                summary += " Originals were left in place."
            }
            tripStatus = summary
            endJob(if (move && toDelete.isNotEmpty()) "Move: confirm deleting originals" else "Organizing finished", summary)
        }
    }

    fun stopTrips() {
        tripStopping = true
        VlmAi.abort()
        tripStatus = "Stopping - finishing the photos already in progress..."
    }

    // ---- deleting (duplicates + originals of a move) ----

    private fun requestDelete(photos: List<Photo>, purpose: String) {
        pendingDelete = PendingDelete(photos, purpose)
        if (Build.VERSION.SDK_INT >= 30) {
            deleteRequest = try {
                // to the trash, not gone: Android keeps trashed photos for 30 days
                MediaStore.createTrashRequest(resolver, photos.take(DELETE_CHUNK).map { it.uri }, true).intentSender
            } catch (e: Exception) {
                reportDelete(purpose, "Could not ask Android to delete: ${errText(e)}")
                null
            }
        } else {
            // Android 8-10: the app may delete directly (it holds storage permission)
            scope.launch {
                deletePhotos(resolver, photos)
                finishDelete(photos, ok = true)
            }
        }
    }

    /** Re-shows the system delete confirmation for whatever is still pending. */
    fun retryDelete() {
        val pd = pendingDelete ?: return
        requestDelete(pd.photos, pd.purpose)
    }

    fun cancelDelete() {
        val pd = pendingDelete ?: return
        pendingDelete = null
        deleteRequest = null
        if (pd.purpose == "move") {
            saveMoveIds(emptyList())
            tripStatus = "Kept ${pd.photos.size} originals - their copies stay in Pictures/PhotoSorter/ too."
        } else {
            dupStatus = "Delete cancelled - nothing was deleted."
        }
    }

    /** Called by MainActivity with the system dialog's result. */
    fun onDeleteResult(ok: Boolean) {
        deleteRequest = null
        val pd = pendingDelete ?: return
        scope.launch { finishDelete(pd.photos.take(DELETE_CHUNK), ok, rest = pd.photos.drop(DELETE_CHUNK)) }
    }

    private suspend fun finishDelete(chunk: List<Photo>, ok: Boolean, rest: List<Photo> = emptyList()) {
        val pd = pendingDelete ?: return
        val gone = withContext(Dispatchers.IO) { chunk.filter { !repo.exists(it) } }
        val goneIds = gone.mapTo(HashSet()) { it.id }
        val freed = gone.sumOf { it.sizeBytes }
        lastFreed += freed
        if (pd.purpose == "dups") {
            _clusters.value = _clusters.value
                .map { c -> c.copy(photos = c.photos.filter { it.id !in goneIds }) }
                .filter { it.photos.size >= 2 }
            selected = selected - goneIds
        }
        val stillThere = chunk.filter { it.id !in goneIds }
        if (ok && rest.isNotEmpty()) {
            // next chunk of a very large batch
            pendingDelete = PendingDelete(rest, pd.purpose)
            if (pd.purpose == "move") saveMoveIds(rest.map { it.id })
            requestDelete(rest, pd.purpose)
            reportDelete(pd.purpose, "Deleted ${gone.size}, ${rest.size} more to go - confirm the next prompt.")
            return
        }
        val remaining = if (ok) emptyList() else stillThere + rest
        pendingDelete = if (remaining.isEmpty()) null else PendingDelete(remaining, pd.purpose)
        if (pd.purpose == "move") saveMoveIds(remaining.map { it.id })
        val msg = when {
            !ok && gone.isEmpty() ->
                "Not confirmed - nothing was removed. Press the button to try again, or keep both."
            pd.purpose == "move" ->
                "Move finished: ${gone.size} originals went to the trash (kept 30 days, then about " +
                    "${humanBytes(freed)} is freed). Your photos are now in Pictures/PhotoSorter/." +
                    (if (stillThere.isNotEmpty() && ok) " ${stillThere.size} could not be removed." else "")
            else ->
                "Moved ${gone.size} photos to the trash - restorable for 30 days, then about ${humanBytes(freed)} is freed." +
                    (if (_clusters.value.isEmpty()) " All duplicate groups cleared." else "")
        }
        reportDelete(pd.purpose, msg)
        if (!appVisible) Notifications.showResult(ctx, "PhotoSorter", msg)
    }

    private fun reportDelete(purpose: String, msg: String) {
        if (purpose == "move") tripStatus = msg else dupStatus = msg
    }

    /** Originals of an unfinished move are remembered so the move can be finished after a restart. */
    private fun saveMoveIds(ids: List<Long>) {
        sp.edit().putString("pendingMove", ids.joinToString(",")).apply()
    }

    private fun restorePendingMove() {
        val ids = sp.getString("pendingMove", "").orEmpty()
            .split(',').mapNotNull { it.trim().toLongOrNull() }
        if (ids.isEmpty()) return
        scope.launch {
            val photos = runCatching { withContext(Dispatchers.IO) { repo.byIds(ids) } }.getOrDefault(emptyList())
            if (photos.isEmpty()) {
                saveMoveIds(emptyList())
            } else if (pendingDelete == null) {
                pendingDelete = PendingDelete(photos, "move")
                tripStatus = "An earlier move is unfinished: ${photos.size} originals still need deleting " +
                    "(their copies are already in Pictures/PhotoSorter/)."
            }
        }
    }

    // ---- local VLM model download ----

    /** Downloads the ~3.3 GB on-device model files; resumes partial downloads. */
    fun downloadVlmModel() = scope.launch {
        if (anyBusy) return@launch
        if (!VlmAi.nativeAvailable) {
            vlmStatus = "Local AI is not available in this build."
            return@launch
        }
        vlmBusy = true
        downloadStopping = false
        beginJob("Downloading on-device AI model")
        var summary = ""
        try {
            val files = listOf(VlmAi.MODEL_NAME to VlmAi.MODEL_URL, VlmAi.MMPROJ_NAME to VlmAi.MMPROJ_URL)
            val dir = VlmAi.modelDir(ctx)
            val freeBytes = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(-1L)
            if (freeBytes in 0 until VlmAi.DOWNLOAD_BYTES + 200_000_000L) {
                summary = "Not enough free space: the model needs about ${humanBytes(VlmAi.DOWNLOAD_BYTES)} (free: ${humanBytes(freeBytes)})."
                return@launch
            }
            for ((i, f) in files.withIndex()) {
                val (name, url) = f
                val target = File(dir, name)
                if (target.length() > 300_000_000L) continue
                withContext(Dispatchers.IO) { downloadOne(url, target, "(${i + 1}/${files.size})") }
            }
            VlmAi.removeLegacyFiles(ctx)
            summary = "Model ready. ${VlmAi.statusText(ctx)}"
        } catch (e: StoppedException) {
            summary = "Download paused - press Download again to resume where it stopped."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            summary = "Download failed: ${errText(e)}. Press Download again to resume."
        } finally {
            vlmBusy = false
            vlmStatus = summary
            endJob("Model download", summary)
        }
    }

    private suspend fun downloadOne(url: String, target: File, step: String) {
        val name = target.name
        val part = File(target.parentFile, "$name.part")
        val have = part.length()
        val req = okhttp3.Request.Builder().url(url)
            .apply { if (have > 0) header("Range", "bytes=$have-") }
            .build()
        vlmHttp.newCall(req).execute().use { resp ->
            if (resp.code == 416 && have > 0) {
                // already complete
            } else {
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} downloading $name")
                val body = resp.body ?: throw IllegalStateException("Empty response for $name")
                val append = resp.code == 206
                var done = if (append) have else 0L
                val total = if (body.contentLength() > 0) done + body.contentLength() else -1L
                var lastUi = 0L
                body.byteStream().use { input ->
                    java.io.FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            if (downloadStopping) throw StoppedException()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastUi > 500) {
                                lastUi = now
                                val mb = done / 1048576
                                val tot = if (total > 0) " of ${total / 1048576} MB" else " MB"
                                onMain {
                                    vlmStatus = "Downloading $name $step: $mb$tot"
                                    jobProgress(mb, if (total > 0) total / 1048576 else 0, "$name $step: $mb$tot")
                                }
                            }
                        }
                    }
                }
                if (total > 0 && done < total) throw IllegalStateException("connection dropped at ${done / 1048576} MB")
            }
        }
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
    }

    fun stopDownload() {
        downloadStopping = true
        vlmStatus = "Pausing download..."
    }

    // ---- duplicates ----

    fun scanDups() = scope.launch {
        if (anyBusy) return@launch
        savePrefs()
        if (!requirePhotoAccess { dupStatus = it }) return@launch
        dupBusy = true
        dupStopping = false
        beginJob("Scanning for duplicates")
        dupStatus = ""
        try {
            val photos = withContext(Dispatchers.IO) { repo.all() }
            dupStatus = ""
            val found = findDuplicates(resolver, photos, { dupStopping }) { done, total, phase ->
                onMain {
                    val label = if (phase == "hashing") "Step 1 of 2: exact copies"
                    else "Step 2 of 2: comparing looks"
                    dupStatus = ""
                    job = job?.copy(title = "Finding duplicates", done = done.toLong(), total = total.toLong(), detail = label)
                }
            }
            if (dupStopping) {
                dupStatus = "Stopped."
                return@launch
            }
            // exact copies first (safe to clean), then look-alikes (bursts/edits - review)
            _clusters.value = found.sortedBy { if (it.exact) 0 else 1 }
            selected = found.flatMap { c -> c.defaultDelete.map { it.id } }.toSet()
            dupStatus = if (found.isEmpty()) {
                "No duplicates found."
            } else {
                val exact = found.filter { it.exact }
                val alike = found.size - exact.size
                "Found ${exact.size} groups of exact copies (about ${humanBytes(exact.sumOf { it.reclaimableBytes })}; " +
                    "the extra copies are pre-selected and the original is always kept) and $alike groups of " +
                    "look-alikes - bursts, edits, RAW+JPEG - which are NOT selected."
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dupStatus = "Duplicate scan failed: ${errText(e)}"
        } finally {
            dupBusy = false
            endJob(if (dupStopping) "Duplicate scan stopped" else "Duplicate scan finished", dupStatus)
        }
    }

    fun stopDups() {
        dupStopping = true
        dupStatus = "Stopping..."
    }

    /** Look-alike groups: mark all but the newest (never RAW files) for deletion. */
    fun selectLookAlikes() {
        selected = selected + _clusters.value.filter { !it.exact }
            .flatMap { c -> c.photos.drop(1).filter { !it.isRaw }.map { it.id } }
    }

    /** Preview "Keep only this": every other photo of the group is marked for deletion. */
    fun keepOnly(cluster: DupCluster, keepId: Long) {
        selected = selected - keepId + cluster.photos.filter { it.id != keepId }.map { it.id }
    }

    fun clearSelection() {
        selected = emptySet()
    }

    fun toggleSelected(id: Long) {
        val cur = selected.toMutableSet()
        if (id in cur) cur.remove(id) else cur.add(id)
        selected = cur
    }

    fun deleteSelected() {
        if (selected.isEmpty()) {
            dupStatus = "Nothing selected for deletion."
            return
        }
        if (pendingDelete?.purpose == "move") {
            dupStatus = "Finish the pending move in the Trips tab first."
            return
        }
        val toDelete = _clusters.value.flatMap { it.photos }.filter { it.id in selected }.distinctBy { it.id }
        dupStatus = "Deleting ${toDelete.size} photos - confirm the system prompt..."
        requestDelete(toDelete, "dups")
    }

    companion object {
        private const val DELETE_CHUNK = 1000
    }
}

class Row(
    val photo: Photo,
    initial: DoneRec?,
) {
    var state: RowState by mutableStateOf(if (initial != null) RowState.Cached(initial) else RowState.Todo)
}

sealed class RowState {
    data object Todo : RowState()
    /** In progress; [fraction] 0..1 drives the row's progress bar. */
    data class Progress(val what: String, val fraction: Float) : RowState()
    /** Trips: AI answer known, not filed yet. */
    data class Scanned(val ai: AiResult) : RowState()
    data class Done(val rec: DoneRec) : RowState()
    data class Cached(val rec: DoneRec) : RowState()
    data class Failed(val msg: String) : RowState()
}
