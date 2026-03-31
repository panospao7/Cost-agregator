package com.yourname.expensetracker.ui.components.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.R
import com.yourname.expensetracker.ui.theme.Dimens
import com.yourname.expensetracker.ui.theme.ExpenseTrackerTheme
import com.yourname.expensetracker.ui.theme.SemanticColors

/**
 * Predefined error types with appropriate icons and messages.
 */
enum class ErrorType(
    val icon: ImageVector,
    val defaultTitle: String,
    val defaultMessage: String,
    val isRetryable: Boolean = true
) {
    NETWORK(
        Icons.Default.CloudOff,
        "Connection Error",
        "Unable to connect to the server. Please check your internet connection and try again.",
        true
    ),
    SERVER(
        Icons.Default.Warning,
        "Server Error",
        "Something went wrong on our end. Please try again in a few moments.",
        true
    ),
    UNKNOWN(
        Icons.Default.ErrorOutline,
        "Something Went Wrong",
        "An unexpected error occurred. Please try again.",
        true
    ),
    TIMEOUT(
        Icons.Default.Warning,
        "Request Timeout",
        "The request took too long to complete. Please try again.",
        true
    ),
    NOT_FOUND(
        Icons.Default.ErrorOutline,
        "Not Found",
        "The requested item could not be found.",
        false
    ),
    AI_PROCESSING(
        Icons.Default.Warning,
        "AI Processing Failed",
        "Unable to process with AI. Please check your settings and try again.",
        true
    )
}

/**
 * A reusable error state component with retry functionality.
 * 
 * @param type The error type (determines icon, default title/message, and retryability)
 * @param title Custom title (overrides type default)
 * @param message Custom message (overrides type default)
 * @param isRetrying Whether a retry is currently in progress
 * @param onRetry Callback when retry is clicked (only if isRetryable)
 * @param onDismiss Callback when dismiss is clicked (optional)
 * @param modifier Modifier for the component
 */
@Composable
fun ErrorState(
    type: ErrorType = ErrorType.UNKNOWN,
    title: String? = null,
    message: String? = null,
    isRetrying: Boolean = false,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val displayTitle = title ?: type.defaultTitle
    val displayMessage = message ?: type.defaultMessage
    val errorContentDescription = stringResource(R.string.a11y_error_format, displayTitle, displayMessage)
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Dimens.Space24)
            .semantics { this.contentDescription = errorContentDescription },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Error icon with warning color
        Icon(
            imageVector = type.icon,
            contentDescription = null,
            modifier = Modifier
                .size(Dimens.IconXLarge)
                .alpha(0.8f),
            tint = SemanticColors.WarningOrange
        )
        
        Spacer(modifier = Modifier.height(Dimens.Space24))
        
        // Title
        Text(
            text = displayTitle,
            style = MaterialTheme.typography.headlineSmall,
            color = SemanticColors.DangerRed,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(Dimens.Space12))
        
        // Message
        Text(
            text = displayMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = SemanticColors.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(0.8f)
        )
        
        Spacer(modifier = Modifier.height(Dimens.Space32))
        
        // Action buttons
        Column(
            verticalArrangement = Arrangement.spacedBy(Dimens.Space12),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Retry button (if retryable and not currently retrying)
            if (type.isRetryable && onRetry != null) {
                Button(
                    onClick = onRetry,
                    enabled = !isRetrying,
                    modifier = Modifier
                        .height(Dimens.ButtonHeightMedium)
                        .fillMaxWidth(0.6f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SemanticColors.PrimaryIndigo
                    )
                ) {
                    if (isRetrying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(Dimens.IconSmall),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(Dimens.IconSmall)
                        )
                    }
                    Text(
                        text = if (isRetrying) "Retrying..." else "Try Again",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = Dimens.Space8)
                    )
                }
            }
            
            // Dismiss button (if provided)
            onDismiss?.let {
                OutlinedButton(
                    onClick = it,
                    modifier = Modifier
                        .height(Dimens.ButtonHeightMedium)
                        .fillMaxWidth(0.6f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SemanticColors.TextSecondary
                    )
                ) {
                    Text(
                        text = "Dismiss",
                        style = MaterialTheme.typography.labelLarge,
                        color = SemanticColors.TextSecondary
                    )
                }
            }
        }
    }
}

/**
 * Inline error banner for use within content (not full screen).
 */
@Composable
fun InlineErrorBanner(
    message: String,
    onRetry: (() -> Unit)? = null,
    isRetrying: Boolean = false,
    modifier: Modifier = Modifier
) {
    androidx.compose.material3.Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.Space16, vertical = Dimens.Space8),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = SemanticColors.DangerRed.copy(alpha = 0.1f)
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = Dimens.BorderWidth,
            color = SemanticColors.DangerRed.copy(alpha = 0.3f)
        )
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .padding(Dimens.Space16)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = stringResource(R.string.label_error),
                tint = SemanticColors.DangerRed,
                modifier = Modifier.size(Dimens.IconMedium)
            )
            
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = SemanticColors.DangerRed,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Dimens.Space12)
            )
            
            onRetry?.let {
                if (isRetrying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(Dimens.IconMedium),
                        color = SemanticColors.DangerRed,
                        strokeWidth = 2.dp
                    )
                } else {
                    Button(
                        onClick = it,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SemanticColors.DangerRed
                        ),
                        modifier = Modifier.height(Dimens.ButtonHeightSmall)
                    ) {
                        Text(stringResource(R.string.action_retry), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/**
 * Animated version of ErrorState that fades in.
 */
@Composable
fun AnimatedErrorState(
    visible: Boolean,
    type: ErrorType = ErrorType.UNKNOWN,
    title: String? = null,
    message: String? = null,
    isRetrying: Boolean = false,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        ErrorState(
            type = type,
            title = title,
            message = message,
            isRetrying = isRetrying,
            onRetry = onRetry,
            onDismiss = onDismiss
        )
    }
}

// Previews
@Preview(showBackground = true)
@Composable
private fun ErrorStateNetworkPreview() {
    ExpenseTrackerTheme {
        ErrorState(
            type = ErrorType.NETWORK,
            onRetry = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ErrorStateRetryingPreview() {
    ExpenseTrackerTheme {
        ErrorState(
            type = ErrorType.SERVER,
            isRetrying = true,
            onRetry = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ErrorStateCustomPreview() {
    ExpenseTrackerTheme {
        ErrorState(
            type = ErrorType.UNKNOWN,
            title = "AI Processing Failed",
            message = "Gemini API returned an error. Please check your API key settings.",
            onRetry = {},
            onDismiss = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun InlineErrorBannerPreview() {
    ExpenseTrackerTheme {
        InlineErrorBanner(
            message = "Failed to load transactions",
            onRetry = {}
        )
    }
}
