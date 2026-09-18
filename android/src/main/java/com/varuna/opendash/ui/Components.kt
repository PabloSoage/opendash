package com.varuna.opendash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The handful of shapes every screen is built from.
 *
 * Keeping them here rather than repeating a Card and a Column on each screen is
 * what makes the app look like one app: the same padding, the same corner
 * radius, the same distance between a heading and what it heads.
 */

/** A heading with room above it, unless it is the first thing on the screen. */
@Composable
fun Section(title: String, modifier: Modifier = Modifier, first: Boolean = false) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = if (first) 0.dp else 20.dp, bottom = 8.dp),
    )
}

/** A raised panel. Everything that belongs together goes in one. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(14.dp),
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/**
 * A panel that opens. The heading says what is inside and what it is set to,
 * so it can stay shut.
 *
 * A settings field is touched once and read never. Four panels of them stacked
 * open is what the link screen was: on a phone held upright the part that says
 * whether anything is connected was below three screenfuls of text boxes. The
 * subtitle is what makes folding them honest — shut, it still shows the value,
 * so nothing is hidden, only quiet.
 */
@Composable
fun Fold(
    title: String,
    subtitle: String? = null,
    open: Boolean,
    onToggle: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Panel {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (open) content()
    }
}

/**
 * One rung of a chain that has to be climbed in order: Wi-Fi, then the adapter,
 * then the bus.
 *
 * Three of these say where you are and what is missing in the height of one
 * paragraph. The alternative — a status line per panel, each its own colour,
 * scattered down the screen — makes the reader assemble the chain themselves.
 */
@Composable
fun Step(label: String, detail: String, done: Boolean, busy: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
        } else {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (done) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.outlineVariant
                    )
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(76.dp),
        )
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = if (done) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Label on the left, value on the right, both on one line. */
@Composable
fun Field(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

/** A coloured dot and a word: the state of something, at a glance. */
@Composable
fun StatusLine(text: String, colour: Color, busy: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
        } else {
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(colour))
        }
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/** Small print under a control: what it does, and why it is set the way it is. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth(),
    )
}

/** Something that went wrong, said plainly. */
@Composable
fun ErrorLine(text: String?) {
    if (text.isNullOrBlank()) return
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * A labelled dropdown.
 *
 * A row of chips is fine for three or four choices and stops working at twelve:
 * the window sizes alone run from fifty milliseconds to the whole recording, and
 * as chips they either wrap onto three lines or hide behind a horizontal scroll
 * nobody notices. A dropdown says what is selected without spending the width.
 */
@Composable
fun <T> Combo(
    label: String,
    value: T,
    options: List<T>,
    render: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 12.dp)) {
            Text(
                label + "  " + render(value),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(render(option)) },
                    onClick = {
                        onSelect(option)
                        open = false
                    },
                    trailingIcon = {
                        if (option == value) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        }
                    },
                )
            }
        }
    }
}
