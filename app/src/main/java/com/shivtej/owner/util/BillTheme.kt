package com.shivtej.owner.util

/**
 * How a bill LOOKS. Changing the theme never changes any number, tax, stock or total: only colours, background
 * and which blocks (logo, QR, notes ...) are shown.
 */
data class BillTheme(
    val id: String = "shivtej_red",
    val accent: Int = 0xFFE30613.toInt(),     // header bars, grand total
    val accent2: Int = 0xFF111111.toInt(),    // dark text / second brand colour
    val tint: Int = 0xFFFDECEC.toInt(),       // soft box background
    val bgStyle: String = "plain",            // plain | tint | lines | dots | watermark | image
    val bgImage: String = "",                 // path of the uploaded background picture (bgStyle = image)
    val bgStrength: Int = 14,                 // 0..60 : how visible the background is (kept light so the bill stays readable)
    val showLogo: Boolean = true,
    val showTagline: Boolean = true,
    val showQr: Boolean = true,               // website QR (only when a website address is saved)
    val showInstagramQr: Boolean = true,      // Instagram QR (only when an Instagram address is saved)
    val centerHeader: Boolean = false,        // logo + shop name centred instead of left
    val showNotes: Boolean = true,
    val showThanks: Boolean = true,
    val showFooter: Boolean = true
) {
    companion object {
        const val MAX_STRENGTH = 60
    }
}

data class ThemePreset(val id: String, val name: String, val accent: Int, val accent2: Int, val tint: Int)

object BillThemes {
    val presets: List<ThemePreset> = listOf(
        ThemePreset("shivtej_red", "Shivtej Red", 0xFFE30613.toInt(), 0xFF111111.toInt(), 0xFFFDECEC.toInt()),
        ThemePreset("classic_black", "Classic Black", 0xFF222222.toInt(), 0xFF000000.toInt(), 0xFFF1F1F1.toInt()),
        ThemePreset("royal_blue", "Royal Blue", 0xFF1565C0.toInt(), 0xFF0D2B5C.toInt(), 0xFFE8F0FB.toInt()),
        ThemePreset("fresh_green", "Fresh Green", 0xFF2E7D32.toInt(), 0xFF1B3D1F.toInt(), 0xFFE9F5EA.toInt()),
        ThemePreset("sunset_orange", "Sunset Orange", 0xFFEF6C00.toInt(), 0xFF4E2A00.toInt(), 0xFFFFF1E3.toInt()),
        ThemePreset("plain_white", "Minimal White", 0xFF444444.toInt(), 0xFF000000.toInt(), 0xFFFAFAFA.toInt())
    )

    val backgrounds: List<Pair<String, String>> = listOf(
        "plain" to "Plain white",
        "tint" to "Soft colour",
        "lines" to "Diagonal lines",
        "dots" to "Dots",
        "watermark" to "Logo watermark",
        "image" to "My own picture"
    )

    fun apply(theme: BillTheme, presetId: String): BillTheme {
        val p = presets.firstOrNull { it.id == presetId } ?: return theme
        return theme.copy(id = p.id, accent = p.accent, accent2 = p.accent2, tint = p.tint)
    }

    fun strength(theme: BillTheme): Int = theme.bgStrength.coerceIn(0, BillTheme.MAX_STRENGTH)
}
