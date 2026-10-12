package com.example.photosorter

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(this)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(ctx: Context) {
    val model = remember { AppModel(ctx) }
    val scope = rememberCoroutineScope()
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted -> model.onPermissionsChanged(granted) }

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
                val code = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
                else Manifest.permission.READ_EXTERNAL_STORAGE
                Button(onClick = { permLauncher.launch(arrayOf(code)) }) {
                    Text("Allow photo access")
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { model.tab = "sort" },
                        enabled = !model.busy && !model.dupBusy && !model.tripBusy
                    ) { Text("Sort & describe") }
                    Button(
                        onClick = { model.tab = "trips" },
                        enabled = !model.busy && !model.dupBusy && !model.tripBusy
                    ) { Text("Trips - group by trip") }
                    Button(
                        onClick = { model.tab = "dups" },
                        enabled = !model.busy && !model.dupBusy && !model.tripBusy
                    ) { Text("Duplicates - save storage") }
                }
                when (model.tab) {
                    "dups" -> DupsTab(model)
                    "trips" -> TripsTab(model, scope, ctx)
                    else -> SortTab(model, scope)
                }
            }
        }
    }
}

@Composable
private fun SortTab(model: AppModel, scope: CoroutineScope) {
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("AI mode:", style = MaterialTheme.typography.bodyMedium)
                    if (model.mode == "cloud") Button(onClick = { model.mode = "cloud" }) { Text("Cloud: Gemini") }
                    else OutlinedButton(onClick = { model.mode = "cloud" }) { Text("Cloud: Gemini") }
                    if (model.mode == "local") Button(onClick = { model.mode = "local" }) { Text("On-device: offline") }
                    else OutlinedButton(onClick = { model.mode = "local" }) { Text("On-device: offline") }
                }
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
                        "No key, no internet, no cost, nothing leaves the phone. Each photo is labeled on-device " +
                                "(EfficientNet-Lite0, ~18 MB, bundled with the app) and the caption is built from the " +
                                "detected objects + EXIF date/GPS. Captions are simpler than cloud mode - that is the " +
                                "trade-off for staying offline.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { scope.launch { model.scan() } },
                        enabled = !model.busy
                    ) { Text("Scan library") }
                    Button(
                        onClick = { scope.launch { model.run(scope) } },
                        enabled = !model.busy
                    ) { Text("AI-organize") }
                    Button(onClick = { model.stop() }, enabled = model.busy) { Text("Stop") }
                    Button(onClick = { model.retryFailed() }, enabled = !model.busy) { Text("Retry errors") }
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
private fun TripsTab(model: AppModel, scope: CoroutineScope, ctx: Context) {
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("AI mode:", style = MaterialTheme.typography.bodyMedium)
                        if (model.mode == "cloud") Button(onClick = { model.mode = "cloud" }) { Text("Cloud: Gemini") }
                        else OutlinedButton(onClick = { model.mode = "cloud" }) { Text("Cloud: Gemini") }
                        if (model.mode == "local") Button(onClick = { model.mode = "local" }) { Text("On-device: offline") }
                        else OutlinedButton(onClick = { model.mode = "local" }) { Text("On-device: offline") }
                    }
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
                        Text(VlmAi.statusText(ctx), style = MaterialTheme.typography.bodySmall)
                        if (!VlmAi.isReady(ctx)) {
                            Button(
                                onClick = { scope.launch { model.downloadVlmModel(scope) } },
                                enabled = !model.vlmBusy && !model.tripBusy
                            ) {
                                Text(if (model.vlmBusy) "Downloading..." else "Download local AI model (~3.3 GB)")
                            }
                        }
                        if (model.vlmStatus.isNotEmpty()) {
                            Text(model.vlmStatus, style = MaterialTheme.typography.bodySmall)
                        }
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
                        if (model.tripMove) Button(onClick = { model.tripMove = true }) { Text("MOVE") }
                        else OutlinedButton(onClick = { model.tripMove = true }) { Text("MOVE") }
                        if (!model.tripMove) Button(onClick = { model.tripMove = false }) { Text("COPY") }
                        else OutlinedButton(onClick = { model.tripMove = false }) { Text("COPY") }
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
                            onClick = { confirmMove = false; scope.launch { model.scanTrips(scope) } },
                            enabled = !model.tripBusy
                        ) { Text("AI scan photos") }
                        Button(
                            onClick = {
                                if (model.tripMove && !confirmMove) {
                                    confirmMove = true
                                    return@Button
                                }
                                confirmMove = false
                                scope.launch { model.organizeTrips(scope) }
                            },
                            enabled = !model.tripBusy && model.trips.isNotEmpty()
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
                }
            }
        }
        items(model.trips, key = { it.id }) { trip ->
            TripCard(trip)
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
    val scope = rememberCoroutineScope()
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
                        onClick = { scope.launch { model.scanDups() }; confirmDelete = false },
                        enabled = !model.dupBusy
                    ) { Text("Scan for duplicates") }
                    if (model.lastFreed > 0) {
                        Text(
                            "Freed this session: " + humanBytes(model.lastFreed),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF2E7D32)
                        )
                    }
                }
                Text(model.dupStatus, style = MaterialTheme.typography.bodySmall, maxLines = 5)
                if (model.clusters.isNotEmpty() && model.selected.isNotEmpty()) {
                    Button(
                        onClick = {
                            if (confirmDelete) {
                                confirmDelete = false
                                scope.launch { model.deleteSelected() }
                            } else {
                                confirmDelete = true
                            }
                        },
                        enabled = !model.dupBusy
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
                (if (cluster.exact) "Byte-identical copies" else "Looks identical (re-save / crop)") +
                        " - ${cluster.photos.size} photos - ${humanBytes(cluster.reclaimableBytes)} reclaimable if keeping 1",
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
                    is RowState.Progress -> Text("${s.what}...", color = Color(0xFF1565C0))
                    is RowState.Done -> Text("filed", color = Color(0xFF2E7D32))
                    is RowState.Cached -> Text("already filed", color = Color(0xFF2E7D32))
                    is RowState.Failed -> Text("error", color = MaterialTheme.colorScheme.error)
                }
            }
            Text(
                row.photo.dateLabel +
                        "  \u00B7  ${row.photo.width}x${row.photo.height}" +
                        if (row.photo.latitude != null) "  \u00B7  has GPS" else "" +
                        "  \u00B7  " + humanBytes(row.photo.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            when (s) {
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
