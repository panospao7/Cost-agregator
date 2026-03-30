package com.yourname.expensetracker.ui.components.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.ReceiptItemCategorization
import com.yourname.expensetracker.ui.theme.SemanticColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReceiptItemBreakdownCard(
    items: List<ReceiptItemCategorization>,
    categories: List<Category>,
    isLoading: Boolean,
    onItemCategoryChanged: (ReceiptItemCategorization, Category?) -> Unit,
    onShowRationale: (ReceiptItemCategorization) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "AI Item Breakdown (${items.size})",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                
                // Average confidence badge
                val avgConfidence = items.map { it.confidence }.average()
                ConfidenceBadge(confidence = avgConfidence.toFloat())
            }
            
            Divider()
            
            // Items list
            items.forEachIndexed { index, item ->
                CategorizedItemRow(
                    item = item,
                    categories = categories,
                    onCategoryChanged = { category ->
                        onItemCategoryChanged(item, category)
                    },
                    onShowRationale = { onShowRationale(item) }
                )
                
                if (index < items.size - 1) {
                    Divider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
private fun CategorizedItemRow(
    item: ReceiptItemCategorization,
    categories: List<Category>,
    onCategoryChanged: (Category?) -> Unit,
    onShowRationale: () -> Unit
) {
    var showCategoryPicker by remember { mutableStateOf(false) }
    
    val displayCategoryName = item.userCorrectedCategoryName 
        ?: item.suggestedCategoryName 
        ?: "Select..."
    
    val isUserCorrected = item.userCorrectedCategoryId != null
    val needsReview = item.confidence < 0.7f && !isUserCorrected
    
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Item description and amount
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item.itemDescription,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "€${String.format("%.2f", item.itemAmount)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
        
        // Category chip and confidence
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Category chip with border color
            val chipContainerColor = when {
                needsReview -> SemanticColors.WarningOrange.copy(alpha = 0.2f)
                isUserCorrected -> SemanticColors.PrimaryIndigo.copy(alpha = 0.2f)
                item.confidence >= 0.9f -> SemanticColors.SuccessGreen.copy(alpha = 0.2f)
                else -> MaterialTheme.colorScheme.surface
            }
            
            Surface(
                color = chipContainerColor,
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = when {
                        needsReview -> SemanticColors.WarningOrange
                        isUserCorrected -> SemanticColors.PrimaryIndigo
                        item.confidence >= 0.9f -> SemanticColors.SuccessGreen
                        else -> MaterialTheme.colorScheme.outline
                    }
                ),
                modifier = Modifier.clickable { showCategoryPicker = true }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isUserCorrected) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Text(
                        text = displayCategoryName,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            
            // Confidence badge
            if (!isUserCorrected) {
                ConfidenceBadge(confidence = item.confidence)
            }
            
            // Info button
            if (item.aiRationale != null) {
                IconButton(
                    onClick = onShowRationale,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Why this category?",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        
        // Show alternatives if confidence is low and not corrected
        if (needsReview && item.alternativeCategoriesJson != null) {
            AlternativeCategoriesRow(
                alternativesJson = item.alternativeCategoriesJson,
                categories = categories,
                onSelect = onCategoryChanged
            )
        }
    }
    
    // Category picker dialog
    if (showCategoryPicker) {
        CategoryPickerDialog(
            categories = categories,
            selectedCategoryId = item.userCorrectedCategoryId ?: item.suggestedCategoryId,
            onCategorySelected = { category ->
                onCategoryChanged(category)
                showCategoryPicker = false
            },
            onDismiss = { showCategoryPicker = false }
        )
    }
}

@Composable
private fun ConfidenceBadge(confidence: Float) {
    val (icon, color, label) = when {
        confidence >= 0.9f -> Triple(Icons.Default.Check, SemanticColors.SuccessGreen, "High")
        confidence >= 0.7f -> Triple(Icons.Default.Check, SemanticColors.WarningOrange, "Good")
        else -> Triple(Icons.Default.Warning, SemanticColors.DangerRed, "Low")
    }
    
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = color
            )
            Text(
                text = "${(confidence * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlternativeCategoriesRow(
    alternativesJson: String,
    categories: List<Category>,
    onSelect: (Category) -> Unit
) {
    // Parse alternatives from JSON
    val alternatives = remember(alternativesJson) {
        parseAlternatives(alternativesJson, categories).take(3)
    }
    
    if (alternatives.isNotEmpty()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Or:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
            
            alternatives.forEach { category ->
                SuggestionChip(
                    onClick = { onSelect(category) },
                    label = {
                        Text(
                            text = category.name,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun CategoryPickerDialog(
    categories: List<Category>,
    selectedCategoryId: Long?,
    onCategorySelected: (Category) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Category") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.forEach { category ->
                    val isSelected = category.id == selectedCategoryId
                    
                    ListItem(
                        headlineContent = { 
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(category.icon)
                                Text(category.name)
                            }
                        },
                        leadingContent = if (isSelected) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected"
                                )
                            }
                        } else null,
                        modifier = Modifier.clickable { onCategorySelected(category) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun parseAlternatives(
    json: String,
    categories: List<Category>
): List<Category> {
    return try {
        val alternatives = mutableListOf<Category>()
        val array = org.json.JSONArray(json)
        
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val categoryId = obj.optLong("id", -1)
            val categoryName = obj.optString("name", "")
            
            // Find matching category
            val category = categories.find { it.id == categoryId }
                ?: categories.find { it.name == categoryName }
            
            category?.let { alternatives.add(it) }
        }
        
        alternatives
    } catch (e: Exception) {
        emptyList()
    }
}
