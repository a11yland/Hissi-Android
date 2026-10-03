package com.a11yland.hissi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.a11yland.hissi.R
import com.a11yland.hissi.core.ElevatorStatus
import com.a11yland.hissi.core.MonitoredElevator

// One elevator: status shape + colour, station, description, status line,
// data age, stale warning — the Android counterpart of ElevatorRowView. The
// whole row is one TalkBack element (status spoken first).
@Composable
fun ElevatorRow(
    elevator: MonitoredElevator,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val status = ElevatorStatus.from(elevator.isWorking)
    val color = statusColor(status)
    val statusText = statusLabel(status)
    val ageText = lastUpdatedText(elevator)
    val staleText = if (elevator.isDataStale) stringResource(R.string.status_stale) else null
    // One TalkBack element with the status spoken first (the visual order
    // leads with the station) — mirrors the iOS accessibilityLabel.
    val spokenRow = listOfNotNull(
        statusText,
        elevator.stationName,
        elevator.elevatorDescription.takeIf { it.isNotEmpty() },
        ageText,
        staleText,
    ).joinToString(", ")

    Row(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = status.icon.vector(),
            contentDescription = null,
            tint = statusSymbolColor(status),
            modifier = Modifier.padding(top = 2.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .clearAndSetSemantics { contentDescription = spokenRow },
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(elevator.stationName, style = MaterialTheme.typography.bodyLarge)
            if (elevator.elevatorDescription.isNotEmpty()) {
                Text(
                    elevator.elevatorDescription,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                statusText,
                style = MaterialTheme.typography.labelMedium,
                color = color,
            )
            Text(
                ageText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            if (staleText != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = staleWarningColor(),
                        modifier = Modifier.padding(top = 1.dp),
                    )
                    Text(
                        staleText,
                        style = MaterialTheme.typography.labelSmall,
                        color = staleWarningColor(),
                    )
                }
            }
        }
        trailing?.invoke()
    }
}

// Per-elevator favorite toggle, mirroring the iOS star.
@Composable
fun FavoriteStar(isFavorite: Boolean, onToggle: () -> Unit) {
    val label = stringResource(if (isFavorite) R.string.fav_remove else R.string.fav_add)
    IconButton(
        onClick = onToggle,
        modifier = Modifier.clearAndSetSemantics { contentDescription = label },
    ) {
        Icon(
            imageVector = if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
            contentDescription = null,
            // Lilac, never yellow — yellow means "unbekannt" in this palette.
            tint = if (isFavorite) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
