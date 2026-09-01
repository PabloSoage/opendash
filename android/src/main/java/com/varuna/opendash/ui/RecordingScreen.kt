package com.varuna.opendash.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.varuna.opendash.R
import com.varuna.opendash.data.SessionFile
import com.varuna.opendash.ui.theme.ValueStyle
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * A recording, scrolled through rather than looked at all at once.
 *
 * The point of this screen is that a twenty-three minute session is not
 * legible as one picture. So it shows a window — five seconds by default, the
 * same as the Scanmatik software — and the window is dragged along the
 * recording. A flick keeps going: the fling uses very little friction, so
 * pushing it at the start and letting go plays the whole session past, which is
 * the closest thing to watching it happen.
 *
 * Everything on screen shares one position, so the charts stay aligned with
 * each other and with the scrub bar.
 *
 * The marker is placed by pressing and holding. It stays at its place on the
 * screen rather than at its moment in the recording, which sounds backwards
 * until the recording is moving: held to the screen, the values under it change
 * as the session runs past, which is what makes it a readout rather than a
 * bookmark.
 */
@Composable
fun RecordingScreen(session: SessionFile.Session) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var window by remember { mutableStateOf(5_000) }
    var rows by remember { mutableStateOf(3) }
    var marker by remember { mutableStateOf<Float?>(null) }
    var picking by remember { mutableStateOf(false) }
    var scaleToWindow by remember { mutableStateOf(true) }

    val shown: SnapshotStateList<Int> = remember(session) {
        mutableStateListOf<Int>().apply {
            addAll(session.channels.indices.filter { session.channels[it].size > 1 })
        }
    }

    val duration = session.durationMs.coerceAtLeast(1)
    val span = window.coerceAtMost(duration)
    val position = remember(session) { Animatable(0f) }
    position.updateBounds(0f, (duration - span).toFloat().coerceAtLeast(0f))

    /**
     * The fling.
     *
     * With exponential decay the coast distance is `velocity / friction` and
     * the coast time is roughly `5 / friction`, where friction is 4.2 times the
     * multiplier. The first setting stopped after a second or two, which is
     * fine for a list and useless here: the point of the fling is to push the
     * recording at the start and watch it run to the end.
     *
     * So: friction 0.084, which is about a minute of coasting, and the velocity
     * multiplied by [FLING_GAIN] on top of the drag scale. At a five-second
     * window on a phone that puts a firm flick at roughly a twenty-minute
     * journey and a gentle one at a few minutes, and it stays in proportion at
     * every window size because the drag scale is what it is built on.
     */
    val decay = remember { exponentialDecay<Float>(frictionMultiplier = 0.02f) }

    Column(modifier = Modifier.fillMaxSize()) {

        Scrubber(
            position = position,
            span = span,
            duration = duration,
            onSeekBy = { delta -> scope.launch { position.snapTo(position.value + delta) } },
            onSeekStart = { scope.launch { position.stop() } },
        )

        // Whatever windows fit inside this recording, plus the recording
        // itself, which is what "All" means.
        val allLabel = stringResource(R.string.viewer_window_all)
        val windowScale = stringResource(R.string.viewer_scale_window)
        val recordingScale = stringResource(R.string.viewer_scale_recording)
        val windows = remember(duration) { WINDOWS.filter { it < duration } + duration }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Combo(
                label = stringResource(R.string.viewer_window),
                value = span,
                options = windows,
                render = { if (it >= duration) allLabel else windowLabel(it) },
                onSelect = { window = it },
            )
            Combo(
                label = stringResource(R.string.viewer_scale),
                value = scaleToWindow,
                options = listOf(true, false),
                render = { if (it) windowScale else recordingScale },
                onSelect = { scaleToWindow = it },
            )
            Combo(
                label = stringResource(R.string.viewer_rows),
                value = rows,
                options = listOf(1, 2, 3, 4),
                render = { it.toString() },
                onSelect = { rows = it },
            )
            TextButton(onClick = { picking = true }) {
                Text(
                    stringResource(R.string.viewer_parameters) + "  " +
                        shown.size + "/" + session.channels.size
                )
            }
            if (marker != null) {
                TextButton(onClick = { marker = null }) {
                    Text(stringResource(R.string.viewer_marker_clear))
                }
            }
        }

        Hint(
            stringResource(
                R.string.viewer_summary,
                session.channels.size,
                session.samples,
                clock(duration),
            ),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val widthPx = with(density) { maxWidth.toPx() }
            val chartHeight = maxHeight / rows
            // rememberDraggableState keeps the lambda it was first given, so
            // the scale has to be read from a state that updates. Capturing it
            // directly would leave the drag moving at the scale of whichever
            // window was selected when the screen was first drawn.
            val msPerPx = rememberUpdatedState(span.toFloat() / widthPx.coerceAtLeast(1f))
            val dragState = rememberDraggableState { delta ->
                scope.launch { position.snapTo(position.value - delta * msPerPx.value) }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Press and hold places the marker; two fingers held down
                    // take it away. Written out rather than using
                    // detectTapGestures because that one reports where the
                    // press was and not how many fingers were on the glass,
                    // and the difference is the whole gesture.
                    //
                    // Nothing here consumes anything, so the drag and the
                    // vertical scroll below still see every event they need.
                    .pointerInput(session, span) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var fingers = 1
                            val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    fingers = maxOf(fingers, event.changes.count { it.pressed })
                                    // A finger lifted, or the press turned into
                                    // a drag: not a hold either way.
                                    if (event.changes.any { !it.pressed }) break
                                    val moved = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if ((moved.position - down.position).getDistance() >
                                        viewConfiguration.touchSlop
                                    ) break
                                }
                            }
                            // The timeout expiring is the success case: it means
                            // the fingers were still down and still still.
                            if (ended == null) {
                                marker = if (fingers >= 2) {
                                    null
                                } else {
                                    (down.position.x / widthPx).coerceIn(0f, 1f)
                                }
                            }
                        }
                    }
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = dragState,
                        onDragStarted = { position.stop() },
                        onDragStopped = { velocity ->
                            position.animateDecay(-velocity * msPerPx.value * FLING_GAIN, decay)
                        },
                    )
            ) {
                if (shown.isEmpty()) {
                    Hint(
                        stringResource(R.string.viewer_empty),
                        modifier = Modifier.padding(24.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(shown.toList(), key = { it }) { index ->
                            WindowChart(
                                channel = session.channels[index],
                                position = position,
                                span = span,
                                marker = marker,
                                scaleToWindow = scaleToWindow,
                                modifier = Modifier.height(chartHeight),
                            )
                        }
                        item {
                            Hint(
                                stringResource(R.string.viewer_marker_hint),
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (picking) {
        ParameterPicker(
            session = session,
            shown = shown,
            onDismiss = { picking = false },
        )
    }
}

// ── the chart ─────────────────────────────────────────────────────────────

/**
 * One channel over `[from, from + span]`.
 *
 * The vertical scale goes either way, and the choice is a real one.
 *
 * Scaled to the window, every trace fills its box, so a wobble of a tenth of a
 * degree is as visible as a climb of a hundred — which is usually what you came
 * to look at, and is the default. The cost is that the line rescales as the
 * recording moves under it, so a flat stretch can come out looking like noise.
 *
 * Scaled to the whole recording, the box is fixed: flat looks flat, a climb
 * looks like a climb, and one spike somewhere else in the session flattens
 * everything either side of it.
 *
 * Both ends of whichever range is in force are printed under the name, so it is
 * never a guess which of the two you are looking at.
 */
@Composable
private fun WindowChart(
    channel: SessionFile.Channel,
    position: Animatable<Float, AnimationVector1D>,
    span: Int,
    marker: Float?,
    scaleToWindow: Boolean,
    modifier: Modifier = Modifier,
) {
    val line = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val markerColour = MaterialTheme.colorScheme.secondary
    val from = position.value
    val at = marker?.let { (from + it * span).toInt() }
    val value = channel.valueAt(at ?: (from + span).toInt())

    // Either the range inside the window, which makes a small wobble fill the
    // box, or the range over the recording, which keeps the trace from
    // rescaling under your finger. Whichever it is, both ends are printed, so
    // the height of a bump is never a mystery.
    val range = if (scaleToWindow) {
        channel.rangeIn(from.toInt(), (from + span).toInt())
    } else {
        null
    } ?: (channel.min to channel.max)

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    channel.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    format(range.first) + " – " + format(range.second) +
                        if (channel.unit.isEmpty()) "" else " " + channel.unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = muted.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
            Text(
                value?.let { format(it) } ?: "—",
                style = ValueStyle,
                color = if (at != null) markerColour else MaterialTheme.colorScheme.onSurface,
            )
            if (channel.unit.isNotEmpty()) {
                Text(
                    " " + channel.unit,
                    style = MaterialTheme.typography.labelMedium,
                    color = muted,
                )
            }
        }

        // weight, not fillMaxSize: inside a Column a child asking for the full
        // height gets the whole box, not what is left after the label row.
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
            var low = range.first
            var high = range.second
            if (high - low < 1e-9) {
                low -= 1.0
                high += 1.0
            }
            val pad = (high - low) * 0.08
            low -= pad
            high += pad

            fun y(v: Double) = (size.height * (1 - (v - low) / (high - low))).toFloat()
            fun x(ms: Int) = ((ms - from) / span * size.width)

            drawLine(
                muted.copy(alpha = 0.18f),
                Offset(0f, size.height / 2),
                Offset(size.width, size.height / 2),
                strokeWidth = 1f,
            )

            trace(channel, from, span, { ms -> x(ms) }, { v -> y(v) }, line)

            if (marker != null) {
                val mx = marker * size.width
                drawLine(
                    markerColour,
                    Offset(mx, 0f),
                    Offset(mx, size.height),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                )
            }
        }
    }
}

/**
 * The polyline for the visible slice, plus one sample either side so the trace
 * enters and leaves the frame instead of starting at its edge.
 *
 * Decimated to at most two points per pixel. Drawing eighty thousand segments
 * into a box a thousand pixels wide costs time and shows nothing a thousand
 * segments would not.
 */
private fun DrawScope.trace(
    channel: SessionFile.Channel,
    from: Float,
    span: Int,
    x: (Int) -> Float,
    y: (Double) -> Float,
    colour: Color,
) {
    if (channel.size == 0) return
    val first = (channel.firstAtOrAfter(from.toInt()) - 1).coerceAtLeast(0)
    val last = channel.firstAtOrAfter((from + span).toInt() + 1).coerceAtMost(channel.size - 1)
    if (last <= first) {
        val v = channel.valueAt((from + span).toInt()) ?: return
        drawLine(colour.copy(alpha = 0.6f), Offset(0f, y(v)), Offset(size.width, y(v)), strokeWidth = 2f)
        return
    }

    val stride = ((last - first) / (size.width * 2).coerceAtLeast(1f)).toInt().coerceAtLeast(1)
    val path = Path()
    var started = false
    var i = first
    while (i <= last) {
        val px = x(channel.times[i])
        val py = y(channel.values[i])
        if (!started) {
            path.moveTo(px, py)
            started = true
        } else {
            path.lineTo(px, py)
        }
        i += stride
    }
    if (i - stride != last) path.lineTo(x(channel.times[last]), y(channel.values[last]))

    val area = Path().apply {
        addPath(path)
        lineTo(x(channel.times[last]), size.height)
        lineTo(x(channel.times[first]), size.height)
        close()
    }
    drawPath(area, Brush.verticalGradient(listOf(colour.copy(alpha = 0.18f), colour.copy(alpha = 0f))))
    drawPath(path, colour, style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

// ── the scrub bar ─────────────────────────────────────────────────────────

/** Where in the recording the window is, and a handle to put it elsewhere. */
@Composable
private fun Scrubber(
    position: Animatable<Float, AnimationVector1D>,
    span: Int,
    duration: Int,
    onSeekBy: (Float) -> Unit,
    onSeekStart: () -> Unit,
) {
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val thumb = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                clock(position.value.toInt()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                " / " + clock(duration),
                style = MaterialTheme.typography.labelMedium,
                color = muted,
                modifier = Modifier.weight(1f),
            )
            Text(
                windowLabel(span),
                style = MaterialTheme.typography.labelMedium,
                color = muted,
            )
        }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(28.dp)) {
            val perPx = rememberUpdatedState(duration / with(density) { maxWidth.toPx() }.coerceAtLeast(1f))
            val seek = rememberDraggableState { delta -> onSeekBy(delta * perPx.value) }
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = seek,
                        onDragStarted = { onSeekStart() },
                    )
            ) {
                val h = size.height
                drawRoundRect(
                    track,
                    topLeft = Offset(0f, h / 2 - 3f),
                    size = androidx.compose.ui.geometry.Size(size.width, 6f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f),
                )
                val left = (position.value / duration * size.width).coerceIn(0f, size.width)
                val w = (span.toFloat() / duration * size.width).coerceAtLeast(6f)
                drawRoundRect(
                    thumb,
                    topLeft = Offset(left.coerceAtMost(size.width - w), h / 2 - 7f),
                    size = androidx.compose.ui.geometry.Size(w, 14f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(7f, 7f),
                )
            }
        }
    }
}

// ── the parameter picker ──────────────────────────────────────────────────

@Composable
private fun ParameterPicker(
    session: SessionFile.Session,
    shown: SnapshotStateList<Int>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.viewer_parameters)) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        shown.clear()
                        shown.addAll(session.channels.indices.filter { session.channels[it].size > 1 })
                    }) { Text(stringResource(R.string.viewer_all)) }
                    TextButton(onClick = { shown.clear() }) {
                        Text(stringResource(R.string.viewer_none))
                    }
                }
                session.visibleWhenSaved?.takeIf { it < session.channels.size }?.let {
                    Hint(stringResource(R.string.viewer_filtered, it, session.channels.size))
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().height(360.dp)) {
                    items(session.channels.indices.toList(), key = { it }) { i ->
                        val channel = session.channels[i]
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = i in shown,
                                onCheckedChange = { on ->
                                    if (on) shown.add(i) else shown.remove(i)
                                },
                            )
                            Text(
                                channel.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                channel.unit,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.viewer_close)) }
        },
    )
}

// ── formatting ────────────────────────────────────────────────────────────

/**
 * Windows worth offering. Down to fifty milliseconds, where individual readings
 * separate, and up to a quarter of an hour, plus whatever the recording is.
 */
/** How much further a flick travels than the finger that threw it. */
private const val FLING_GAIN = 4f

private val WINDOWS = listOf(50, 100, 200, 500, 1_000, 2_000, 5_000, 10_000, 30_000, 60_000, 300_000, 900_000)

private fun windowLabel(ms: Int): String = when {
    ms < 1_000 -> ms.toString() + " ms"
    ms < 60_000 -> (ms / 1000).toString() + " s"
    else -> (ms / 60_000).toString() + " min"
}

private fun clock(ms: Int): String {
    val seconds = ms / 1000
    return String.format(Locale.ROOT, "%d:%02d.%d", seconds / 60, seconds % 60, (ms % 1000) / 100)
}

private fun format(v: Double): String = when {
    kotlin.math.abs(v) >= 1000 -> String.format(Locale.ROOT, "%.0f", v)
    kotlin.math.abs(v) >= 10 -> String.format(Locale.ROOT, "%.1f", v)
    else -> String.format(Locale.ROOT, "%.2f", v)
}
