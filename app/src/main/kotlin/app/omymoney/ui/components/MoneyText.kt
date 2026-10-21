package app.omymoney.ui.components

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

@Composable
fun MoneyText(value: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.titleMedium,
              textAlign: TextAlign = TextAlign.Start, color: Color = Color.Unspecified) {
    // Tabular figures keep columns of amounts aligned and stop digits jittering when values change.
    val tint = color.takeOrElse { LocalContentColor.current.takeOrElse { MaterialTheme.colorScheme.onSurface } }
    BasicText(value, modifier, style = style.copy(color = tint, textAlign = textAlign, fontFeatureSettings = "tnum"),
        maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = style.fontSize, stepSize = 1.sp))
}
