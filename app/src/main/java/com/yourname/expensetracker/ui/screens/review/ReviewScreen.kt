package com.yourname.expensetracker.ui.screens.review

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.foundation.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PendingReview
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    viewModel: ReviewViewModel = hiltViewModel()
) {
    val pendingReviews by viewModel.pendingReviews.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    var editingReview by remember { mutableStateOf<PendingReview?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review Queue ($pendingCount)") }
            )
        }
    ) { padding ->
        if (pendingReviews.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("✅", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "All caught up!",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "No transactions need your review",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        "Swipe through to approve or reject",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                items(pendingReviews, key = { it.id }) { review ->
                    ReviewCard(
                        review = review,
                        onApprove = { viewModel.approveReview(review.id) },
                        onReject = { viewModel.rejectReview(review.id) },
                        onEdit = { editingReview = review }
                    )
                }
            }
        }

        // Edit dialog
        if (editingReview != null) {
            EditReviewDialog(
                review = editingReview!!,
                categories = categories,
                onDismiss = { editingReview = null },
                onSave = { amount, merchant, categoryId ->
                    viewModel.approveReviewWithEdits(
                        reviewId = editingReview!!.id,
                        finalAmount = amount,
                        finalMerchant = merchant,
                        finalCategoryId = categoryId
                    )
                    editingReview = null
                }
            )
        }
    }
}

@Composable
fun ReviewCard(
    review: PendingReview,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onEdit: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }
    val confidenceColor = when {
        review.confidence >= 0.75f -> Color(0xFF4CAF50)
        review.confidence >= 0.60f -> Color(0xFFFFC107)
        else -> Color(0xFFFF5722)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Confidence indicator bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = review.packageName.split(".").lastOrNull() ?: review.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(confidenceColor, RoundedCornerShape(4.dp))
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${(review.confidence * 100).toInt()}% sure",
                        style = MaterialTheme.typography.labelSmall,
                        color = confidenceColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Transaction details
            Text(
                text = review.suggestedMerchant,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = dateFormat.format(Date(review.createdAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Amount
            Text(
                text = "${review.suggestedCurrency} ${String.format("%.2f", review.suggestedAmount)}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )

            // Original notification preview
            review.notificationTitle?.let { title ->
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        review.notificationText?.let { text ->
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Reject
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reject")
                }

                // Edit
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier.weight(0.5f)
                ) {
                    Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp))
                }

                // Approve
                Button(
                    onClick = onApprove,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50)
                    )
                ) {
                    Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Approve")
                }
            }
        }
    }
}

@Composable
fun EditReviewDialog(
    review: PendingReview,
    categories: List<Category>,
    onDismiss: () -> Unit,
    onSave: (Double?, String?, Long?) -> Unit
) {
    var amount by remember { mutableStateOf(String.format("%.2f", review.suggestedAmount)) }
    var merchant by remember { mutableStateOf(review.suggestedMerchant) }
    var selectedCategoryId by remember { mutableStateOf(review.suggestedCategoryId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Transaction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text("Merchant") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount (${review.suggestedCurrency})") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    "Category",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )

                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .heightIn(max = 200.dp)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    categories.forEach { category ->
                        Surface(
                            onClick = { selectedCategoryId = category.id },
                            shape = RoundedCornerShape(8.dp),
                            color = if (selectedCategoryId == category.id)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(category.icon)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(category.name, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsedAmount = amount.toDoubleOrNull()
                    val editedAmount = if (parsedAmount != null && kotlin.math.abs(parsedAmount - review.suggestedAmount) > 0.001) parsedAmount else null
                    val editedMerchant = merchant.takeIf { it != review.suggestedMerchant }
                    val editedCategory = selectedCategoryId.takeIf { it != review.suggestedCategoryId }
                    onSave(editedAmount, editedMerchant, editedCategory)
                }
            ) {
                Text("Save & Approve")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
