package com.example.photosorter

import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.LruCache
import android.util.Size
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Thumbnail / preview loading with a small memory cache (system thumbnails on Android 10+). */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    suspend fun load(resolver: ContentResolver, photo: Photo, px: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "${photo.id}@$px"
        cache.get(key) ?: runCatching {
            if (Build.VERSION.SDK_INT >= 29) resolver.loadThumbnail(photo.uri, Size(px, px), null)
            else ImageTools.decodeSmall(resolver, photo.uri, px)
        }.getOrNull()?.also { cache.put(key, it) }
    }
}

@Composable
fun PhotoThumb(photo: Photo, px: Int, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val resolver = LocalContext.current.contentResolver
    val bmp by produceState<Bitmap?>(null, photo.id, px) { value = Thumbs.load(resolver, photo, px) }
    Box(modifier.background(Color(0x22888888)), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) {
            Image(b.asImageBitmap(), contentDescription = photo.title, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        } else {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

private val KeepGreen = Color(0xFF2E7D32)

/** Horizontal strip of a duplicate group's photos: red border = will be deleted. Tap opens the preview. */
@Composable
fun DupThumbStrip(photos: List<Photo>, selected: Set<Long>, onOpen: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(photos, key = { _, p -> p.id }) { i, p ->
            val del = p.id in selected
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PhotoThumb(
                    p, 256,
                    Modifier
                        .size(104.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(3.dp, if (del) MaterialTheme.colorScheme.error else KeepGreen, RoundedCornerShape(8.dp))
                        .clickable { onOpen(i) }
                )
                Text(
                    if (del) "DELETE" else "KEEP",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (del) MaterialTheme.colorScheme.error else KeepGreen
                )
            }
        }
    }
}

/**
 * Full-screen preview of one duplicate group: swipe between photos, compare resolution/size/date,
 * switch keep/delete, or keep only the one shown.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DupPreviewDialog(
    cluster: DupCluster,
    startIndex: Int,
    selected: Set<Long>,
    onToggle: (Long) -> Unit,
    onKeepOnly: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val photos = cluster.photos
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, maxOf(0, photos.size - 1))) { photos.size }
    val ctx = LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${pager.currentPage + 1} / ${photos.size}  -  swipe to compare",
                    color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = onDismiss) { Text("Close", color = Color.White) }
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth(), key = { photos[it].id }) { page ->
                PhotoThumb(photos[page], 1600, Modifier.fillMaxSize(), ContentScale.Fit)
            }
            val p = photos[pager.currentPage]
            val del = p.id in selected
            Text(p.title, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${p.width}x${p.height}  ·  ${humanBytes(p.sizeBytes)}  ·  ${p.dateLabel}" +
                    (if (p.isRaw) "  ·  RAW" else ""),
                color = Color(0xFFCCCCCC), style = MaterialTheme.typography.bodySmall
            )
            Text(
                if (del) "Will be DELETED" else "Will be KEPT",
                color = if (del) Color(0xFFFF8A80) else Color(0xFFA5D6A7),
                style = MaterialTheme.typography.labelLarge
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onToggle(p.id) },
                    colors = if (del) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(if (del) "Keep this" else "Delete this") }
                Button(onClick = { onKeepOnly(p.id) }) { Text("Keep only this") }
                OutlinedButton(onClick = {
                    runCatching {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, p.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }.onFailure { Toast.makeText(ctx, "No app found to open the photo", Toast.LENGTH_SHORT).show() }
                }) { Text("Zoom", color = Color.White) }
            }
        }
    }
}
