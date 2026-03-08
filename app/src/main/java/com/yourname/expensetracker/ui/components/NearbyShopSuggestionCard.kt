package com.yourname.expensetracker.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.domain.location.NearbyPoi

/**
 * A card showing one Overpass POI candidate.
 *
 * Displayed in [SpendingMapScreen] when Overpass returns multiple shops
 * near the device location and the user must pick the right one.
 */
@Composable
fun NearbyShopSuggestionCard(
    poi: NearbyPoi,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = poi.name,
                    style = MaterialTheme.typography.bodyLarge
                )
                if (poi.displayAddress != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = poi.displayAddress,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (poi.category != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = poi.category,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "%.0f m".format(poi.distanceMetres),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
