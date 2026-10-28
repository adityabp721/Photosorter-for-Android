package com.adityabalagere.photosorter

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Room at the bottom of every list so the floating bar never hides the last item. */
private val ListPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)

/**
 * The scrolling list used by every tab: a draggable scrollbar on the right and, once the first
 * item (the tab's controls card) scrolls away, a floating bar with [floating] actions and a
 * back-to-top button - so the main buttons are always one tap away.
 */
@Composable
fun ScrollingList(
    floating: (@Composable RowScope.() -> Unit)? = null,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val scrolledAway by remember { derivedStateOf { state.firstVisibleItemIndex > 0 } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(), state = state, contentPadding = ListPadding,
            verticalArrangement = Arrangement.spacedBy(10.dp), content = content,
        )
        ListScrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 8.dp))
        AnimatedVisibility(
            visible = scrolledAway,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 3.dp,
                modifier = Modifier.shadow(6.dp, RoundedCornerShape(28.dp)),
            ) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    floating?.invoke(this)
                    FilledTonalIconButton(onClick = { scope.launch { state.animateScrollToItem(0) } }) {
                        Icon(Icons.Outlined.KeyboardArrowUp, "Back to top")
                    }
                }
            }
        }
    }
}

/**
 * Thin scrollbar for a LazyColumn. Position is by item index (items differ a lot in height, so
 * this is an estimate, like other list scrollbars). Drag the bar, or tap the track, to jump.
 */
@Composable
private fun ListScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    val metrics by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val total = info.totalItemsCount
            val visible = info.visibleItemsInfo
            if (total == 0 || visible.isEmpty() || (!state.canScrollForward && !state.canScrollBackward)) {
                null
            } else {
                val first = visible.first()
                val partial = if (first.size > 0) (-first.offset).coerceAtLeast(0).toFloat() / first.size else 0f
                val shown = visible.size.toFloat().coerceAtMost(total.toFloat())
                val thumb = (shown / total).coerceIn(0.08f, 1f)
                val maxStart = (total - shown).coerceAtLeast(1f)
                val pos = if (!state.canScrollForward) 1f else ((first.index + partial) / maxStart).coerceIn(0f, 1f)
                thumb to pos
            }
        }
    }
    val m = metrics ?: return
    val active = dragging || state.isScrollInProgress
    val alpha by animateFloatAsState(if (active) 0.9f else 0.35f, label = "scrollbar")

    BoxWithConstraints(modifier.width(20.dp)) {
        val trackPx = with(density) { maxHeight.toPx() }
        val thumbPx = (trackPx * m.first).coerceAtLeast(with(density) { 36.dp.toPx() })
        // the thumb's size changes while scrolling; read it fresh instead of restarting the
        // gesture detector (keying pointerInput on it cancelled every drag)
        val sizes by rememberUpdatedState(trackPx to thumbPx)
        fun jumpTo(y: Float) {
            val (track, thumb) = sizes
            val total = state.layoutInfo.totalItemsCount
            if (total == 0 || track <= thumb) return
            val f = ((y - thumb / 2) / (track - thumb)).coerceIn(0f, 1f)
            scope.launch { state.scrollToItem(((total - 1) * f).roundToInt()) }
        }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { dragging = true; jumpTo(it.y) },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ -> change.consume(); jumpTo(change.position.y) }
                }
                .pointerInput(Unit) { detectTapGestures { jumpTo(it.y) } },
        ) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 4.dp)
                    .offset { androidx.compose.ui.unit.IntOffset(0, ((trackPx - thumbPx) * m.second).roundToInt()) }
                    .width(if (active) 6.dp else 4.dp)
                    .height(with(density) { thumbPx.toDp() })
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
            )
        }
    }
}
