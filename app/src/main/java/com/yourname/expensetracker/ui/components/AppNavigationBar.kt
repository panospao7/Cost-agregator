package com.yourname.expensetracker.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

data class NavItem(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon
)

val navItems = listOf(
    NavItem("Home", Icons.Rounded.Home, Icons.Filled.Home),
    NavItem("Activity", Icons.Rounded.Receipt, Icons.Filled.Receipt),
    NavItem("Review", Icons.Rounded.FactCheck, Icons.Filled.FactCheck),
    NavItem("Plan", Icons.Rounded.CalendarMonth, Icons.Filled.CalendarMonth),
    NavItem("Analytics", Icons.Rounded.Insights, Icons.Filled.Insights),
    NavItem("Map", Icons.Rounded.Map, Icons.Filled.Map)
)

@Composable
fun AppNavigationBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    pendingReviewCount: Int,
    modifier: Modifier = Modifier
) {
    NavigationBar(modifier = modifier, tonalElevation = 0.dp) {
        navItems.forEachIndexed { index, item ->
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
                            contentDescription = item.label
                        )
                    }
                },
                label = { Text(item.label) }
            )
        }
    }
}
