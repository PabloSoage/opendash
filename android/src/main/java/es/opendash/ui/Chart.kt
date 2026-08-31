package es.opendash.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import es.opendash.data.Series
import java.util.Locale

/**
 * A parameter, its current value, and where it has been.
 *
 * The line is drawn against the range actually seen rather than the parameter's
 * declared limits: coolant temperature nominally spans -40 to 215 °C, so
 * plotting it against that turns a warm-up into a flat line. A little headroom
 * keeps it from touching the edges.
 */
@Composable
fun ParameterChart(
    name: String,
    unit: String,
    series: Series,
    modifier: Modifier = Modifier,
    colour: Color = MaterialTheme.colorScheme.primary,
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Text(
                String.format(Locale.ROOT, "%.2f %s", series.last, unit),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(72.dp)) {
            val points = series.snapshot()
            if (points.size < 2) return@Canvas

            var low = series.min
            var high = series.max
            if (high - low < 1e-9) {
                low -= 1.0
                high += 1.0
            }
            val pad = (high - low) * 0.1
            low -= pad
            high += pad

            val stepX = size.width / (points.size - 1)
            fun y(v: Double) = (size.height * (1 - (v - low) / (high - low))).toFloat()

            val path = Path().apply {
                moveTo(0f, y(points[0]))
                for (i in 1 until points.size) lineTo(stepX * i, y(points[i]))
            }
            drawPath(path, colour, style = Stroke(width = 2.5f))
            drawLine(
                colour.copy(alpha = 0.25f),
                Offset(0f, y((low + high) / 2)),
                Offset(size.width, y((low + high) / 2)),
                strokeWidth = 1f,
            )
        }
    }
}
