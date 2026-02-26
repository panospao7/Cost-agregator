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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.Category

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryScreen(
    onDismiss: () -> Unit,
    viewModel: CategoryViewModel = hiltViewModel()
) {
    val categories by viewModel.categories.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage Categories") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Category")
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
            items(categories) { category ->
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
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val color = remember(category.color) {
                try {
                    Color(android.graphics.Color.parseColor(category.color))
                } catch (e: Exception) {
                    Color.Gray
                }
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(color, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(category.icon, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(category.name, style = MaterialTheme.typography.bodyLarge)
            if (category.isDefault) {
                Spacer(modifier = Modifier.weight(1f))
                Text("Default", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
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
    
    val isValidName = name.matches(Regex("^[a-zA-Z0-9\\s\\-_'.]*$"))
    val isValidColor = color.matches(Regex("^#[0-9A-Fa-f]{6}$"))
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Category") },
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
                    label = { Text("Name") },
                    isError = isNameError || (name.isNotEmpty() && !isValidName),
                    supportingText = { 
                        when {
                            isNameError -> Text("Name cannot be empty")
                            name.isNotEmpty() && !isValidName -> Text("Invalid characters")
                        }
                    },
                    singleLine = true
                )
                OutlinedTextField(
                    value = icon,
                    onValueChange = { if (it.length <= 10) icon = it },
                    label = { Text("Icon (Emoji)") },
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
                    label = { Text("Color (Hex)") },
                    isError = isColorError || (color.isNotEmpty() && !isValidColor),
                    supportingText = {
                        when {
                            isColorError -> Text("Invalid color format")
                            color.isNotEmpty() && !isValidColor -> Text("Use #RRGGBB format")
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
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
