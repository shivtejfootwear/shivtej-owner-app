package com.shivtej.owner.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Item History icon: a shoe with a magnifying glass (search for an article).
 * Drawn in one colour, so the Quick Action tint colours it. Same size as the other Quick Action icons.
 */
val ItemHistoryIcon: ImageVector by lazy {
    ImageVector.Builder(name = "ItemHistory", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = addPathNodes("M2 12V4.4c0-.9.7-1.5 1.6-1.4l3.6.5c.6.1 1 .5 1.2 1l.9 2.2 9 .8c1.3.1 2.1 1 2.1 2.3V12z M2 12h20.1v2.4H2z"),
            fill = SolidColor(Color.Black)
        )
        .addPath(
            pathData = addPathNodes("M20.2 17.5a3.2 3.2 0 1 0-6.4 0a3.2 3.2 0 1 0 6.4 0z M18.9 17.5a1.9 1.9 0 1 0-3.8 0a1.9 1.9 0 1 0 3.8 0z M19.83 19.27L22.53 21.97L21.47 23.03L18.77 20.33Z"),
            fill = SolidColor(Color.Black),
            pathFillType = PathFillType.EvenOdd
        )
        .build()
}
