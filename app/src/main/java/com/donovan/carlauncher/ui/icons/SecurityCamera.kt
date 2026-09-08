package com.donovan.carlauncher.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * A wall-mounted bullet CCTV camera, for the CCTV tab.
 *
 * Material has no security-camera glyph, and Videocam is already the Dashcam tab -
 * two tabs sharing a silhouette is worse than no icon at all. Drawn on the usual
 * 24x24 grid so it sits correctly next to the Material icons on the rail.
 */
val SecurityCamera: ImageVector by lazy {
    ImageVector.Builder(
        name = "SecurityCamera",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // Body, tilted nose-down, with the lens punched straight out of it.
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(3.52f, 8.54f)
            lineTo(15.88f, 4.52f)
            lineTo(17.48f, 9.46f)
            lineTo(5.12f, 13.48f)
            close()
            moveTo(5.15f, 10.35f)
            arcToRelative(1.75f, 1.75f, 0f, isMoreThanHalf = true, isPositiveArc = true, 3.5f, 0f)
            arcToRelative(1.75f, 1.75f, 0f, isMoreThanHalf = true, isPositiveArc = true, -3.5f, 0f)
            close()
        }
        // Sunshade riding over the top - the most camera-ish part of the silhouette.
        path(fill = SolidColor(Color.Black)) {
            moveTo(2.1f, 7.2f)
            lineTo(16.4f, 2.55f)
            lineTo(17.0f, 4.4f)
            lineTo(2.7f, 9.05f)
            close()
        }
        // Arm dropping to the wall plate.
        path(fill = SolidColor(Color.Black)) {
            moveTo(13.9f, 11.55f)
            lineTo(16.0f, 10.85f)
            lineTo(17.35f, 15.0f)
            lineTo(19.9f, 15.0f)
            lineTo(19.9f, 17.1f)
            lineTo(15.6f, 17.1f)
            close()
        }
    }.build()
}
