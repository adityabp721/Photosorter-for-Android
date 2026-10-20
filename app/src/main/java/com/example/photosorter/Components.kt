package com.example.photosorter

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Luggage
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------- building blocks

/** Rounded card with an icon + title header, used for each tab's controls. */
@Composable
fun SectionCard(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            content()
        }
    }
}

/** Small colored pill, e.g. "filed", "error", "62%". */
@Composable
fun StatusPill(text: String, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(50), color = container) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1,
        )
    }
}

/** Collapsible "How it works" text so the main controls stay compact. */
@Composable
fun HowItWorks(text: String) {
    var open by remember { mutableStateOf(false) }
    Column {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("How it works", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Icon(
                Icons.Outlined.ExpandMore, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp).rotate(if (open) 180f else 0f),
            )
        }
        if (open) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun StatusLine(text: String) {
    if (text.isBlank()) return
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 6)
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(56.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** Number field for "how many photos" with a label. */
@Composable
fun CountField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(5)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    )
}

// ---------------------------------------------------------------- running job

/** Pinned card for the job in progress: title, percent, bar, detail, Stop. */
@Composable
fun JobCard(model: AppModel) {
    val job = model.job ?: return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        job.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val f = job.fraction
                    val counter = if (job.total in 1 until 1_000_000 && !job.title.startsWith("Downloading")) "${job.done} of ${job.total}" else ""
                    Text(
                        listOfNotNull(f?.let { "${(it * 100).toInt()}%" }, counter.ifEmpty { null }).joinToString("  ·  ")
                            .ifEmpty { "Working..." },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                FilledTonalButton(onClick = { model.stopAll() }) {
                    Icon(Icons.Outlined.Stop, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Stop")
                }
            }
            val f = job.fraction
            if (f != null) {
                LinearProgressIndicator(
                    progress = { f }, strokeCap = StrokeCap.Round,
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
            } else {
                LinearProgressIndicator(strokeCap = StrokeCap.Round, modifier = Modifier.fillMaxWidth().height(6.dp))
            }
            Text(
                job.detail.ifEmpty { "Keeps running if you leave the app - check the notification." },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------- per-photo row

/** One photo: thumbnail, name, meta, status pill, progress bar or result. */
@Composable
fun PhotoRowCard(row: Row, onOpen: (DoneRec) -> Unit) {
    val s = row.state
    val rec = when (s) {
        is RowState.Done -> s.rec
        is RowState.Cached -> s.rec
        else -> null
    }
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().then(if (rec != null) Modifier.clickable { onOpen(rec) } else Modifier),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PhotoThumb(row.photo, 192, Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.photo.title, style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    when (s) {
                        is RowState.Todo -> StatusPill("queued", scheme.surfaceVariant, scheme.onSurfaceVariant)
                        is RowState.Progress -> StatusPill("${(s.fraction * 100).toInt()}%", scheme.primaryContainer, scheme.onPrimaryContainer)
                        is RowState.Scanned -> StatusPill("scanned", scheme.secondaryContainer, scheme.onSecondaryContainer)
                        is RowState.Done -> StatusPill("filed", SuccessColor.copy(alpha = 0.16f), SuccessColor)
                        is RowState.Cached -> StatusPill("already filed", SuccessColor.copy(alpha = 0.16f), SuccessColor)
                        is RowState.Failed -> StatusPill("error", scheme.errorContainer, scheme.onErrorContainer)
                    }
                }
                Text(
                    row.photo.dateLabel +
                        (if (row.photo.latitude != null) "  ·  GPS" else "") +
                        "  ·  " + humanBytes(row.photo.sizeBytes),
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1,
                )
                when (s) {
                    is RowState.Progress -> {
                        LinearProgressIndicator(
                            progress = { s.fraction }, strokeCap = StrokeCap.Round,
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        )
                        Text(s.what, style = MaterialTheme.typography.labelSmall, color = scheme.primary, maxLines = 1)
                    }
                    is RowState.Scanned -> FolderResult(s.ai.folder, s.ai.description)
                    is RowState.Done -> FolderResult(s.rec.folder, s.rec.description)
                    is RowState.Cached -> FolderResult(s.rec.folder, s.rec.description)
                    is RowState.Failed -> Text(s.msg, style = MaterialTheme.typography.bodySmall, color = scheme.error, maxLines = 2)
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun FolderResult(folder: String, description: String) {
    Text(
        "$folder/", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
    if (description.isNotBlank()) {
        Text(description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------- AI engine

fun engineLabel(model: AppModel, ctx: android.content.Context): String = when {
    model.mode == "cloud" -> "Cloud · ${model.modelName.ifBlank { "Gemini" }}"
    VlmAi.isReady(ctx) -> "On-device · ${VlmAi.MODEL_LABEL}"
    else -> "On-device · basic labels"
}

/** Tappable chip showing the active AI engine; opens the settings sheet. */
@Composable
fun EngineChip(model: AppModel, onClick: () -> Unit) {
    val ctx = LocalContext.current
    AssistChip(
        onClick = onClick,
        label = { Text(engineLabel(model, ctx)) },
        leadingIcon = {
            Icon(
                if (model.mode == "cloud") Icons.Outlined.Cloud else Icons.Outlined.PhoneAndroid, null,
                Modifier.size(AssistChipDefaults.IconSize),
            )
        },
    )
}

/** Bottom sheet with every AI setting (shared by Sort and Trips). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsSheet(model: AppModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var showKey by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = { model.savePrefs(); onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("AI settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = model.mode == "local",
                    onClick = { if (!model.anyBusy) model.mode = "local" },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    icon = { SegmentedButtonDefaults.Icon(model.mode == "local") { Icon(Icons.Outlined.PhoneAndroid, null, Modifier.size(18.dp)) } },
                ) { Text("On-device") }
                SegmentedButton(
                    selected = model.mode == "cloud",
                    onClick = { if (!model.anyBusy) model.mode = "cloud" },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    icon = { SegmentedButtonDefaults.Icon(model.mode == "cloud") { Icon(Icons.Outlined.Cloud, null, Modifier.size(18.dp)) } },
                ) { Text("Cloud") }
            }
            if (model.mode == "local") {
                FeatureLine(Icons.Outlined.Lock, "Private & free", "Nothing leaves your phone. No key, works offline.")
                FeatureLine(
                    Icons.Outlined.AutoAwesome, "${VlmAi.MODEL_LABEL} (~1.6 GB, Apache 2.0)",
                    "Reads each photo and recognizes subjects and places. " +
                        "Without it, a small built-in labeler gives simple categories.",
                )
                Text(VlmAi.statusText(ctx), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!VlmAi.isReady(ctx)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { model.downloadVlmModel() }, enabled = !model.anyBusy) {
                            Text(if (model.vlmBusy) "Downloading..." else "Download model (1.6 GB)")
                        }
                        if (model.vlmBusy) TextButton(onClick = { model.stopDownload() }) { Text("Pause") }
                    }
                }
                StatusLine(model.vlmStatus)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Place names from GPS", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Looks up the country for photos with GPS, for trip names. Sends only the coordinates to Google.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = model.geocodeLocal, onCheckedChange = { model.geocodeLocal = it })
                }
            } else {
                FeatureLine(Icons.Outlined.Cloud, "Google Gemini", "Best captions; photos are uploaded (downscaled) to Google. ~1 request per photo.")
                OutlinedTextField(
                    value = model.apiKey,
                    onValueChange = { model.apiKey = it.trim() },
                    label = { Text("Gemini API key") },
                    supportingText = { Text("Free at aistudio.google.com") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "Show key")
                        }
                    },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model.modelName,
                    onValueChange = { model.modelName = it.trim() },
                    label = { Text("Model") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Web search", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Lets the AI verify landmarks, products and places",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = model.useWebSearch, onCheckedChange = { model.useWebSearch = it })
                }
            }
            HorizontalDivider()
            Text(
                "Logo artwork: Material Icons by Google (Apache License 2.0).",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun FeatureLine(icon: ImageVector, title: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- permission / welcome

@Composable
fun WelcomeScreen(onAllow: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Image(painterResource(R.drawable.ic_logo), null, Modifier.size(104.dp))
        Spacer(Modifier.height(20.dp))
        Text("PhotoSorter", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Your gallery, organized by AI",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FeatureLine(Icons.Outlined.AutoAwesome, "Sort & describe", "Files photos into folders with a one-line caption.")
            FeatureLine(Icons.Outlined.Luggage, "Trips", "Groups travel photos into folders like japan-trip.")
            FeatureLine(Icons.Outlined.CleaningServices, "Duplicates", "Finds exact copies and look-alikes to free up space.")
            FeatureLine(Icons.Outlined.Lock, "You stay in control", "Nothing is moved or deleted without your confirmation.")
        }
        Spacer(Modifier.height(32.dp))
        Button(onClick = onAllow, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Outlined.PhotoLibrary, null)
            Spacer(Modifier.width(8.dp))
            Text("Allow photo access")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Choose \"Allow all\" so the whole library can be organized.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
