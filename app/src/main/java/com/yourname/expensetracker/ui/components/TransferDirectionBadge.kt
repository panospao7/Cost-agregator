package com.yourname.expensetracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.TransferDirection

/**
 * A badge component that displays transfer direction (INCOMING/OUTGOING)
 * with visual indicators and account information.
 */
@Composable
fun TransferDirectionBadge(
    direction: TransferDirection?,
    accountName: String?,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    compact: Boolean = false
) {
    when (direction) {
        TransferDirection.INCOMING -> IncomingBadge(
            accountName = accountName,
            modifier = modifier,
            showLabel = showLabel,
            compact = compact
        )
        TransferDirection.OUTGOING -> OutgoingBadge(
            accountName = accountName,
            modifier = modifier,
            showLabel = showLabel,
            compact = compact
        )
        null -> UnknownBadge(
            modifier = modifier,
            showLabel = showLabel,
            compact = compact
        )
    }
}

@Composable
private fun IncomingBadge(
    accountName: String?,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    compact: Boolean = false
) {
    val backgroundColor = MaterialTheme.colorScheme.secondaryContainer
    val contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    val compactContentDescription = buildString {
        append(stringResource(R.string.a11y_transfer_incoming))
        accountName?.takeIf { it.isNotBlank() }?.let {
            append(" ")
            append(it)
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .then(
                if (showLabel) Modifier else Modifier.semantics {
                    contentDescription = compactContentDescription
                }
            )
            .padding(horizontal = if (compact) 6.dp else 10.dp, vertical = if (compact) 4.dp else 6.dp)
    ) {
        Icon(
            imageVector = Icons.Default.ArrowDownward,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(if (compact) 14.dp else 18.dp)
        )
        
        if (showLabel) {
            Spacer(modifier = Modifier.width(6.dp))
            
            Text(
                text = buildString {
                    append(stringResource(R.string.transfer_incoming))
                    accountName?.let { 
                        append(" ")
                        append(it)
                    }
                },
                color = contentColor,
                style = if (compact) {
                    MaterialTheme.typography.labelSmall
                } else {
                    MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun OutgoingBadge(
    accountName: String?,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    compact: Boolean = false
) {
    val backgroundColor = MaterialTheme.colorScheme.primaryContainer
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    val compactContentDescription = buildString {
        append(stringResource(R.string.a11y_transfer_outgoing))
        accountName?.takeIf { it.isNotBlank() }?.let {
            append(" ")
            append(it)
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .then(
                if (showLabel) Modifier else Modifier.semantics {
                    contentDescription = compactContentDescription
                }
            )
            .padding(horizontal = if (compact) 6.dp else 10.dp, vertical = if (compact) 4.dp else 6.dp)
    ) {
        Icon(
            imageVector = Icons.Default.ArrowUpward,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(if (compact) 14.dp else 18.dp)
        )
        
        if (showLabel) {
            Spacer(modifier = Modifier.width(6.dp))
            
            Text(
                text = buildString {
                    append(stringResource(R.string.transfer_outgoing))
                    accountName?.let { 
                        append(" ")
                        append(it)
                    }
                },
                color = contentColor,
                style = if (compact) {
                    MaterialTheme.typography.labelSmall
                } else {
                    MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun UnknownBadge(
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    compact: Boolean = false
) {
    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    val compactContentDescription = stringResource(R.string.a11y_transfer_unknown)
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .then(
                if (showLabel) Modifier else Modifier.semantics {
                    contentDescription = compactContentDescription
                }
            )
            .padding(horizontal = if (compact) 6.dp else 10.dp, vertical = if (compact) 4.dp else 6.dp)
    ) {
        Icon(
            imageVector = Icons.Default.HelpOutline,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(if (compact) 14.dp else 18.dp)
        )

        if (showLabel) {
            Spacer(modifier = Modifier.width(6.dp))

            Text(
                text = stringResource(R.string.transfer_set_direction),
                color = contentColor,
                style = if (compact) {
                    MaterialTheme.typography.labelSmall
                } else {
                    MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
                }
            )
        }
    }
}

/**
 * Compact icon-only version for use in lists where space is limited.
 */
@Composable
fun TransferDirectionIcon(
    direction: TransferDirection?,
    modifier: Modifier = Modifier,
    size: Int = 20
) {
    when (direction) {
        TransferDirection.INCOMING -> {
            Box(
                modifier = modifier
                    .size(size.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowDownward,
                    contentDescription = stringResource(R.string.a11y_transfer_incoming),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size((size * 0.6).dp)
                )
            }
        }
        TransferDirection.OUTGOING -> {
            Box(
                modifier = modifier
                    .size(size.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowUpward,
                    contentDescription = stringResource(R.string.a11y_transfer_outgoing),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size((size * 0.6).dp)
                )
            }
        }
        null -> {
            Box(
                modifier = modifier
                    .size(size.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.HelpOutline,
                    contentDescription = stringResource(R.string.a11y_transfer_unknown),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size((size * 0.6).dp)
                )
            }
        }
    }
}

// Previews
@Preview(showBackground = true)
@Composable
private fun IncomingBadgePreview() {
    MaterialTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            TransferDirectionBadge(
                direction = TransferDirection.INCOMING,
                accountName = "From: John Smith",
                showLabel = true,
                compact = false
            )
            Spacer(modifier = Modifier.height(8.dp))
            TransferDirectionBadge(
                direction = TransferDirection.INCOMING,
                accountName = "From: John",
                showLabel = true,
                compact = true
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OutgoingBadgePreview() {
    MaterialTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            TransferDirectionBadge(
                direction = TransferDirection.OUTGOING,
                accountName = "To: Savings Account",
                showLabel = true,
                compact = false
            )
            Spacer(modifier = Modifier.height(8.dp))
            TransferDirectionBadge(
                direction = TransferDirection.OUTGOING,
                accountName = "To: Mary",
                showLabel = true,
                compact = true
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun UnknownBadgePreview() {
    MaterialTheme {
        TransferDirectionBadge(
            direction = null,
            accountName = null,
            showLabel = true,
            compact = false
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun IconsPreview() {
    MaterialTheme {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TransferDirectionIcon(direction = TransferDirection.INCOMING)
            TransferDirectionIcon(direction = TransferDirection.OUTGOING)
            TransferDirectionIcon(direction = null)
        }
    }
}
