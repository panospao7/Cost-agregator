package com.yourname.expensetracker.ui.screens.addexpense

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.ui.components.asString
import com.yourname.expensetracker.ui.theme.SemanticColors

const val QUICK_ADD_LINE_TAG = "quick_add_line"
const val QUICK_ADD_FILL_TAG = "quick_add_fill"
const val QUICK_ADD_SUMMARY_TAG = "quick_add_summary"

/**
 * C4 quick-add region at the top of the add-expense sheet (create mode only; the sheet has no edit mode).
 *
 * INV-12: the typed line lives only in this composable's plain `remember` state (never a saveable holder,
 * never the ViewModel or a saved-state handle). It leaves this function in exactly one place: the argument of
 * [onFill]. Rotation and process death lose it by design (Fill returns to disabled).
 *
 * @param summary lines for the last Fill (summary, flags, line hints); resolved by [quickAddText]
 * @param homeCurrencyLoaded false while the home currency is still loading (INV-17)
 * @param isSaving true while a save is in flight (INV-17)
 */
@Composable
fun QuickAddSection(
    summary: List<QuickAddLine>,
    homeCurrencyLoaded: Boolean,
    isSaving: Boolean,
    onFill: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var line by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val block = QuickAddUiMapper.fillBlock(
        lineIsBlank = line.isBlank(),
        homeCurrencyLoaded = homeCurrencyLoaded,
        isSaving = isSaving
    )
    val fillEnabled = block == null
    val blockDescription = QuickAddUiMapper.fillBlockDescription(block)?.asString()
    val counter = QuickAddUiMapper.counter(line.length)?.asString()

    // IME Done and the Fill button share one path: Fill when allowed, then always hide the keyboard and
    // clear focus so the filled form is visible. A blank line plus Done only hides the keyboard.
    val submit: () -> Unit = {
        if (fillEnabled) onFill(line)
        keyboardController?.hide()
        // force = true: the non-forced default may leave the text field focused (IME Done must drop focus).
        focusManager.clearFocus(force = true)
    }

    val stackButton = LocalConfiguration.current.screenWidthDp < 360 || LocalDensity.current.fontScale >= 1.3f

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (stackButton) {
            QuickAddLineField(
                line = line,
                counter = counter,
                onLineChange = { if (it.length <= QuickAddUiMapper.MAX_LINE_LENGTH) line = it },
                onDone = submit,
                modifier = Modifier.fillMaxWidth()
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                QuickAddFillButton(
                    enabled = fillEnabled,
                    stateDescriptionText = blockDescription,
                    onClick = submit,
                    modifier = Modifier
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                QuickAddLineField(
                    line = line,
                    counter = counter,
                    onLineChange = { if (it.length <= QuickAddUiMapper.MAX_LINE_LENGTH) line = it },
                    onDone = submit,
                    modifier = Modifier.weight(1f)
                )
                QuickAddFillButton(
                    enabled = fillEnabled,
                    stateDescriptionText = blockDescription,
                    onClick = submit,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        // The polite live region is always composed, so a screen reader registers it before the first
        // summary appears and announces the rows when they are inserted (ux 10).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(QUICK_ADD_SUMMARY_TAG)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (summary.isNotEmpty()) {
                summary.forEach { summaryLine ->
                    val warning = summaryLine.tone == QuickAddTone.WARNING
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            imageVector = if (warning) Icons.Default.Warning else Icons.Default.Check,
                            contentDescription = null,
                            tint = if (warning) SemanticColors.Warning else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp).padding(top = 2.dp)
                        )
                        Text(
                            text = quickAddText(summaryLine.text),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (warning) SemanticColors.Warning else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * The line field. Text keyboard, no capitalisation, autocorrect off (G2-15; Compose cannot disable IME
 * personalised learning, so privacy relies on INV-12/13, not the IME). Not a password field. IME Done
 * runs [onDone] (= Fill). Overlong edits are rejected by the caller (max 200).
 */
@Composable
private fun QuickAddLineField(
    line: String,
    counter: String?,
    onLineChange: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier
) {
    OutlinedTextField(
        value = line,
        onValueChange = onLineChange,
        label = { Text(stringResource(R.string.quick_add_label)) },
        placeholder = { Text(stringResource(R.string.quick_add_placeholder)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        supportingText = {
            Column {
                Text(stringResource(R.string.quick_add_helper))
                if (counter != null) Text(counter)
            }
        },
        modifier = modifier.testTag(QUICK_ADD_LINE_TAG)
    )
}

/** Fill button: 48dp minimum, content description, and a state description explaining why it is disabled. */
@Composable
private fun QuickAddFillButton(
    enabled: Boolean,
    stateDescriptionText: String?,
    onClick: () -> Unit,
    modifier: Modifier
) {
    val fillCd = stringResource(R.string.quick_add_fill_cd)
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = 48.dp)
            .testTag(QUICK_ADD_FILL_TAG)
            .semantics {
                contentDescription = fillCd
                if (stateDescriptionText != null) stateDescription = stateDescriptionText
            }
    ) {
        Text(stringResource(R.string.quick_add_fill_button))
    }
}

/** The "Filled" marker or flag for one field, for use in `supportingText` or as a caption. */
@Composable
fun QuickAddNoteText(note: QuickAddFieldNote, modifier: Modifier = Modifier) {
    val flag = note.kind == QuickAddFieldNoteKind.FLAG
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = if (flag) Icons.Default.Warning else Icons.Default.Check,
            contentDescription = null,
            tint = if (flag) SemanticColors.Warning else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp).padding(top = 2.dp)
        )
        Text(
            text = quickAddText(note.text),
            style = MaterialTheme.typography.bodySmall,
            color = if (flag) SemanticColors.Warning else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Chip row above the category grid (G2-11): why the category is set plus a 48dp Remove that routes to
 * the ViewModel's `clearCategory()` (the caller wires [onRemove]).
 */
@Composable
fun QuickAddCategoryChipRow(
    chip: QuickAddCategoryChip,
    category: Category,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val catColor = remember(category.color) {
        try {
            Color(android.graphics.Color.parseColor(category.color))
        } catch (e: IllegalArgumentException) {
            Color.Gray
        }
    }
    val label = chip.label.asString()
    val removeCd = chip.removeContentDescription.asString()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = catColor.copy(alpha = 0.2f),
        border = BorderStroke(1.dp, catColor)
    ) {
        Row(
            modifier = Modifier.heightIn(min = 48.dp).padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = category.icon,
                // The emoji is decoration: the label already names the category (ux 10).
                modifier = Modifier.padding(end = 8.dp).clearAndSetSemantics { }
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onRemove,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = removeCd }
            ) {
                Text(stringResource(R.string.quick_add_category_remove))
            }
        }
    }
}

/**
 * Resolves a mapper [UiText] to display text. Field-name lists ([QuickAddFieldNames]) are joined here
 * because [UiText] arguments are not resolved recursively. Resources only: no typed line is involved.
 */
@Composable
internal fun quickAddText(text: UiText): String {
    if (text !is UiText.StringResource) return text.asString()
    val args = text.args.map { arg ->
        if (arg is QuickAddFieldNames) {
            arg.fieldNameResIds.map { stringResource(it) }.joinToString(", ")
        } else {
            arg
        }
    }
    return if (args.isEmpty()) stringResource(text.resId) else stringResource(text.resId, *args.toTypedArray())
}
