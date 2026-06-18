package com.yourname.expensetracker.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.R

data class NavItem(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon
) {
    /**
     * Get the resolved label string.
     * Use this in Composables.
     */
    @Composable
    fun label(): String = stringResource(labelRes)
}

val navItems = listOf(
    NavItem(R.string.nav_home, Icons.Rounded.Home, Icons.Filled.Home),
    NavItem(R.string.nav_activity, Icons.Rounded.Receipt, Icons.Filled.Receipt),
    NavItem(R.string.nav_review, Icons.Rounded.FactCheck, Icons.Filled.FactCheck),
    NavItem(R.string.nav_plan, Icons.Rounded.CalendarMonth, Icons.Filled.CalendarMonth),
    NavItem(R.string.nav_analytics, Icons.Rounded.Insights, Icons.Filled.Insights),
    NavItem(R.string.nav_map, Icons.Rounded.Map, Icons.Filled.Map)
)

@Composable
fun AppNavigationBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    pendingReviewCount: Int,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val useOverflow = navItems.size > 5 && configuration.screenWidthDp < 420
    val primaryItems = if (useOverflow) navItems.take(4) else navItems
    val overflowItems = if (useOverflow) navItems.drop(primaryItems.size) else emptyList()
    val compactLabels = navItems.size > 5
    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    var overflowExpanded by remember { mutableStateOf(false) }

    NavigationBar(modifier = modifier, tonalElevation = 0.dp) {
        primaryItems.forEachIndexed { index, item ->
            NavigationBarItem(
                selected = selectedTab == index,
                onClick = { onTabSelected(index) },
                icon = {
                    BadgedBox(
                        badge = {
                            if (index == 2 && pendingReviewCount > 0) {
                                Badge { Text(pendingReviewCount.toString()) }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (selectedTab == index) item.selectedIcon else item.icon,
                            contentDescription = item.label()
                        )
                    }
                },
                label = { Text(item.label()) },
                alwaysShowLabel = !compactLabels
            )
        }

        if (overflowItems.isNotEmpty()) {
            val overflowLabel = stringResource(R.string.nav_more)
            NavigationBarItem(
                selected = selectedTab >= primaryItems.size,
                onClick = { overflowExpanded = true },
                icon = {
                    Box {
                        Icon(
                            imageVector = Icons.Rounded.MoreHoriz,
                            contentDescription = overflowLabel
                        )

                        DropdownMenu(
                            expanded = overflowExpanded,
                            onDismissRequest = { overflowExpanded = false },
                            modifier = Modifier.widthIn(max = with(density) { (screenWidthPx * 0.9f).toDp() })
                        ) {
                            overflowItems.forEachIndexed { overflowIndex, overflowItem ->
                                val targetIndex = primaryItems.size + overflowIndex
                                DropdownMenuItem(
                                    text = { Text(overflowItem.label()) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (selectedTab == targetIndex) overflowItem.selectedIcon else overflowItem.icon,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        overflowExpanded = false
                                        onTabSelected(targetIndex)
                                    }
                                )
                            }
                        }
                    }
                },
                label = { Text(overflowLabel) },
                alwaysShowLabel = !compactLabels
            )
        }
    }
}
