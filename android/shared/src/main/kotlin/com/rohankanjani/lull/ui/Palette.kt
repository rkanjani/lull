package com.rohankanjani.lull.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import com.rohankanjani.lull.bedjetkit.Mode

object Theme {
    val background = Color.Black
    val surface = Color(0.055f, 0.059f, 0.071f)
    val raised = Color(0.098f, 0.102f, 0.118f)
    val hairline = Color.White.copy(alpha = 0.08f)
    val secondary = Color.White.copy(alpha = 0.55f)
    /** The faintest text that still reads at 4.5:1 against the background and surfaces. */
    val tertiary = Color.White.copy(alpha = 0.48f)

    val cool = Color(0.30f, 0.64f, 1.0f)
    val deepCool = Color(0.17f, 0.36f, 1.0f)
    val warm = Color(1.0f, 0.54f, 0.24f)
    val hot = Color(1.0f, 0.30f, 0.18f)
    val neutral = Color(0.56f, 0.56f, 0.60f)
    /** iOS system green, used for connection and switches. */
    val green = Color(0.20f, 0.78f, 0.35f)
    /** iOS system red, used for destructive actions. */
    val red = Color(1.0f, 0.27f, 0.23f)

    /** Numbers in the UI keep their width as they change. */
    val tabular = TextStyle(fontFeatureSettings = "tnum")
}

val Mode.tint: Color
    get() = when (this) {
        Mode.COOL -> Theme.cool
        Mode.HEAT -> Theme.warm
        Mode.TURBO -> Theme.hot
        Mode.EXTENDED_HEAT -> Color(1.0f, 0.70f, 0.25f)
        Mode.DRY -> Color(0.35f, 0.84f, 0.77f)
        Mode.STANDBY, Mode.WAIT -> Theme.neutral
    }

val Mode.icon: ImageVector
    get() = when (this) {
        Mode.STANDBY -> Icons.Filled.PowerSettingsNew
        Mode.HEAT -> Icons.Filled.LocalFireDepartment
        Mode.TURBO -> Icons.Filled.Bolt
        Mode.EXTENDED_HEAT -> Icons.Filled.WbSunny
        Mode.COOL -> Icons.Filled.AcUnit
        Mode.DRY -> Icons.Filled.Air
        Mode.WAIT -> Icons.Filled.Pause
    }

/**
 * Whether the person has picked one of the largest text sizes, where layouts stack instead of squeezing.
 * Like iOS's accessibility text sizes, which begin around one and a half times the default.
 */
@Composable
fun isLargeText(): Boolean = LocalDensity.current.fontScale >= 1.5f
