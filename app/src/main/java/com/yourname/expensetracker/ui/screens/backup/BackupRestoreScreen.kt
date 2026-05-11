package com.yourname.expensetracker.ui.screens.backup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.R

/**
 * Backup & Restore screen allowing users to create encrypted .costbackup bundles
 * and restore from existing backups.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(
    onNavigateBack: () -> Unit,
    viewModel: BackupRestoreViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Password state for create backup
    var createPassword by remember { mutableStateOf("") }
    var createPasswordVisible by remember { mutableStateOf(false) }

    // Password state for restore
    var restorePassword by remember { mutableStateOf("") }
    var restorePasswordVisible by remember { mutableStateOf(false) }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf<String?>(null) }

    // File picker launcher for restore
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedFileUri = uri
            // Extract filename from URI for display
            val fileName = uri.lastPathSegment?.substringAfterLast('/')
                ?: uri.lastPathSegment
            selectedFileName = fileName
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Show error/success messages
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }
    LaunchedEffect(uiState.successMessage) {
        uiState.successMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSuccess()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_restore_title)) },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text(stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // ── Last Backup Info ──────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.backup_restore_last_backup,
                                uiState.lastBackupDate ?: stringResource(R.string.backup_restore_no_backup)),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (uiState.lastBackupFile != null) {
                            Text(
                                text = uiState.lastBackupFile!!.substringAfterLast('/'),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            // ── Create Backup Section ─────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.backup_restore_create_button),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    OutlinedTextField(
                        value = createPassword,
                        onValueChange = { createPassword = it },
                        label = { Text(stringResource(R.string.backup_restore_password_label)) },
                        placeholder = { Text(stringResource(R.string.backup_restore_password_placeholder)) },
                        singleLine = true,
                        visualTransformation = if (createPasswordVisible)
                            VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { createPasswordVisible = !createPasswordVisible }) {
                                Icon(
                                    if (createPasswordVisible) Icons.Default.VisibilityOff
                                    else Icons.Default.Visibility,
                                    contentDescription = if (createPasswordVisible)
                                        "Hide password" else "Show password"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.isBackingUp
                    )

                    Button(
                        onClick = { viewModel.createBackup(createPassword) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.isBackingUp && createPassword.isNotBlank()
                    ) {
                        if (uiState.isBackingUp) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.backup_restore_creating))
                        } else {
                            Icon(Icons.Default.Lock, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.backup_restore_create_button))
                        }
                    }
                }
            }

            // ── Restore Backup Section ────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.backup_restore_restore_button),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    // Selected file display
                    OutlinedTextField(
                        value = selectedFileName ?: "",
                        onValueChange = {},
                        label = { Text(stringResource(R.string.backup_restore_select_file)) },
                        readOnly = true,
                        enabled = !uiState.isRestoring,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            TextButton(
                                onClick = { filePickerLauncher.launch("application/octet-stream") }
                            ) {
                                Text(
                                    if (selectedFileUri == null) "Select"
                                    else "Change"
                                )
                            }
                        }
                    )

                    OutlinedTextField(
                        value = restorePassword,
                        onValueChange = { restorePassword = it },
                        label = { Text(stringResource(R.string.backup_restore_password_label)) },
                        placeholder = { Text(stringResource(R.string.backup_restore_password_placeholder)) },
                        singleLine = true,
                        visualTransformation = if (restorePasswordVisible)
                            VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { restorePasswordVisible = !restorePasswordVisible }) {
                                Icon(
                                    if (restorePasswordVisible) Icons.Default.VisibilityOff
                                    else Icons.Default.Visibility,
                                    contentDescription = if (restorePasswordVisible)
                                        "Hide password" else "Show password"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.isRestoring
                    )

                    Button(
                        onClick = {
                            selectedFileUri?.let { uri ->
                                viewModel.restoreBackup(uri, restorePassword)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.isRestoring && selectedFileUri != null && restorePassword.isNotBlank()
                    ) {
                        if (uiState.isRestoring) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.backup_restore_restoring))
                        } else {
                            Icon(Icons.Default.Restore, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.backup_restore_restore_button))
                        }
                    }
                }
            }

            // ── Restart Required Banner ───────────────────────────────
            // P7-P1-08: This banner is NOT dismissable. After a successful restore the
            // injected Room instance is stale and writes are blocked by maintenance mode.
            // The only safe action is to kill the process so Android relaunches with a
            // fresh Room binding.
            if (uiState.restartRequired) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.backup_restore_restart_required),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(onClick = {
                                // P7-P1-08: Force-kill the process. Android will relaunch
                                // the activity with a fresh Room instance on next user tap.
                                Runtime.getRuntime().exit(0)
                            }) {
                                Text(stringResource(R.string.backup_restore_restart_now))
                            }
                        }
                    }
                }
            }

            // Spacer at bottom for scrolling
            Spacer(Modifier.height(32.dp))
        }
    }
}
