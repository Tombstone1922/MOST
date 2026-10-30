package com.v2ray.ang.ui.compose

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.v2ray.ang.AppConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ThemeTest {
    @Test fun blackDefaultPreservesPreviouslySavedThemeModes() {
        assertEquals("3", AppConfig.DEFAULT_UI_MODE_NIGHT)
        for (systemDark in listOf(false, true)) {
            assertEquals(systemDark, themeUsesDarkColors("0", systemDark))
            assertFalse(themeUsesDarkColors("1", systemDark))
            assertTrue(themeUsesDarkColors("2", systemDark))
            assertTrue(themeUsesDarkColors("3", systemDark))
        }
    }

    @Test fun blackCanvasRemainsNeutralWithWallpaperColors() {
        val wallpaper = darkColorScheme(
            primary = Color(0xFFFFB2FA), background = Color(0xFF231126),
            surface = Color(0xFF231126), surfaceTint = Color(0xFFFFB2FA),
            onSurface = Color(0xFFF9DDF9),
        )
        val black = blackColorScheme(wallpaper)
        assertEquals(Color.Black, black.background)
        assertEquals(Color.Black, black.surface)
        assertEquals(Color.Black, black.surfaceDim)
        assertEquals(Color.Black, black.surfaceContainerLowest)
        assertEquals(Color.Transparent, black.surfaceTint)
        assertEquals(wallpaper.primary, black.primary)
        assertEquals(wallpaper.onSurface, black.onSurface)
        assertEquals(black.surfaceContainerLow.red, black.surfaceContainerLow.green)
        assertEquals(black.surfaceContainerLow.green, black.surfaceContainerLow.blue)
    }
}
