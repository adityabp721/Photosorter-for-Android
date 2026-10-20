package com.adityabalagere.photosorter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.Luggage
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Park
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.Celebration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable

private val ListPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)

@Composable
private fun ButtonIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(icon, null, Modifier.size(18.dp))
    Spacer(Modifier.width(6.dp))
}

// ======================================================================== Sort

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SortScreen(model: AppModel, openSettings: () -> Unit) {
    val rows = model.rows
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "controls") {
            SectionCard(Icons.Outlined.AutoAwesome, "Sort & describe", "Files copies into folders with a one-line caption") {
                EngineChip(model, openSettings)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CountField("Newest photos", model.limitText, { model.limitText = it }, Modifier.width(150.dp))
                    Text(
                        "Already-filed photos are skipped.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledTonalButton(onClick = { model.scan() }, enabled = !model.anyBusy) {
                        ButtonIcon(Icons.Outlined.Search); Text("Load photos")
                    }
                    Button(onClick = { model.runSort() }, enabled = !model.anyBusy && rows.any { it.state is RowState.Todo }) {
                        ButtonIcon(Icons.Outlined.AutoAwesome); Text("Organize")
                    }
                    if (rows.any { it.state is RowState.Failed } && !model.anyBusy) {
                        TextButton(onClick = { model.retryFailed() }) { ButtonIcon(Icons.Outlined.Refresh); Text("Retry errors") }
                    }
                }
                StatusLine(model.status)
                HowItWorks(
                    "Load the newest photos, then Organize: the AI looks at each one and files a COPY into " +
                        "Pictures/PhotoSorter/<folder>/ with a caption (stored in the photo's details). " +
                        "Originals are never changed. Tap a filed photo to open its copy."
                )
            }
        }
        if (rows.isNotEmpty()) {
            item(key = "summary") { RowSummary(rows) }
            items(rows, key = { it.photo.id }) { row -> PhotoRowCard(row, onOpen = { model.openCopy(it.outPath) }) }
        } else {
            item(key = "empty") {
                EmptyState(Icons.Outlined.ImageSearch, "No photos loaded", "Press Load photos to pick up your newest pictures.")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RowSummary(rows: List<Row>) {
    val scheme = MaterialTheme.colorScheme
    val queued = rows.count { it.state is RowState.Todo || it.state is RowState.Progress }
    val done = rows.count { it.state is RowState.Done || it.state is RowState.Cached || it.state is RowState.Scanned }
    val failed = rows.count { it.state is RowState.Failed }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatusPill("${rows.size} photos", scheme.surfaceVariant, scheme.onSurfaceVariant)
        if (queued > 0) StatusPill("$queued to do", scheme.primaryContainer, scheme.onPrimaryContainer)
        if (done > 0) StatusPill("$done done", SuccessColor.copy(alpha = 0.16f), SuccessColor)
        if (failed > 0) StatusPill("$failed failed", scheme.errorContainer, scheme.onErrorContainer)
    }
}

// ======================================================================== Trips

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TripsScreen(model: AppModel, openSettings: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    var showPhotos by remember { mutableStateOf(false) }
    if (confirm) {
        val n = model.trips.sumOf { it.photos.size }
        AlertDialog(
            onDismissRequest = { confirm = false },
            icon = { Icon(if (model.tripMove) Icons.Outlined.DriveFileMove else Icons.Outlined.ContentCopy, null) },
            title = { Text(if (model.tripMove) "Move $n photos?" else "Copy $n photos?") },
            text = {
                Text(
                    if (model.tripMove) "Each photo is copied into its folder and the copy is verified. Then Android asks " +
                        "you once to delete the originals - nothing is deleted before that."
                    else "Copies go to Pictures/PhotoSorter/<folder>/. Your originals stay where they are."
                )
            },
            confirmButton = {
                Button(onClick = { confirm = false; model.organizeTrips() }) { Text(if (model.tripMove) "Move" else "Copy") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "controls") {
            SectionCard(Icons.Outlined.Luggage, "Organize", "Trips, food, screenshots, people and more") {
                EngineChip(model, openSettings)
                CountField("Newest photos to scan", model.tripLimitText, { model.tripLimitText = it }, Modifier.fillMaxWidth())
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !model.tripMove, onClick = { if (!model.tripBusy) { model.tripMove = false; model.savePrefs() } },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("Copy") }
                    SegmentedButton(
                        selected = model.tripMove, onClick = { if (!model.tripBusy) { model.tripMove = true; model.savePrefs() } },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text("Move") }
                }
                Text(
                    if (model.tripMove) "Move: originals are deleted after a verified copy (you confirm first)."
                    else "Copy: originals stay where they are.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (model.tripMove) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledTonalButton(onClick = { model.scanTrips() }, enabled = !model.anyBusy) {
                        ButtonIcon(Icons.Outlined.Search); Text("Scan photos")
                    }
                    Button(
                        onClick = { confirm = true },
                        enabled = !model.anyBusy && model.trips.isNotEmpty() && model.pendingDelete == null,
                    ) {
                        ButtonIcon(if (model.tripMove) Icons.Outlined.DriveFileMove else Icons.Outlined.ContentCopy)
                        Text(if (model.tripMove) "Move into folders" else "Copy into folders")
                    }
                }
                StatusLine(model.tripStatus)
                HowItWorks(
                    "The AI tells what each photo shows (food, people, pets, documents...). Screenshots and WhatsApp " +
                        "images are recognized from where they are saved and get their own folders. Photos taken in another time zone or country form trips: every " +
                        "photo from those days goes to one folder like japan-trip, named from landmarks the AI " +
                        "recognizes or the GPS. Review the folders, then copy or move them into Pictures/PhotoSorter/. " +
                        "Already-filed photos are skipped, so you can work through a big library in batches."
                )
            }
        }
        val pd = model.pendingDelete
        if (pd != null && pd.purpose == "move") {
            item(key = "pending") {
                Card(
                    Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Finish the move", style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                        Text(
                            "${pd.photos.size} originals still need to go to the trash - their verified copies are already in Pictures/PhotoSorter/.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { model.retryDelete() }, enabled = !model.anyBusy) { Text("Move originals to trash") }
                            TextButton(onClick = { model.cancelDelete() }) { Text("Keep both") }
                        }
                    }
                }
            }
        }
        if (model.trips.isNotEmpty()) {
            item(key = "groupsHeader") {
                Text("${model.trips.size} folders", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
            }
            items(model.trips, key = { "t" + it.id }) { trip -> TripCard(trip) }
        }
        val tripRows = model.tripRows
        if (tripRows.isNotEmpty()) {
            val expanded = showPhotos || model.tripBusy
            item(key = "rowsHeader") {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { showPhotos = !showPhotos }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Each photo", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Icon(Icons.Outlined.ExpandMore, null, Modifier.rotate(if (expanded) 180f else 0f))
                }
                RowSummary(tripRows)
            }
            if (expanded) {
                items(tripRows, key = { "r" + it.photo.id }) { row -> PhotoRowCard(row, onOpen = { model.openCopy(it.outPath) }) }
            }
        } else if (model.trips.isEmpty()) {
            item(key = "empty") {
                EmptyState(Icons.Outlined.Luggage, "Nothing organized yet", "Press Scan photos to sort your newest pictures into folders.")
            }
        }
    }
}

@Composable
private fun TripCard(trip: TripCluster) {
    val isTrip = trip.folder.endsWith("-trip") || trip.folder.startsWith("trip-")
    val icon = when {
        isTrip -> Icons.Outlined.Flight
        trip.folder == "food" -> Icons.Outlined.Restaurant
        trip.folder == "screenshots" -> Icons.Outlined.Screenshot
        trip.folder == "whatsapp" -> Icons.Outlined.Chat
        trip.folder == "people" -> Icons.Outlined.People
        trip.folder == "pets" -> Icons.Outlined.Pets
        trip.folder == "documents" -> Icons.Outlined.Description
        trip.folder == "nature" -> Icons.Outlined.Park
        trip.folder == "shopping" -> Icons.Outlined.ShoppingBag
        trip.folder == "events" -> Icons.Outlined.Celebration
        else -> Icons.Outlined.Folder
    }
    Card(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon, null,
                    tint = if (isTrip) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        trip.folder.ifEmpty { "unnamed" }, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${trip.photos.size} photo${if (trip.photos.size == 1) "" else "s"}  ·  ${trip.dateRangeLabel}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (trip.filed > 0) {
                    StatusPill(
                        if (trip.filed >= trip.photos.size) "filed" else "${trip.filed}/${trip.photos.size}",
                        SuccessColor.copy(alpha = 0.16f), SuccessColor,
                    )
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(trip.photos.take(12), key = { it.id }) { p ->
                    PhotoThumb(p, 192, Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)))
                }
            }
            if (trip.filed in 1 until trip.photos.size) {
                LinearProgressIndicator(
                    progress = { trip.filed.toFloat() / trip.photos.size }, strokeCap = StrokeCap.Round,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (trip.summary.isNotEmpty()) {
                Text(trip.summary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ======================================================================== Duplicates

/** Clean up tab: duplicates or blurry photos. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanScreen(model: AppModel) {
    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            listOf("dups" to "Duplicates", "blurry" to "Blurry").forEachIndexed { i, (key, label) ->
                SegmentedButton(
                    selected = model.cleanMode == key,
                    onClick = { model.cleanMode = key },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                ) { Text(label) }
            }
        }
        if (model.cleanMode == "blurry") BlurScreen(model) else DupsScreen(model)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DupsScreen(model: AppModel) {
    var confirm by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Pair<DupCluster, Int>?>(null) }
    preview?.let { (cluster, start) ->
        // the group may have changed (photos deleted) since the preview opened
        val live = model.clusters.firstOrNull { it.key == cluster.key }
        if (live == null) preview = null
        else DupPreviewDialog(
            live, start, model.selected,
            onToggle = { model.toggleSelected(it) },
            onKeepOnly = { model.keepOnly(live, it) },
            onDismiss = { preview = null },
        )
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            icon = { Icon(Icons.Outlined.DeleteOutline, null) },
            title = { Text("Move ${model.selected.size} photos to the trash?") },
            text = {
                Text(
                    "They stay in Android's trash for 30 days, so you can restore them. After that about " +
                        "${humanBytes(model.selectedBytes)} is freed. Android will ask you to confirm once more."
                )
            },
            confirmButton = {
                Button(
                    onClick = { confirm = false; model.deleteSelected() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Move to trash") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
    val clusters = model.clusters
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "controls") {
            SectionCard(Icons.Outlined.CleaningServices, "Duplicates", "Find copies and look-alikes to free up space") {
                FilledTonalButton(onClick = { model.scanDups() }, enabled = !model.anyBusy) {
                    ButtonIcon(Icons.Outlined.Search); Text(if (clusters.isEmpty()) "Scan library" else "Scan again")
                }
                StatusLine(model.dupStatus)
                if (model.lastFreed > 0) {
                    StatusPill("Freed this session: ${humanBytes(model.lastFreed)}", SuccessColor.copy(alpha = 0.16f), SuccessColor)
                }
                if (clusters.isNotEmpty() && !model.dupBusy) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (clusters.any { !it.exact }) {
                            OutlinedButton(onClick = { model.selectLookAlikes() }) { Text("Select look-alikes") }
                        }
                        if (model.selected.isNotEmpty()) {
                            TextButton(onClick = { model.clearSelection() }) { Text("Clear selection") }
                        }
                    }
                }
                HowItWorks(
                    "Runs fully on your phone. Exact copies (same bytes) are found first and the extra copies are " +
                        "pre-selected. Look-alikes - bursts, edits, re-saves, RAW+JPEG pairs - are only shown for review. " +
                        "Tap a picture to switch keep/delete; press and hold (or Compare) to view them full screen. " +
                        "RAW files are never selected automatically."
                )
            }
        }
        if (clusters.isNotEmpty() && model.selected.isNotEmpty()) {
            item(key = "deleteBar") {
                Button(
                    onClick = { confirm = true },
                    enabled = !model.anyBusy && model.pendingDelete == null,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    ButtonIcon(Icons.Outlined.DeleteOutline)
                    Text("Trash ${model.selected.size} selected  ·  ${humanBytes(model.selectedBytes)}")
                }
            }
        }
        if (clusters.isEmpty() && !model.dupBusy) {
            item(key = "empty") {
                EmptyState(Icons.Outlined.CleaningServices, "Nothing to review yet", "Scan your library to find duplicate photos.")
            }
        }
        items(clusters, key = { "c" + it.key }) { cluster ->
            ClusterCard(
                cluster, model.selected,
                onToggle = { model.toggleSelected(it) },
                onOpen = { preview = cluster to it },
            )
        }
    }
}

@Composable
private fun ClusterCard(cluster: DupCluster, selected: Set<Long>, onToggle: (Long) -> Unit, onOpen: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val (label, pillBg, pillFg) = when {
        cluster.exact -> Triple("Exact copies", scheme.primaryContainer, scheme.onPrimaryContainer)
        cluster.hasRaw -> Triple("RAW + JPEG", scheme.tertiaryContainer, scheme.onTertiaryContainer)
        else -> Triple("Look-alikes", scheme.secondaryContainer, scheme.onSecondaryContainer)
    }
    val toFree = cluster.photos.filter { it.id in selected }.sumOf { it.sizeBytes }
    Card(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(label, pillBg, pillFg)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${cluster.photos.size} photo${if (cluster.photos.size == 1) "" else "s"}" + if (toFree > 0) "  ·  frees ${humanBytes(toFree)}" else "",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onOpen(0) }) { Text("Compare") }
            }
            DupThumbStrip(cluster.photos, selected, onToggle = onToggle, onPreview = onOpen)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlurScreen(model: AppModel) {
    var confirm by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Pair<List<Photo>, Int>?>(null) }
    val items = model.blurry
    val groups = remember(items) {
        val fmt = java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.getDefault())
        items.groupBy { it.kind to fmt.format(java.util.Date(it.photo.dateTakenMs)) }.toList()
    }
    preview?.let { (photos, start) ->
        val live = photos.filter { p -> items.any { it.photo.id == p.id } }
        if (live.isEmpty()) preview = null
        else DupPreviewDialog(
            DupCluster(false, live), start.coerceAtMost(live.size - 1), model.blurSelected,
            onToggle = { model.toggleBlur(it) },
            onKeepOnly = null,
            onDismiss = { preview = null },
        )
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            icon = { Icon(Icons.Outlined.DeleteOutline, null) },
            title = { Text("Move ${model.blurSelected.size} photos to the trash?") },
            text = {
                Text(
                    "They stay in Android's trash for 30 days, so you can restore them. After that about " +
                        "${humanBytes(model.blurSelectedBytes)} is freed. Android will ask you to confirm once more."
                )
            },
            confirmButton = {
                Button(
                    onClick = { confirm = false; model.deleteBlurSelected() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Move to trash") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "controls") {
            SectionCard(Icons.Outlined.BlurOn, "Blurry photos", "Out-of-focus, shaky and accidental shots") {
                FilledTonalButton(onClick = { model.scanBlurry() }, enabled = !model.anyBusy) {
                    ButtonIcon(Icons.Outlined.Search); Text(if (items.isEmpty()) "Scan library" else "Scan again")
                }
                StatusLine(model.blurStatus)
                if (items.isNotEmpty() && !model.blurBusy) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(onClick = { model.selectBlurry() }) { Text("Select all") }
                        if (items.any { it.kind == BlurItem.Kind.DARK }) {
                            OutlinedButton(onClick = { model.selectBlurry(BlurItem.Kind.DARK) }) { Text("Select dark/blank") }
                        }
                        if (model.blurSelected.isNotEmpty()) {
                            TextButton(onClick = { model.clearBlurSelection() }) { Text("Clear selection") }
                        }
                    }
                }
                HowItWorks(
                    "Runs fully on your phone. Each photo's sharpness is measured in its sharpest area, so a " +
                        "sharp subject on a plain background is not flagged. Screenshots, RAW files and " +
                        "PhotoSorter's own copies are skipped. Nothing is selected for you: tap a picture to " +
                        "mark it, press and hold to view it full screen."
                )
            }
        }
        if (model.blurSelected.isNotEmpty()) {
            item(key = "deleteBar") {
                Button(
                    onClick = { confirm = true },
                    enabled = !model.anyBusy && model.pendingDelete == null,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    ButtonIcon(Icons.Outlined.DeleteOutline)
                    Text("Trash ${model.blurSelected.size} selected  ·  ${humanBytes(model.blurSelectedBytes)}")
                }
            }
        }
        if (items.isEmpty() && !model.blurBusy) {
            item(key = "empty") {
                EmptyState(Icons.Outlined.BlurOn, "Nothing to review yet", "Scan your library to find blurry photos.")
            }
        }
        items(groups, key = { (k, _) -> "b" + k.first.name + k.second }) { (k, group) ->
            val photos = group.map { it.photo }
            val scheme = MaterialTheme.colorScheme
            Card(
                Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant.copy(alpha = 0.45f)),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(k.first.label, scheme.secondaryContainer, scheme.onSecondaryContainer)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${k.second}  ·  ${photos.size} photo${if (photos.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { preview = photos to 0 }) { Text("View") }
                    }
                    DupThumbStrip(photos, model.blurSelected, onToggle = { model.toggleBlur(it) }, onPreview = { preview = photos to it })
                }
            }
        }
    }
}
