package com.yourname.expensetracker.ui.screens.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.yourname.expensetracker.ui.components.asString
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

    // R18b: bounded "changes paused during backup/restore" message for writes.
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.writeBlockedMessages.collect { message ->
            snackbarHostState.showSnackbar(message.asString(context))
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message.asString(context))
        }
    }
    val renameDialog by viewModel.renameDialog.collectAsState()
    val deleteDialog by viewModel.deleteDialog.collectAsState()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            val fabDescription = stringResource(R.string.category_fab_add)
            FloatingActionButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.semantics { contentDescription = fabDescription }
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
                CategoryItem(
                    category = category,
                    onRename = { viewModel.requestRename(category.id) },
                    onDelete = { viewModel.requestDelete(category.id) }
                )
            }
        }

        renameDialog?.let { state ->
            RenameCategoryDialog(
                state = state,
                onDismiss = viewModel::dismissRename,
                onConfirm = { name -> viewModel.renameCategory(state.categoryId, name) }
            )
        }

        deleteDialog?.let { state ->
            DeleteCategoryDialog(
                state = state,
                onSelectReplacement = viewModel::selectReplacement,
                onDismiss = viewModel::dismissDelete,
                onConfirm = { replacementId -> viewModel.confirmDelete(state.categoryId, replacementId) }
            )
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
fun CategoryItem(
    category: Category,
    onRename: () -> Unit = {},
    onDelete: () -> Unit = {}
) {
    val color = remember(category.color) {
        try {
            Color(android.graphics.Color.parseColor(category.color))
        } catch (e: Exception) {
            Color.Gray
        }
    }
    
    val categoryLabel = stringResource(R.string.category_label)
    val defaultLabel = stringResource(R.string.category_label_default)
    val cardDescription = remember(category.name, category.isDefault, categoryLabel, defaultLabel) {
        buildString {
            append(category.name)
            append(" ")
            append(categoryLabel)
            if (category.isDefault) {
                append(", ")
                append(defaultLabel)
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
            val iconDescription = stringResource(R.string.a11y_category_icon, category.name)
            Text(
                category.icon,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { contentDescription = iconDescription }
            )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(category.name, style = MaterialTheme.typography.bodyLarge)
            if (category.isDefault) {
                Spacer(modifier = Modifier.weight(1f))
                val defaultLabel = stringResource(R.string.category_label_default)
                Text(
                    stringResource(R.string.category_label_default),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                    modifier = Modifier.semantics { contentDescription = defaultLabel }
                )
            } else {
                // Overflow menu (rename, delete) on non-default rows only.
                Spacer(modifier = Modifier.weight(1f))
                CategoryOverflowMenu(
                    categoryName = category.name,
                    onRename = onRename,
                    onDelete = onDelete
                )
            }
        }
    }
}

@Composable
private fun CategoryOverflowMenu(
    categoryName: String,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val menuDescription = stringResource(R.string.category_manage_menu_cd, categoryName)
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = menuDescription }
        ) {
            Icon(Icons.Default.MoreVert, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.category_manage_rename)) },
                onClick = {
                    expanded = false
                    onRename()
                },
                modifier = Modifier.heightIn(min = 48.dp)
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.category_manage_delete)) },
                onClick = {
                    expanded = false
                    onDelete()
                },
                modifier = Modifier.heightIn(min = 48.dp)
            )
        }
    }
}

@Composable
fun RenameCategoryDialog(
    state: RenameDialogState,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember(state.categoryId) { mutableStateOf(state.currentName) }
    val canConfirm = name.isNotBlank() && !state.inFlight

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.category_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MAX_NAME_LENGTH) },
                label = { Text(stringResource(R.string.category_dialog_label_name)) },
                isError = state.error != null,
                supportingText = { state.error?.let { Text(it.asString()) } },
                singleLine = true,
                enabled = !state.inFlight
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) },
                enabled = canConfirm,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.category_manage_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun DeleteCategoryDialog(
    state: DeleteDialogState,
    onSelectReplacement: (Long) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    val replacement = state.replacement
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.category_delete_title, state.categoryName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val count = state.expenseCount) {
                    is ExpenseCountState.Loading ->
                        Text(stringResource(R.string.category_delete_count_loading))
                    is ExpenseCountState.Unavailable ->
                        Text(stringResource(R.string.category_delete_count_unavailable))
                    is ExpenseCountState.Loaded ->
                        if (replacement != null) {
                            Text(
                                pluralStringResource(
                                    R.plurals.category_delete_move_summary,
                                    count.count,
                                    count.count,
                                    replacement.name
                                )
                            )
                        }
                }
                Text(
                    stringResource(R.string.category_delete_budget_note),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    stringResource(R.string.category_delete_picker_label),
                    style = MaterialTheme.typography.labelLarge
                )
                LazyColumn(
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .selectableGroup()
                ) {
                    items(state.candidates, key = { it.id }) { candidate ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(
                                    selected = candidate.id == state.replacementId,
                                    enabled = !state.inFlight,
                                    role = Role.RadioButton,
                                    onClick = { onSelectReplacement(candidate.id) }
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = candidate.id == state.replacementId,
                                onClick = null,
                                enabled = !state.inFlight
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "${candidate.icon} ${candidate.name}",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
                state.error?.let {
                    Text(
                        it.asString(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { replacement?.let { onConfirm(it.id) } },
                enabled = state.canConfirm,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.category_manage_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

private const val MAX_NAME_LENGTH = 50

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
