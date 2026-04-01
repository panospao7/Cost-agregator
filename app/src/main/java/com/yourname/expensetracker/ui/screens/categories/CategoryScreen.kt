package com.yourname.expensetracker.ui.screens.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.ui.theme.SemanticColors
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryScreen(
    onDismiss: () -> Unit,
    viewModel: CategoryViewModel = hiltViewModel()
) {
    val categories by viewModel.categories.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = SemanticColors.BaseNavy,
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        stringResource(R.string.screen_manage_categories),
                        color = SemanticColors.TextPrimary
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SemanticColors.BaseNavy,
                    titleContentColor = SemanticColors.TextPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.semantics { contentDescription = stringResource(R.string.category_fab_add) }
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(categories, key = { it.id }) { category ->
                CategoryItem(category)
            }
        }
        
        if (showAddDialog) {
            AddCategoryDialog(
                onDismiss = { showAddDialog = false },
                onAdd = { name, icon, color ->
                    viewModel.addCategory(name, icon, color)
                    showAddDialog = false
                }
            )
        }
    }
}

@Composable
fun CategoryItem(category: Category) {
    val color = remember(category.color) {
        try {
            Color(android.graphics.Color.parseColor(category.color))
        } catch (e: Exception) {
            Color.Gray
        }
    }
    
    val cardDescription = remember(category.name, category.isDefault) {
        buildString {
            append(category.name)
            append(" ")
            append(stringResource(R.string.category_label))
            if (category.isDefault) {
                append(", ")
                append(stringResource(R.string.category_label_default))
            }
        }
    }

    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.semantics { contentDescription = cardDescription }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(color, CircleShape),
                contentAlignment = Alignment.Center
            ) {
            Text(
                category.icon,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { contentDescription = stringResource(R.string.a11y_category_icon, category.name) }
            )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(category.name, style = MaterialTheme.typography.bodyLarge)
            if (category.isDefault) {
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    stringResource(R.string.category_label_default),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                    modifier = Modifier.semantics { contentDescription = stringResource(R.string.category_label_default) }
                )
            }
        }
    }
}

@Composable
fun AddCategoryDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("📦") }
    var color by remember { mutableStateOf("#607D8B") }
    var isNameError by remember { mutableStateOf(false) }
    var isColorError by remember { mutableStateOf(false) }
    
    // Remember validation results to avoid recalculating regex on every recomposition
    val isValidName = remember(name) { name.matches(Regex("^[a-zA-Z0-9\\s\\-_'.]*$")) }
    val isValidColor = remember(color) { color.matches(Regex("^#[0-9A-Fa-f]{6}$")) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.category_dialog_new_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { 
                        if (it.matches(Regex("^[a-zA-Z0-9\\s\\-_'.]*$")) || it.isEmpty()) {
                            name = it.take(50)
                        }
                        if (it.isNotBlank()) isNameError = false
                    },
                    label = { Text(stringResource(R.string.category_dialog_label_name)) },
                    isError = isNameError || (name.isNotEmpty() && !isValidName),
                    supportingText = { 
                        when {
                            isNameError -> Text(stringResource(R.string.category_error_name_empty))
                            name.isNotEmpty() && !isValidName -> Text(stringResource(R.string.category_error_invalid_characters))
                        }
                    },
                    singleLine = true
                )
                OutlinedTextField(
                    value = icon,
                    onValueChange = { if (it.length <= 10) icon = it },
                    label = { Text(stringResource(R.string.category_dialog_label_icon)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = color,
                    onValueChange = { 
                        if (it.matches(Regex("^#?[0-9A-Fa-f]*$")) && it.length <= 7) {
                            color = if (it.startsWith("#")) it else "#$it"
                        }
                        isColorError = false
                    },
                    label = { Text(stringResource(R.string.category_dialog_label_color)) },
                    isError = isColorError || (color.isNotEmpty() && !isValidColor),
                    supportingText = {
                        when {
                            isColorError -> Text(stringResource(R.string.category_error_invalid_color))
                            color.isNotEmpty() && !isValidColor -> Text(stringResource(R.string.category_error_hex_format))
                        }
                    },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { 
                    val finalName = name.trim()
                    val finalColor = if (color.startsWith("#")) color else "#$color"
                    
                    when {
                        finalName.isBlank() -> isNameError = true
                        !isValidColor -> isColorError = true
                        else -> onAdd(finalName, icon, finalColor)
                    }
                }
            ) {
                Text(stringResource(R.string.category_action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
