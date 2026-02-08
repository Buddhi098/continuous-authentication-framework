package com.ca.authframework.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
        darkColorScheme(
                primary = DeepBlue80,
                secondary = NavyBlue80,
                tertiary = SlateBlue80,
                error = ErrorDark,
                background = Color(0xFF121212),
                surface = Color(0xFF1E1E1E),
                onPrimary = Color.White,
                onSecondary = Color.White,
                onTertiary = Color.White,
                onBackground = Color(0xFFE0E0E0),
                onSurface = Color(0xFFE0E0E0),
                primaryContainer = Color(0xFF2C4663),
                secondaryContainer = Color(0xFF3A4F6B),
                tertiaryContainer = SuccessDark,
                errorContainer = Color(0xFF5F2120)
        )

private val LightColorScheme =
        lightColorScheme(
                primary = DeepBlue40,
                secondary = NavyBlue40,
                tertiary = SlateBlue40,
                error = ErrorLight,
                background = Color(0xFFFAFAFA),
                surface = Color.White,
                onPrimary = Color.White,
                onSecondary = Color.White,
                onTertiary = Color.White,
                onBackground = Color(0xFF1C1B1F),
                onSurface = Color(0xFF1C1B1F),
                primaryContainer = SurfaceBlue,
                secondaryContainer = ContainerBlue,
                tertiaryContainer = SuccessLight,
                errorContainer = Color(0xFFFFDAD6)
        )

@Composable
fun AuthframeworkTheme(
        darkTheme: Boolean = isSystemInDarkTheme(),
        // Dynamic color is available on Android 12+
        dynamicColor: Boolean = true,
        content: @Composable () -> Unit
) {
        val colorScheme =
                when {
                        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                                val context = LocalContext.current
                                if (darkTheme) dynamicDarkColorScheme(context)
                                else dynamicLightColorScheme(context)
                        }
                        darkTheme -> DarkColorScheme
                        else -> LightColorScheme
                }

        MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
