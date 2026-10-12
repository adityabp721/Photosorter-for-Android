package com.example.photosorter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

class AppModel(private val ctx: Context) {

    private val resolver = ctx.contentResolver
    private val repo = PhotoRepo(resolver)
    private val sp = ctx.getSharedPreferences("photosorter", Context.MODE_PRIVATE)
    private val index = Organizer.loadIndex(ctx)

    // ---- general ----

    var tab: String by mutableStateOf("sort")                       // "sort" | "dups"
    var mode: String by mutableStateOf(sp.getString("mode", "cloud") ?: "cloud") // "cloud" | "local"
    var apiKey: String by mutableStateOf(sp.getString("key", "") ?: "")
    var modelName: String by mutableStateOf(sp.getString("model", Gemini.DEFAULT_MODEL) ?: Gemini.DEFAULT_MODEL)
    var limitText: String by mutableStateOf("50")
    var useWebSearch: Boolean by mutableStateOf(sp.getBoolean("search", true))
    var status: String by mutableStateOf(
        "Choose cloud or on-device AI, scan the library, then press AI-organize. " +
            "On-device mode needs no key and works offline."
    )
    var busy: Boolean by mutableStateOf(false)
    var permissions: Boolean by mutableStateOf(permissionGranted())

    private val _rows = mutableStateOf<List<Row>>(emptyList())
    val rows: List<Row> get() = _rows.value
    private val rowsLock = Any()

    @Volatile private var stopping = false

    // ---- trips (group gallery into per-trip folders like "japan-trip") ----

    var tripBusy: Boolean by mutableStateOf(false)
    var tripStatus: String by mutableStateOf(
        "The AI scans EACH photo (image + date + GPS) and files it into a folder like japan-trip or " +
            "swiss-trip; non-travel photos get category folders. Then Organize moves (or copies) the " +
            "photos into Pictures/PhotoSorter/<folder>/. Move mode deletes the originals after a " +
            "verified copy."
    )
    var tripLimitText: String by mutableStateOf("200")
    var tripMove: Boolean by mutableStateOf(sp.getBoolean("tripmove", true))
    private val _trips = mutableStateOf<List<TripCluster>>(emptyList())
    val trips: List<TripCluster> get() = _trips.value

    @Volatile private var tripStopping = false

    // ---- duplicates ----

    var dupBusy: Boolean by mutableStateOf(false)
    var dupStatus: String by mutableStateOf(
        "Scanning checks ALL photos for exact and near-identical copies (re-saves, crops). Nothing is deleted until you press Delete."
    )
    var lastFreed: Long by mutableStateOf(0L)
    private val _clusters = mutableStateOf<List<DupCluster>>(emptyList())
    val clusters: List<DupCluster> get() = _clusters.value
    var selected: Set<Long> by mutableStateOf(emptySet())

    val selectedBytes: Long
        get() = _clusters.value.flatMap { it.photos }.filter { it.id in selected }.sumOf { it.sizeBytes }

    private fun permissionGranted(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(ctx, needed) == PackageManager.PERMISSION_GRANTED
    }

    fun onPermissionsChanged(granted: Map<String, Boolean>) {
        permissions = granted.values.all { it }
    }

    fun savePrefs() {
        sp.edit()
            .putString("key", apiKey)
            .putString("model", modelName)
            .putString("mode", mode)
            .putBoolean("search", useWebSearch)
            .putBoolean("tripmove", tripMove)
            .apply()
    }

    private suspend fun onMain(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    // ---- sort & file ----

    private fun requirePhotoAccess(): Boolean {
        if (permissionGranted()) return true
        permissions = false
        status = "Photo access is not allowed. Press \"Allow photo access\" below, choose ALL photos " +
            "(not just selected ones), then try again."
        return false
    }

    suspend fun scan() {
        savePrefs()
        if (!requirePhotoAccess()) return
        onMain { status = "Scanning photo library..." }
        val n = limitText.toIntOrNull()?.coerceIn(10, 500) ?: 50
        val photos = withContext(Dispatchers.IO) {
            repo.recent(n).map { p ->
                val g = ImageTools.gpsFor(resolver, p.uri)
                if (g != null) p.copy(latitude = g[0], longitude = g[1]) else p
            }
        }
        val newRows = photos.map { p -> Row(p, index[p.id]) }
        onMain {
            synchronized(rowsLock) { _rows.value = newRows }
            val cached = newRows.count { it.state is RowState.Cached }
            status = "Loaded ${newRows.size} photos (latest first). $cached already filed, will be skipped."
        }
    }

    suspend fun run(scope: CoroutineScope) {
        savePrefs()
        if (!permissionGranted()) {
            permissions = false
            return
        }
        val useLocal = mode == "local"
        if (!useLocal) {
            if (apiKey.isBlank()) {
                onMain { status = "Enter your Gemini API key first (free at aistudio.google.com), or switch to on-device mode." }
                return
            }
            if (modelName.isBlank()) {
                onMain { status = "Enter a model name." }
                return
            }
        }
        val snapshot = synchronized(rowsLock) { rows.toMutableList() }
        val todo = snapshot.filter { it.state is RowState.Todo }
        if (todo.isEmpty()) {
            onMain { status = "Nothing to file - scan first, or already-filed ones are skipped." }
            return
        }
        busy = true
        stopping = false
        status = "Filing ${todo.size} photos (${if (useLocal) "on-device" else "cloud"}, up to 3 at a time)..."
        val sem = Semaphore(3)
        var filed = 0
        val jobs = todo.map { row ->
            scope.launch(Dispatchers.Default) {
                if (stopping) return@launch
                sem.withPermit {
                    if (stopping) return@withPermit
                    onMain { row.state = if (useLocal) RowState.Progress("labeling on-device") else RowState.Progress("shrinking photo") }
                    try {
                        val ai = if (useLocal) {
                            val bmp = ImageTools.decodeSmall(resolver, row.photo.uri, 256)
                            val r = LocalAi.describeImage(ctx, bmp, row.photo.dateLabel, row.photo.latitude != null)
                            bmp.recycle()
                            r
                        } else {
                            val b64 = ImageTools.toBase64Jpeg(resolver, row.photo.uri)
                            if (stopping) return@withPermit
                            onMain {
                                row.state = if (useWebSearch) RowState.Progress("asking AI + live search")
                                else RowState.Progress("asking AI")
                            }
                            Gemini.describe(apiKey.trim(), modelName.trim(), b64, buildHints(row.photo), useWebSearch)
                        }
                        if (stopping) return@withPermit
                        onMain { row.state = RowState.Progress("filing into folder") }
                        val rec = Organizer.copyAndDescribe(ctx, resolver, row.photo, ai)
                        synchronized(rowsLock) { index[row.photo.id] = rec }
                        Organizer.saveIndex(ctx, index)
                        onMain {
                            row.state = RowState.Done(rec)
                            filed++
                            status = "Filed $filed/${todo.size} -> ${rec.folder}/ - ${rec.description}"
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: SecurityException) {
                        onMain {
                            row.state = RowState.Failed("Android blocked access to this photo")
                            status = "Photo access not allowed for some images. Open Settings > Apps > " +
                                "PhotoSorter > Photos & videos and choose \"All photos\" (not just selected), " +
                                "then press Retry errors."
                        }
                    } catch (e: Exception) {
                        onMain {
                            row.state = RowState.Failed(e.message ?: e.javaClass.simpleName)
                            status = "Last error: ${e.message ?: e.javaClass.simpleName}"
                        }
                    }
                }
            }
        }
        jobs.forEach { it.join() }
        busy = false
        val failed = rows.count { it.state is RowState.Failed }
        status = if (stopping) "Stopped - press AI-organize again to continue with the rest." else
            "Finished. $failed failed - retry them below."
    }

    fun stop() {
        stopping = true
        status = "Stopping - waiting for in-flight photos..."
    }

    fun retryFailed() {
        synchronized(rowsLock) {
            _rows.value.forEach { if (it.state is RowState.Failed) it.state = RowState.Todo }
        }
        status = "Failed photos reset to queued. Press AI-organize to retry."
    }

    fun openCopy(outPath: String) {
        Organizer.openCopy(ctx, outPath)
    }

    private fun buildHints(p: Photo): String {
        val sb = StringBuilder()
        if (p.dateTakenMs > 0) sb.append("Taken: ${p.dateLabel}. ")
        if (p.latitude != null && p.longitude != null) {
            sb.append("GPS: ").append(String.format(java.util.Locale.US, "%.5f, %.5f", p.latitude, p.longitude)).append(". ")
        }
        sb.append("Pixel size: ${p.width}x${p.height}.")
        return sb.toString()
    }

    // ---- trips: AI-scan each photo, file into per-trip folders ----

    /**
     * Reads the library (newest first), then AI-scans every not-yet-filed photo: the model sees
     * the image + EXIF date/GPS and returns a folder like "japan-trip" or a category for
     * non-travel photos. Results are grouped into folders for review/organizing.
     */
    suspend fun scanTrips(scope: CoroutineScope) {
        savePrefs()
        if (!requirePhotoAccess()) return
        val useLocal = mode == "local"
        if (!useLocal && apiKey.isBlank()) {
            onMain { tripStatus = "Enter your Gemini API key first (free at aistudio.google.com), or switch to on-device mode." }
            return
        }
        tripBusy = true
        tripStopping = false
        _trips.value = emptyList()

        val limit = tripLimitText.toIntOrNull()?.coerceIn(10, 20000) ?: 200
        onMain { tripStatus = "Reading photo library (up to $limit newest)..." }
        val base = withContext(Dispatchers.IO) { repo.all(limit) }

        // Read EXIF GPS per photo - sent to the AI as a location hint (big accuracy boost).
        val photos = ArrayList<Photo>(base.size)
        var i = 0
        for (p in base) {
            if (tripStopping) break
            val g = withContext(Dispatchers.IO) { ImageTools.gpsFor(resolver, p.uri) }
            photos.add(if (g != null) p.copy(latitude = g[0], longitude = g[1]) else p)
            i++
            if (i % 200 == 0) onMain { tripStatus = "Reading location data: $i/${base.size}" }
        }
        if (tripStopping) {
            tripBusy = false
            onMain { tripStatus = "Stopped." }
            return
        }

        // Already-filed photos are skipped - run this in batches to stay within API quotas.
        val todo = photos.filter { index[it.id] == null }
        if (todo.isEmpty()) {
            tripBusy = false
            onMain { tripStatus = "All $limit newest photos are already filed - nothing to scan." }
            return
        }
        // Local mode: use the on-device VLM when its model files are present;
        // otherwise fall back to the basic content labeler (no place names).
        val vlmOk = if (useLocal) {
            VlmAi.ensureLoaded(ctx) { tripStatus = it } // snapshot-state write, safe off-main
        } else false
        if (useLocal && !vlmOk) {
            onMain { tripStatus = "Local AI model not loaded - falling back to basic content categories (no place names)." }
        }

        // The VLM runs one photo at a time in a single native context.
        val sem = Semaphore(if (useLocal && vlmOk) 1 else 3)
        onMain {
            tripStatus = "AI-scanning ${todo.size} photos (${if (useLocal) if (vlmOk) "on-device VLM" else "on-device labels" else "cloud"}, " +
                "this can take a while - you can Stop anytime."
        }

        val results = HashMap<Long, AiResult>()
        var doneCount = 0
        val jobs = todo.map { p ->
            scope.launch(Dispatchers.Default) {
                sem.withPermit {
                    if (tripStopping) return@withPermit
                    try {
                        val ai = when {
                            useLocal && vlmOk -> {
                                val bytes = ImageTools.toJpegBytes(resolver, p.uri, 768)
                                if (tripStopping) return@withPermit
                                val raw = VlmAi.classify(bytes, buildHints(p))
                                    ?: throw IllegalStateException("Local AI returned no answer")
                                VlmAi.parseOutput(raw)
                                    ?: throw IllegalStateException("Could not parse local AI answer: ${raw.take(80)}")
                            }
                            useLocal -> {
                                val bmp = ImageTools.decodeSmall(resolver, p.uri, 256)
                                val r = LocalAi.describeImage(ctx, bmp, p.dateLabel, p.latitude != null)
                                bmp.recycle()
                                r
                            }
                            else -> {
                                val b64 = ImageTools.toBase64Jpeg(resolver, p.uri)
                                if (tripStopping) return@withPermit
                                Gemini.classifyTrip(apiKey.trim(), modelName.trim(), b64, buildHints(p), useWebSearch)
                            }
                        }
                        synchronized(results) {
                            results[p.id] = ai
                            doneCount++
                        }
                        onMain { tripStatus = "AI scan: $doneCount/${todo.size} -> ${ai.folder}" }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        onMain { tripStatus = "Error on ${p.title}: ${e.message ?: e.javaClass.simpleName}" }
                    }
                }
            }
        }
        jobs.forEach { it.join() }

        val groups = Trips.group(todo, results)
        tripBusy = false
        onMain {
            _trips.value = groups
            val failed = todo.size - results.size
            tripStatus = "Scanned ${results.size}/${todo.size} photos into ${groups.size} folders." +
                if (failed > 0) " $failed failed - press AI scan again to retry just those." else "" +
                if (tripMove) " Organize will MOVE them (originals get deleted)."
                else " Organize will COPY them (originals stay put)."
        }
    }

    /** Copies or moves every photo of each group into Pictures/PhotoSorter/<folder>/ (skips already-filed). */
    suspend fun organizeTrips(scope: CoroutineScope) {
        val list = _trips.value
        if (list.isEmpty()) {
            onMain { tripStatus = "No groups yet - press AI scan first." }
            return
        }
        for (t in list) t.filed = 0
        val todoAll = list.flatMap { it.photos }.filter { index[it.id] == null }
        if (todoAll.isEmpty()) {
            onMain { tripStatus = "Everything is already filed - nothing to do." }
            return
        }

        // Moving needs enough free space for the full batch (copy exists briefly before the original is deleted).
        if (tripMove) {
            val need = todoAll.sumOf { it.sizeBytes }
            val free = Organizer.freeBytes()
            if (free >= 0 && free < need + 50L * 1024 * 1024) {
                onMain {
                    tripStatus = "Not enough free space to move ${humanBytes(need)} (free: ${humanBytes(free)}). " +
                        "Free up space, or switch to Copy."
                }
                return
            }
        }

        tripBusy = true
        tripStopping = false
        onMain {
            tripStatus = if (tripMove) "Moving ${todoAll.size} photos into folders - originals will be deleted (3 at a time)..."
            else "Copying ${todoAll.size} photos into folders (3 at a time)..."
        }

        val sem = Semaphore(3)
        for (t in list) {
            if (tripStopping) break
            val todo = t.photos.filter { index[it.id] == null }
            if (todo.isEmpty()) continue
            onMain { tripStatus = "${if (tripMove) "Moving" else "Copying"} ${t.folder}/ (${t.photos.size} photos)..." }
            val jobs = todo.map { p ->
                scope.launch(Dispatchers.Default) {
                    sem.withPermit {
                        if (tripStopping) return@withPermit
                        try {
                            val rec = if (tripMove) Organizer.moveToFolder(ctx, resolver, p, t.folder, t.summary)
                                      else Organizer.copyToFolder(ctx, resolver, p, t.folder, t.summary)
                            synchronized(index) { index[p.id] = rec }
                            Organizer.saveIndex(ctx, index)
                            onMain { t.filed++ }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            onMain { tripStatus = "Error ${if (tripMove) "moving" else "copying"} ${p.title}: ${e.message ?: e.javaClass.simpleName}" }
                        }
                    }
                }
            }
            jobs.forEach { it.join() }
        }

        tripBusy = false
        val done = list.sumOf { it.filed }
        onMain {
            tripStatus = when {
                tripStopping -> "Stopped - press Organize again to continue."
                tripMove -> "Done. Moved $done photos into ${list.size} folders under Pictures/PhotoSorter/. Originals were deleted."
                else -> "Done. Copied $done photos into ${list.size} folders under Pictures/PhotoSorter/. Open your Photos app to browse them."
            }
        }
    }

    fun stopTrips() {
        tripStopping = true
        tripStatus = "Stopping - waiting for in-flight work..."
    }

    // ---- local VLM model download ----

    var vlmBusy: Boolean by mutableStateOf(false)
    var vlmStatus: String by mutableStateOf("")

    private val vlmHttp = okhttp3.OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /** Downloads the ~3.3 GB on-device model files (skips files that already exist). */
    suspend fun downloadVlmModel(scope: CoroutineScope) {
        if (!VlmAi.nativeAvailable) {
            onMain { vlmStatus = "Local AI is not available in this build." }
            return
        }
        if (vlmBusy) return
        vlmBusy = true
        try {
            val files = listOf(
                VlmAi.MODEL_NAME to VlmAi.MODEL_URL,
                VlmAi.MMPROJ_NAME to VlmAi.MMPROJ_URL,
            )
            for ((name, url) in files) {
                val target = File(VlmAi.modelDir(ctx), name)
                if (target.length() > 100_000_000L) continue
                onMain { vlmStatus = "Downloading $name ..." }
                withContext(Dispatchers.IO) {
                    val req = okhttp3.Request.Builder().url(url).build()
                    vlmHttp.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} downloading $name")
                        val body = resp.body ?: throw IllegalStateException("Empty response for $name")
                        val total = body.contentLength()
                        val part = File(target.parentFile, name + ".part")
                        body.byteStream().use { input ->
                            part.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                var done = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    if (done % (8L * 1024 * 1024) < 65536) {
                                        val mb = done / 1048576
                                        val tot = if (total > 0) " of ${total / 1048576} MB" else ""
                                        withContext(Dispatchers.Main) { vlmStatus = "Downloading $name: $mb MB$tot" }
                                    }
                                }
                            }
                        }
                        if (!part.renameTo(target)) {
                            part.copyTo(target, overwrite = true)
                            part.delete()
                        }
                    }
                }
            }
            onMain { vlmStatus = "Model ready. ${VlmAi.statusText(ctx)}" }
        } catch (e: Exception) {
            onMain { vlmStatus = "Download failed: ${e.message ?: e.javaClass.simpleName}" }
        }
        vlmBusy = false
    }

    // ---- duplicates ----

    suspend fun scanDups() {
        savePrefs()
        dupBusy = true
        dupStatus = "Reading photo library..."
        try {
            val photos = withContext(Dispatchers.IO) { repo.all() }
            dupStatus = "Hashing ${photos.size} photos - byte-identical pass first, then near-duplicate pass. Off-device, free, may take a few minutes."
            val found = findDuplicates(resolver, photos) { done, total, phase ->
                withContext(Dispatchers.Main) {
                    val label = if (phase == "hashing") "Scanning for byte-identical copies"
                    else "Comparing looks (near-duplicates)"
                    dupStatus = "$label: $done/$total"
                }
            }
            _clusters.value = found
            selected = found.flatMap { c -> c.photos.drop(1).map { it.id } }.toSet()
            if (found.isEmpty()) {
                dupStatus = "No duplicates found."
            } else {
                val reclaimable = found.flatMap { c -> c.photos.drop(1) }.sumOf { it.sizeBytes }
                dupStatus = "Found ${found.size} duplicate groups; about ${humanBytes(reclaimable)} reclaimable if you keep the newest of each. Tap photos to switch keep/delete, then press Delete selected."
            }
        } catch (e: Exception) {
            dupStatus = "Duplicate scan failed: ${e.message ?: e.javaClass.simpleName}"
        }
        dupBusy = false
    }

    fun toggleSelected(id: Long) {
        val cur = selected.toMutableSet()
        if (id in cur) cur.remove(id) else cur.add(id)
        selected = cur
    }

    suspend fun deleteSelected() {
        if (selected.isEmpty()) {
            dupStatus = "Nothing selected for deletion."
            return
        }
        dupBusy = true
        dupStatus = "Deleting ${selected.size} files (the system may show its own confirmations)..."
        val toDelete = _clusters.value.flatMap { it.photos }.filter { it.id in selected }
        val (ok, freed) = deletePhotos(resolver, toDelete)
        _clusters.value = _clusters.value
            .map { c -> c.copy(photos = c.photos.filter { it.id !in selected }) }
            .filter { it.photos.size >= 2 }
        selected = emptySet()
        lastFreed += freed
        dupStatus = "Deleted $ok/${toDelete.size} photos, about ${humanBytes(freed)} freed." +
            if (_clusters.value.isEmpty()) " All duplicate groups cleared." else ""
        dupBusy = false
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
    data class Progress(val what: String) : RowState()
    data class Done(val rec: DoneRec) : RowState()
    data class Cached(val rec: DoneRec) : RowState()
    data class Failed(val msg: String) : RowState()
}
