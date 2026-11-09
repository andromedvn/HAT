package andromedvn.heuristic.activity.tracker.ui.theme

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.graphics.ColorUtils as AndroidColorUtils
import androidx.core.view.WindowCompat
import andromedvn.heuristic.activity.tracker.data.ThemeType
import andromedvn.heuristic.activity.tracker.data.UserSettings

object ColorUtils {
    fun colorToHSL(color: Color): FloatArray {
        val hsl = FloatArray(3)
        AndroidColorUtils.colorToHSL(color.toArgb(), hsl)
        return hsl
    }

    fun tone(hsl: FloatArray, tone: Int): Color {
        val newHsl = hsl.clone()
        newHsl[2] = (tone / 100f).coerceIn(0f, 1f)
        return Color(AndroidColorUtils.HSLToColor(newHsl))
    }
}

fun generateMonochromaticDark(seed: Color): ColorScheme {
    val hsl = ColorUtils.colorToHSL(seed)
    return darkColorScheme(
        primary = ColorUtils.tone(hsl, 80), onPrimary = ColorUtils.tone(hsl, 20),
        primaryContainer = ColorUtils.tone(hsl, 30), onPrimaryContainer = ColorUtils.tone(hsl, 90),
        secondary = ColorUtils.tone(hsl, 80), onSecondary = ColorUtils.tone(hsl, 20),
        secondaryContainer = ColorUtils.tone(hsl, 30), onSecondaryContainer = ColorUtils.tone(hsl, 90),
        tertiary = ColorUtils.tone(hsl, 80), onTertiary = ColorUtils.tone(hsl, 20),
        tertiaryContainer = ColorUtils.tone(hsl, 30), onTertiaryContainer = ColorUtils.tone(hsl, 90),
        background = ColorUtils.tone(hsl, 6), onBackground = ColorUtils.tone(hsl, 90),
        surface = ColorUtils.tone(hsl, 6), onSurface = ColorUtils.tone(hsl, 90),
        surfaceVariant = ColorUtils.tone(hsl, 30), onSurfaceVariant = ColorUtils.tone(hsl, 80),
        outline = ColorUtils.tone(hsl, 60)
    )
}

@Composable
fun ComposeEmptyActivityTheme(settings: UserSettings, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    // FALLBACK: derives from the stored accent so a custom colour survives on
    // devices without dynamic colour. Pre-fix this discarded settings.staticColor
    // and settings.extractedColor and hardcoded orange, which is also why the
    // crash-recovery screen (which asks for red) rendered orange.
    val scheme = when (settings.themeType) {
        ThemeType.DYNAMIC -> if (dynamicAvailable) dynamicDarkColorScheme(context) else generateMonochromaticDark(Color(settings.extractedColor))
        ThemeType.STATIC -> generateMonochromaticDark(Color(settings.staticColor))
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            // Deprecated in API 35 in favour of edge-to-edge + enableEdgeToEdge(),
            // but they still work and the modern replacement is a visual change
            // that needs device verification. Suppressed deliberately, not overlooked.
            @Suppress("DEPRECATION")
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val attributes = window.attributes
                attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                window.attributes = attributes
            }
            
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = false
            insetsController.isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(colorScheme = scheme, typography = Typography, content = content)
}
