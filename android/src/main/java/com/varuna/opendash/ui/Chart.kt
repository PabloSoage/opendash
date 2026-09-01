package com.varuna.opendash.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.varuna.opendash.data.Series
import com.varuna.opendash.ui.theme.ValueStyle
import java.util.Locale

/**
 * A parameter, its current value, and where it has been.
 *
 * The line is drawn against the range actually seen rather than the parameter's
 * declared limits. Coolant temperature nominally spans -40 to 215 °C, so
 * plotting a warm-up against that produces a flat line near the bottom; against
 * the observed range it produces the curve you wanted to look at. A little
 * headroom keeps the trace off the edges.
 *
 * [tick] exists to make the redraw happen. [Series] is a ring buffer written to
 * by the polling thread, and writing a snapshot into Compose state on every
 * sample would allocate a list per reading; instead the monitor bumps a counter
 * once per lap and this reads it, which is enough to invalidate the canvas.
 */
@Composable
fun ParameterChart(
    name: String,
    unit: String,
    series: Series,
    tick: Int,
    modifier: Modifier = Modifier,
    colour: Color = MaterialTheme.colorScheme.primary,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                color = muted,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                String.format(Locale.ROOT, "%.2f", series.last),
                style = ValueStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (unit.isNotBlank()) {
                Text(
                    " " + unit,
                    style = MaterialTheme.typography.labelMedium,
                    color = muted,
                )
            }
        }

        Canvas(modifier = Modifier.fillMaxWidth().height(84.dp).padding(top = 4.dp)) {
            @Suppress("UNUSED_EXPRESSION")
            tick
            val points = series.snapshot()
            if (points.size < 2) return@Canvas

            var low = series.min
            var high = series.max
            if (high - low < 1e-9) {
                low -= 1.0
                high += 1.0
            }
            val pad = (high - low) * 0.12
            low -= pad
            high += pad

            val stepX = size.width / (points.size - 1)
            fun y(v: Double) = (size.height * (1 - (v - low) / (high - low))).toFloat()

            // A midline, so a trace that barely moves still reads as a value
            // rather than as a straight line drawn at random.
            drawLine(
                muted.copy(alpha = 0.2f),
                Offset(0f, y((low + high) / 2)),
                Offset(size.width, y((low + high) / 2)),
                strokeWidth = 1f,
            )

            val line = Path().apply {
                moveTo(0f, y(points[0]))
                for (i in 1 until points.size) lineTo(stepX * i, y(points[i]))
            }
            val area = Path().apply {
                addPath(line)
                lineTo(stepX * (points.size - 1), size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(
                area,
                Brush.verticalGradient(
                    listOf(colour.copy(alpha = 0.22f), colour.copy(alpha = 0f)),
                ),
            )
            drawPath(
                line,
                colour,
                style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}
