package com.example.photosorter

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = appModel
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(model)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        appModel.appVisible = true
        appModel.refreshPermissions() // the user may have changed access in Settings
        Notifications.cancelResult(this)
    }

    override fun onStop() {
        appModel.appVisible = false
        super.onStop()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(model: AppModel) {
    val ctx = LocalContext.current
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted -> model.onPermissionsChanged(granted) }
    val extraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res -> model.onDeleteResult(res.resultCode == Activity.RESULT_OK) }

    // Users who granted photo access with an older version get asked for the newer
    // permissions (photo locations, notifications) once per launch.
    LaunchedEffect(model.permissions) {
        if (!model.permissions) return@LaunchedEffect
        val missing = buildList {
            if (model.missingLocation) add(Manifest.permission.ACCESS_MEDIA_LOCATION)
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) extraLauncher.launch(missing.toTypedArray())
    }
    // System "delete these photos?" confirmation - shown as soon as the app is visible.
    val req = model.deleteRequest
    LaunchedEffect(req, model.appVisible) {
        if (req != null && model.appVisible) {
            model.deleteRequest = null
            runCatching { deleteLauncher.launch(IntentSenderRequest.Builder(req).build()) }
                .onFailure { model.onDeleteResult(false) }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("PhotoSorter") }) }) { pad ->
        if (!model.permissions) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp).padding(pad),
                verticalArrangement = Arrangement.Center
            ) {
                Text("PhotoSorter", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "This app AI-scans your photos and files them into folders like japan-trip or swiss-trip under " +
                            "Pictures/PhotoSorter/. You choose COPY (originals stay) or MOVE (originals are deleted, " +
                            "only after a verified copy - and only when you confirm it). It also finds duplicates you " +
                            "can safely delete. Nothing is ever deleted without your explicit action. " +
                            "It needs access to your photo library."
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { permLauncher.launch(model.permissionsToRequest()) }) {
                    Text("Allow photo access")
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TabButton("Sort & describe", model.tab == "sort") { model.tab = "sort" }
                    TabButton("Trips - group by trip", model.tab == "trips") { model.tab = "trips" }
                    TabButton("Duplicates - save storage", model.tab == "dups") { model.tab = "dups" }
                }
                if (model.partialAccess) {
                    Text(
                        "Limited access: PhotoSorter only sees the photos you selected. For a full sort, allow all photos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    OutlinedButton(onClick = { permLauncher.launch(model.permissionsToRequest()) }) {
                        Text("Change photo access")
                    }
                }
                JobBar(model)
                when (model.tab) {
                    "dups" -> DupsTab(model)
                    "trips" -> TripsTab(model)
                    else -> SortTab(model)
                }
            }
        }
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick) { Text(label) }
    else OutlinedButton(onClick = onClick) { Text(label) }
}

/** Overall progress of the running job (also mirrored in the notification). */
@Composable
private fun JobBar(model: AppModel) {
    val job = model.job ?: return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(job.title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            val f = job.fraction
            if (f != null) Text("${(f * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
        }
        val f = job.fraction
        if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            (if (job.total in 1 until 1_000_000 && !job.title.startsWith("Downloading")) "${job.done}/${job.total}  " else "") +
                job.detail.ifEmpty { "Keeps running if you leave the app - see the notification." },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ModeChooser(model: AppModel) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("AI mode:", style = MaterialTheme.typography.bodyMedium)
        TabButton("Cloud: Gemini", model.mode == "cloud") { if (!model.anyBusy) model.mode = "cloud" }
        TabButton("On-device", model.mode == "local") { if (!model.anyBusy) model.mode = "local" }
    }
}

@Composable
private fun LocalModelPanel(model: AppModel) {
    val ctx = LocalContext.current
    Text(VlmAi.statusText(ctx), style = MaterialTheme.typography.bodySmall)
    if (!VlmAi.isReady(ctx)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { model.downloadVlmModel() },
                enabled = !model.anyBusy
            ) {
                Text(if (model.vlmBusy) "Downloading..." else "Download local AI model (~3.3 GB)")
            }
            if (model.vlmBusy) OutlinedButton(onClick = { model.stopDownload() }) { Text("Pause") }
        }
    }
    if (model.vlmStatus.isNotEmpty()) {
        Text(model.vlmStatus, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SortTab(model: AppModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "controls") {
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModeChooser(model)
                if (model.mode == "cloud") {
                    OutlinedTextField(
                        value = model.apiKey,
                        onValueChange = { model.apiKey = it },
                        label = { Text("Gemini API key (free at aistudio.google.com)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = model.modelName,
                            onValueChange = { model.modelName = it },
                            label = { Text("Model") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = model.limitText,
                            onValueChange = { model.limitText = it.filter(Char::isDigit) },
                            label = { Text("N") },
                            singleLine = true,
                            modifier = Modifier.width(80.dp)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = model.useWebSearch,
                            onCheckedChange = { model.useWebSearch = it }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Verify details with live web search (identifies landmarks, products, events)",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Text(
                        "No key, no internet, no cost, nothing leaves the phone. With the on-device AI model " +
                                "(Qwen2.5-VL) each photo gets a real caption in ~10-30 s; without it, the bundled " +
                                "labeler (EfficientNet-Lite0) gives simple object-based captions.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    LocalModelPanel(model)
                    OutlinedTextField(
                        value = model.limitText,
                        onValueChange = { model.limitText = it.filter(Char::isDigit) },
                        label = { Text("N") },
                        singleLine = true,
                        modifier = Modifier.width(80.dp)
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { model.scan() },
                        enabled = !model.anyBusy
                    ) { Text("Scan library") }
                    Button(
                        onClick = { model.runSort() },
                        enabled = !model.anyBusy
                    ) { Text("AI-organize") }
                    Button(onClick = { model.stop() }, enabled = model.busy) { Text("Stop") }
                    Button(onClick = { model.retryFailed() }, enabled = !model.anyBusy) { Text("Retry errors") }
                }
                Text(model.status, style = MaterialTheme.typography.bodySmall, maxLines = 8)
                Text(
                    "Files COPIES only - originals are untouched. N = how many newest photos to load. " +
                            "Copies go to Pictures/PhotoSorter/<folder>/ with a .description.txt side file. " +
                            "Already-filed photos are skipped (saved in app index, no extra API cost).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        }
        items(model.rows, key = { it.photo.id }) { row ->
            RowView(row, onOpen = { model.openCopy(it.outPath) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TripsTab(model: AppModel) {
    var confirmMove by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "controls") {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "The AI scans EACH photo (image + date + GPS location) and files it into a folder like " +
                                "japan-trip or swiss-trip; non-travel photos get category folders (food, screenshots...). " +
                                "~1 API call per photo - the free Gemini tier is limited (~250/day), so run it in " +
                                "batches: already-filed photos are skipped on re-runs.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    ModeChooser(model)
                    if (model.mode == "cloud") {
                        OutlinedTextField(
                            value = model.apiKey,
                            onValueChange = { model.apiKey = it },
                            label = { Text("Gemini API key (free at aistudio.google.com)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = model.useWebSearch,
                                onCheckedChange = { model.useWebSearch = it }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Verify places with live web search (better landmark/country ID)",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    } else {
                        Text(
                            "On-device mode runs a small AI model (Qwen2.5-VL, ~3.3 GB) directly on your phone - " +
                                    "no key, no internet, nothing leaves the device. Each photo takes roughly 10-30 s to scan.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        LocalModelPanel(model)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = model.tripLimitText,
                            onValueChange = { model.tripLimitText = it.filter(Char::isDigit) },
                            label = { Text("Max photos") },
                            singleLine = true,
                            modifier = Modifier.width(110.dp)
                        )
                        Text(
                            "How many newest photos to scan this run (already-filed ones are skipped).",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("File by:", style = MaterialTheme.typography.bodyMedium)
                        TabButton("COPY", !model.tripMove) { if (!model.tripBusy) model.tripMove = false }
                        TabButton("MOVE", model.tripMove) { if (!model.tripBusy) model.tripMove = true }
                        Text(
                            if (model.tripMove) "deletes originals after a verified copy"
                            else "originals stay where they are",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (model.tripMove) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { confirmMove = false; model.scanTrips() },
                            enabled = !model.anyBusy
                        ) { Text("AI scan photos") }
                        Button(
                            onClick = {
                                if (model.tripMove && !confirmMove) {
                                    confirmMove = true
                                    return@Button
                                }
                                confirmMove = false
                                model.organizeTrips()
                            },
                            enabled = !model.anyBusy && model.trips.isNotEmpty() && model.pendingDelete == null
                        ) {
                            Text(
                                when {
                                    model.tripMove && confirmMove -> "Press again to MOVE (originals deleted)"
                                    model.tripMove -> "Move into folders"
                                    else -> "Copy into folders"
                                }
                            )
                        }
                        Button(onClick = { model.stopTrips() }, enabled = model.tripBusy) { Text("Stop") }
                    }
                    Text(model.tripStatus, style = MaterialTheme.typography.bodySmall, maxLines = 8)
                    val pd = model.pendingDelete
                    if (pd != null && pd.purpose == "move") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { model.retryDelete() }, enabled = !model.anyBusy) {
                                Text("Finish move: delete ${pd.photos.size} originals")
                            }
                            OutlinedButton(onClick = { model.cancelDelete() }) { Text("Keep both") }
                        }
                    }
                }
            }
        }
        items(model.trips, key = { "t" + it.id }) { trip ->
            TripCard(trip)
        }
        if (model.tripRows.isNotEmpty()) {
            item(key = "rowsHeader") {
                val r = model.tripRows
                Text(
                    "Per photo (${r.size}): " +
                        "${r.count { it.state is RowState.Scanned || it.state is RowState.Done }} done, " +
                        "${r.count { it.state is RowState.Failed }} failed",
                    style = MaterialTheme.typography.titleSmall
                )
            }
            items(model.tripRows, key = { "r" + it.photo.id }) { row ->
                RowView(row, onOpen = { model.openCopy(it.outPath) })
            }
        }
    }
}

@Composable
private fun TripCard(trip: TripCluster) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    trip.folder.ifEmpty { "unnamed" },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                if (trip.photos.isNotEmpty() && trip.filed > 0) {
                    Text(
                        if (trip.filed >= trip.photos.size) "filed" else "filed ${trip.filed}/${trip.photos.size}",
                        color = Color(0xFF2E7D32),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Text(
                "${trip.photos.size} photos  \u00B7  ${trip.dateRangeLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (trip.summary.isNotEmpty()) {
                Text(trip.summary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun DupsTab(model: AppModel) {
    var confirmDelete by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "controls") {
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Scan uses only the phone: first byte-exact matching (SHA-256), then a visual (perceptual) " +
                            "pass that catches re-saves, crops and re-edits. The newest copy in each group is kept " +
                            "by default. Nothing is deleted until you press Delete, and your phone's own " +
                            "system will confirm deletions too.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { model.scanDups(); confirmDelete = false },
                        enabled = !model.anyBusy
                    ) { Text("Scan for duplicates") }
                    if (model.dupBusy) OutlinedButton(onClick = { model.stopDups() }) { Text("Stop") }
                    if (model.lastFreed > 0) {
                        Text(
                            "Freed this session: " + humanBytes(model.lastFreed),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF2E7D32)
                        )
                    }
                }
                Text(model.dupStatus, style = MaterialTheme.typography.bodySmall, maxLines = 6)
                if (model.clusters.isNotEmpty() && !model.dupBusy) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (model.clusters.any { !it.exact }) {
                            OutlinedButton(onClick = { model.selectLookAlikes(); confirmDelete = false }) {
                                Text("Select look-alikes too")
                            }
                        }
                        OutlinedButton(onClick = { model.clearSelection(); confirmDelete = false }) {
                            Text("Clear selection")
                        }
                    }
                }
                if (model.clusters.isNotEmpty() && model.selected.isNotEmpty()) {
                    Button(
                        onClick = {
                            if (confirmDelete) {
                                confirmDelete = false
                                model.deleteSelected()
                            } else {
                                confirmDelete = true
                            }
                        },
                        enabled = !model.anyBusy && model.pendingDelete == null
                    ) {
                        Text(
                            if (confirmDelete) "Press again to delete ${model.selected.size} photos (stays selected until you do)"
                            else "Delete selected (${model.selected.size} - about ${humanBytes(model.selectedBytes)})"
                        )
                    }
                }
            }
        }
        }
        items(model.clusters, key = { "c" + it.key }) { cluster ->
            ClusterCard(cluster, model.selected, onToggle = { model.toggleSelected(it) })
        }
    }
}

@Composable
private fun ClusterCard(cluster: DupCluster, selected: Set<Long>, onToggle: (Long) -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                when {
                    cluster.exact -> "Exact copies"
                    cluster.hasRaw -> "RAW + JPEG of the same shot - review"
                    else -> "Look-alikes (burst / edit / re-save) - review"
                } + " - ${cluster.photos.size} photos - ${humanBytes(cluster.reclaimableBytes)} reclaimable if keeping 1",
                style = MaterialTheme.typography.titleSmall
            )
            cluster.photos.forEach { p ->
                val sel = p.id in selected
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { onToggle(p.id) }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (sel) "[DELETE] " else "[KEEP]   ",
                        color = if (sel) MaterialTheme.colorScheme.error else Color(0xFF2E7D32),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        p.title + "  -  ${p.dateLabel}  -  ${humanBytes(p.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
fun RowView(row: Row, onOpen: (DoneRec) -> Unit) {
    val s = row.state
    val rec = when (s) {
        is RowState.Done -> s.rec
        is RowState.Cached -> s.rec
        else -> null
    }
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().then(
            if (rec != null) Modifier.clickable { onOpen(rec) } else Modifier
        )
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.photo.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                when (s) {
                    is RowState.Todo -> Text("queued", color = MaterialTheme.colorScheme.outline)
                    is RowState.Progress -> Text("${(s.fraction * 100).toInt()}%", color = Color(0xFF1565C0))
                    is RowState.Scanned -> Text("scanned", color = Color(0xFF1565C0))
                    is RowState.Done -> Text("filed", color = Color(0xFF2E7D32))
                    is RowState.Cached -> Text("already filed", color = Color(0xFF2E7D32))
                    is RowState.Failed -> Text("error", color = MaterialTheme.colorScheme.error)
                }
            }
            Text(
                row.photo.dateLabel +
                        "  \u00B7  ${row.photo.width}x${row.photo.height}" +
                        (if (row.photo.latitude != null) "  \u00B7  has GPS" else "") +
                        "  \u00B7  " + humanBytes(row.photo.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            when (s) {
                is RowState.Progress -> {
                    LinearProgressIndicator(progress = { s.fraction }, modifier = Modifier.fillMaxWidth())
                    Text("${s.what}...", style = MaterialTheme.typography.bodySmall, color = Color(0xFF1565C0))
                }
                is RowState.Scanned ->
                    Text("-> ${s.ai.folder}/  \u2014  ${s.ai.description}", style = MaterialTheme.typography.bodyMedium)
                is RowState.Done ->
                    Text("-> ${s.rec.folder}/  \u2014  ${s.rec.description}", style = MaterialTheme.typography.bodyMedium)
                is RowState.Cached ->
                    Text("-> ${s.rec.folder}/  \u2014  ${s.rec.description} (tap to open the copy)",
                        style = MaterialTheme.typography.bodyMedium)
                is RowState.Failed ->
                    Text(s.msg, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error, maxLines = 3)
                else -> Unit
            }
        }
    }
}
