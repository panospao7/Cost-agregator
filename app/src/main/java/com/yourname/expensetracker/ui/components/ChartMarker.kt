package com.yourname.expensetracker.ui.components

import android.graphics.Typeface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.component.lineComponent
import com.patrykandpatrick.vico.compose.component.overlayingComponent
import com.patrykandpatrick.vico.compose.component.shapeComponent
import com.patrykandpatrick.vico.compose.component.textComponent
import com.patrykandpatrick.vico.compose.dimensions.dimensionsOf
import com.patrykandpatrick.vico.core.component.marker.MarkerComponent
import com.patrykandpatrick.vico.core.component.shape.DashedShape
import com.patrykandpatrick.vico.core.component.shape.LineComponent
import com.patrykandpatrick.vico.core.component.shape.ShapeComponent
import com.patrykandpatrick.vico.core.component.shape.Shapes
import com.patrykandpatrick.vico.core.marker.Marker
import com.yourname.expensetracker.ui.theme.SemanticColors

@Composable
fun rememberMarker(): Marker {
    val labelBackgroundColor = MaterialTheme.colorScheme.surface
    val labelBackground = remember(labelBackgroundColor) {
        ShapeComponent(shape = Shapes.pillShape, color = labelBackgroundColor.toArgb()).setShadow(
            radius = 4f,
            dy = 2f,
            applyElevationOverlay = true,
        )
    }
    
    val label = textComponent(
        color = MaterialTheme.colorScheme.onSurface,
        background = labelBackground,
        padding = dimensionsOf(8.dp, 4.dp),
        typeface = Typeface.MONOSPACE,
    )
    
    val indicator = shapeComponent(Shapes.pillShape, SemanticColors.PrimaryIndigo)
    
    val guidelineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
    val guideline = lineComponent(
        color = guidelineColor,
        thickness = 2.dp,
        shape = DashedShape(Shapes.pillShape, 2f, 4f),
    )
    
    return remember(label, indicator, guideline) {
        object : MarkerComponent(label, indicator, guideline) {
            init {
                indicatorSizeDp = 6f
                onApplyEntryColor = { entryColor ->
                    indicator.color = withAlpha(entryColor, 255)
                    labelBackground.color = withAlpha(entryColor, 34) // ~13% opacity
                }
            }
        }
    }
}

private fun withAlpha(color: Int, alpha: Int): Int {
    return (color and 0x00FFFFFF) or (alpha shl 24)
}
