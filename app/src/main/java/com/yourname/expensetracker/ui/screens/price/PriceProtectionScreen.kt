package com.yourname.expensetracker.ui.screens.price

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.domain.price.PriceProtectionTracker
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PriceProtectionScreen(
    onNavigateBack: () -> Unit,
    viewModel: PriceProtectionViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val priceDrops by viewModel.priceDrops.collectAsState()
    val protectedItems by viewModel.protectedItems.collectAsState()
    val excludedTrackingKeys by viewModel.excludedTrackingKeys.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val homeCurrency by viewModel.homeCurrency.collectAsState(initial = "")
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }
    val unableToOpenClaimMessage = stringResource(R.string.price_snackbar_unable_open_claim_link)
    val itemAddedBackMessage = stringResource(R.string.price_snackbar_item_added_back_tracking)
    val itemRemovedMessage = stringResource(R.string.price_snackbar_item_removed_tracking)
    val undoLabel = stringResource(R.string.action_undo)

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_price_protection)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Tabs
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.price_tab_drops)) },
                    icon = { 
                        BadgedBox(
                            badge = {
                                if (priceDrops.isNotEmpty()) {
                                    Badge { Text("${priceDrops.size}") }
                                }
                            }
                        ) {
                            Icon(Icons.Rounded.TrendingDown, null)
                        }
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.price_tab_protected)) },
                    icon = { Icon(Icons.Rounded.Shield, null) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text(stringResource(R.string.price_tab_deals)) },
                    icon = { Icon(Icons.Rounded.LocalOffer, null) }
                )
            }
            
            when (selectedTab) {
                0 ->                 PriceDropsTab(
                    priceDrops = priceDrops,
                    isLoading = isLoading,
                    homeCurrency = homeCurrency,
                    onRefresh = { viewModel.refreshPriceDrops() },
                    onFileClaim = { url ->
                        val launched = openExternalUrl(context, url)
                        if (!launched) {
                            scope.launch {
                                snackbarHostState.showSnackbar(unableToOpenClaimMessage)
                            }
                        }
                    }
                )
                1 -> ProtectedItemsTab(
                    items = protectedItems,
                    isLoading = isLoading,
                    homeCurrency = homeCurrency,
                    isTracked = { item ->
                        val key = "${item.receiptId}:${item.itemName.lowercase()}:${item.purchaseDate}"
                        key !in excludedTrackingKeys
                    },
                    onTrackItem = { item ->
                        viewModel.trackItem(item)
                        scope.launch {
                            snackbarHostState.showSnackbar(itemAddedBackMessage)
                        }
                    },
                    onRemoveFromTracking = { item ->
                        viewModel.removeFromTracking(item)
                        scope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = itemRemovedMessage,
                                actionLabel = undoLabel
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                viewModel.trackItem(item)
                            }
                        }
                    }
                )
                2 -> DealsTab(
                    viewModel = viewModel,
                    isLoading = isLoading,
                    homeCurrency = homeCurrency
                )
            }
        }
    }
}

@Composable
fun PriceDropsTab(
    priceDrops: List<PriceProtectionTracker.PriceDropAlert>,
    isLoading: Boolean,
    homeCurrency: String,
    onRefresh: () -> Unit,
    onFileClaim: (String) -> Unit
) {
    
    if (isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    } else if (priceDrops.isEmpty()) {
        EmptyPriceDropsState(onRefresh = onRefresh)
    } else {
        val totalSavings = priceDrops.sumOf { it.priceDrop }
        
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.price_available_refunds),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                        
                        Text(
                            text = CurrencyFormatter.format(totalSavings, homeCurrency),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        
                        Text(
                            text = stringResource(R.string.price_items_with_drops, priceDrops.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            
            items(priceDrops) { alert ->
                PriceDropCard(
                    alert = alert,
                    onFileClaim = onFileClaim,
                    homeCurrency = homeCurrency
                )
            }
        }
    }
}

@Composable
fun PriceDropCard(
    alert: PriceProtectionTracker.PriceDropAlert,
    onFileClaim: (String) -> Unit,
    homeCurrency: String
) {
    val dateFormatter = DateTimeFormatter.ofPattern("MMM dd", Locale.getDefault())
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.TrendingDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(24.dp)
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Text(
                    text = alert.item.itemName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                if (alert.isSimulated) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "SIMULATED",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Price comparison
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.price_label_you_paid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = CurrencyFormatter.format(alert.item.purchasePrice, homeCurrency),
                        style = MaterialTheme.typography.titleMedium,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
                    )
                }
                
                Icon(
                    imageVector = Icons.Default.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.price_label_current_price),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = CurrencyFormatter.format(alert.currentPrice, homeCurrency),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.price_label_refund),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = CurrencyFormatter.format(alert.priceDrop, homeCurrency),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Store
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Store,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = alert.item.merchant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                
                // Days remaining
                if (alert.daysRemaining > 0) {
                    Surface(
                        color = if (alert.daysRemaining < 7) 
                            MaterialTheme.colorScheme.errorContainer 
                        else 
                            MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.price_days_left, alert.daysRemaining),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (alert.daysRemaining < 7) 
                                MaterialTheme.colorScheme.onErrorContainer 
                            else 
                                MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Claim button
            if (alert.claimUrl != null) {
                Button(
                    onClick = { onFileClaim(alert.claimUrl) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.OpenInNew, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.price_action_file_claim))
                }
            }
        }
    }
}

@Composable
fun ProtectedItemsTab(
    items: List<PriceProtectionTracker.PriceProtectedItem>,
    isLoading: Boolean,
    homeCurrency: String,
    isTracked: (PriceProtectionTracker.PriceProtectedItem) -> Boolean,
    onTrackItem: (PriceProtectionTracker.PriceProtectedItem) -> Unit,
    onRemoveFromTracking: (PriceProtectionTracker.PriceProtectedItem) -> Unit
) {
    var pendingRemoval by remember { mutableStateOf<PriceProtectionTracker.PriceProtectedItem?>(null) }

    if (isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    } else if (items.isEmpty()) {
        EmptyProtectedItemsState()
    } else {
        val protectedCount = items.count { it.priceProtectionEligible }
        
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.price_eligible_count, protectedCount, items.size),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                }
            }
            
            items(items) { item ->
                ProtectedItemCard(
                    item = item,
                    homeCurrency = homeCurrency,
                    isTracked = isTracked(item),
                    onTrackItem = { onTrackItem(item) },
                    onRemoveFromTracking = { pendingRemoval = item }
                )
            }
        }
    }

    pendingRemoval?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(stringResource(R.string.price_remove_tracking_confirm_title)) },
            text = { Text(stringResource(R.string.price_remove_tracking_confirm_message, item.itemName)) },
            confirmButton = {
                Button(
                    onClick = {
                        onRemoveFromTracking(item)
                        pendingRemoval = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.label_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
fun ProtectedItemCard(
    item: PriceProtectionTracker.PriceProtectedItem,
    homeCurrency: String,
    isTracked: Boolean,
    onTrackItem: () -> Unit,
    onRemoveFromTracking: () -> Unit
) {
    val dateFormatter = DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.getDefault())
    val purchaseDate = Instant.ofEpochMilli(item.purchaseDate)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Inventory2,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.itemName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.price_purchased_label, dateFormatter.format(purchaseDate)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                if (item.priceProtectionEligible) {
                    Icon(
                        imageVector = Icons.Rounded.Verified,
                        contentDescription = stringResource(R.string.price_eligible),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.price_price_label, CurrencyFormatter.format(item.purchasePrice, homeCurrency)),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.price_return_label, item.returnWindowDays),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            
            if (item.priceProtectionEligible) {
                Spacer(modifier = Modifier.height(8.dp))
                
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.price_protection_active),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (isTracked) {
                OutlinedButton(
                    onClick = onRemoveFromTracking,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Rounded.NotificationsOff, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.price_remove_from_tracking_action))
                }
            } else {
                Button(
                    onClick = onTrackItem,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.NotificationsActive, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.price_track_item_action))
                }
            }
        }
    }
}

@Composable
fun DealsTab(
    viewModel: PriceProtectionViewModel,
    isLoading: Boolean,
    homeCurrency: String
) {
    val deals by viewModel.deals.collectAsState()
    val coupons by viewModel.coupons.collectAsState()
    val benefits by viewModel.creditCardBenefits.collectAsState()
    
    if (isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Better Deals
            if (deals.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.price_better_deals_found),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                items(deals) { deal ->
                    DealCard(deal = deal, homeCurrency = homeCurrency)
                }
            }
            
            // Coupons
            if (coupons.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.price_available_coupons),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                items(coupons) { coupon ->
                    CouponCard(coupon = coupon)
                }
            }
            
            // Credit Card Benefits
            if (benefits.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.price_credit_card_benefits),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                items(benefits) { benefit ->
                    CreditCardBenefitCard(benefit = benefit, homeCurrency = homeCurrency)
                }
            }
            
            if (deals.isEmpty() && coupons.isEmpty() && benefits.isEmpty()) {
                item {
                    EmptyDealsState()
                }
            }
        }
    }
}

@Composable
fun DealCard(deal: PriceProtectionTracker.DealAlternative, homeCurrency: String) {
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = deal.originalItem.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                if (deal.isSimulated) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "SIMULATED",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.price_current_label, CurrencyFormatter.format(deal.originalPrice, homeCurrency)),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.price_merchant_at_label, deal.betterMerchant),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.price_label_save_percent, deal.savingsPercent.toInt()),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = stringResource(R.string.price_save_at_merchant, CurrencyFormatter.format(deal.savings, homeCurrency), deal.betterMerchant),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun CouponCard(coupon: PriceProtectionTracker.CouponMatch) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = coupon.code,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }

            if (coupon.isSimulated) {
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "SIMULATED",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = coupon.description,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.price_at_merchant, coupon.merchant),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
        }
    }
}

@Composable
fun CreditCardBenefitCard(benefit: PriceProtectionTracker.CreditCardBenefit, homeCurrency: String) {
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (benefit.benefitType) {
                    PriceProtectionTracker.BenefitType.CASHBACK -> Icons.Rounded.Money
                    PriceProtectionTracker.BenefitType.POINTS -> Icons.Rounded.Star
                    PriceProtectionTracker.BenefitType.PROTECTION -> Icons.Rounded.Shield
                    PriceProtectionTracker.BenefitType.WARRANTY -> Icons.Rounded.Build
                    PriceProtectionTracker.BenefitType.EXTENDED_RETURN -> Icons.Rounded.Schedule
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = benefit.benefitDescription,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.price_via_card, benefit.cardName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                
                if (benefit.requiresAction && benefit.actionDescription != null) {
                    Text(
                        text = stringResource(R.string.price_action_required, benefit.actionDescription),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            
            Text(
                text = stringResource(R.string.price_plus_value_format, CurrencyFormatter.format(benefit.estimatedValue, homeCurrency)),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun EmptyPriceDropsState(onRefresh: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.TrendingFlat,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
            Text(
                text = stringResource(R.string.price_empty_no_drops),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = stringResource(R.string.price_empty_no_drops_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(onClick = onRefresh) {
                Icon(Icons.Rounded.Refresh, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.price_action_check_now))
            }
    }
}

@Composable
fun EmptyProtectedItemsState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.ShoppingBag,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
            Text(
                text = stringResource(R.string.price_empty_no_protected),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = stringResource(R.string.price_empty_no_protected_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
    }
}

@Composable
fun EmptyDealsState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
            Text(
                text = stringResource(R.string.price_empty_no_deals),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = stringResource(R.string.price_empty_no_deals_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
    }
}

private fun openExternalUrl(context: android.content.Context, url: String): Boolean {
    return runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.isSuccess
}
