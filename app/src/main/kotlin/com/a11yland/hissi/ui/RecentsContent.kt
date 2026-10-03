package com.a11yland.hissi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.a11yland.hissi.R

// Recent searches as tappable chips while the search field is focused and
// empty — the Android counterpart of RecentSearchChips. (The nearby-stations
// section follows as a later increment within phase 3.)
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecentsContent(search: SearchViewModel, onPick: (String) -> Unit) {
    val recents by search.recents.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        NearbySection(search = search, onSelect = onPick)

        if (recents.isEmpty()) {
            Text(
                stringResource(R.string.no_recents_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (term in recents) {
                    AssistChip(
                        onClick = { onPick(term) },
                        label = { Text(term) },
                        trailingIcon = {
                            val removeLabel = stringResource(R.string.recent_remove, term)
                            // The tap target fills the chip height (32 dp) —
                            // a bare 16 dp icon is untappable; 48 dp would
                            // blow the chip up.
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clickable(onClickLabel = removeLabel) {
                                        search.removeRecent(term)
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = removeLabel,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}
