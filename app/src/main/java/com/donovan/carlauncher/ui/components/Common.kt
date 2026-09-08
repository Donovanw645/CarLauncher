package com.donovan.carlauncher.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Rounded panel used for every block of content in the launcher. */
@Composable
fun CarCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = 16.dp,
    color: Color = MaterialTheme.colorScheme.surface,
    border: BorderStroke? = null,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    Surface(
        modifier = if (onClick != null) modifier.clickable { onClick() } else modifier,
        shape = RoundedCornerShape(22.dp),
        color = color,
        border = border,
    ) {
        Box(Modifier.padding(contentPadding), content = content)
    }
}

/**
 * Round transport / map control. Sized generously - this gets pressed by someone
 * looking at the road, not at the screen.
 */
@Composable
fun RoundControl(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 62.dp,
    enabled: Boolean = true,
    filled: Boolean = false,
    tint: Color? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (filled) scheme.primary else scheme.surfaceVariant
    val fg = tint ?: if (filled) scheme.onPrimary else scheme.onSurface
    Box(
        modifier = modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.35f)
            .background(bg, CircleShape)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = fg,
            modifier = Modifier.size(size * 0.46f),
        )
    }
}

/** Square shortcut tile: icon over a short label. */
@Composable
fun TileButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    subtitle: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        color = if (accent) scheme.primaryContainer else scheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (accent) scheme.primary else scheme.onSurface,
                modifier = Modifier.size(28.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** Small status pill used along the top strip. */
@Composable
fun StatusChip(
    icon: ImageVector,
    text: String?,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val color = tint ?: scheme.onSurfaceVariant
    Row(
        modifier = (if (onClick != null) modifier.clickable { onClick() } else modifier)
            .background(scheme.surface, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        if (!text.isNullOrBlank()) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Wall-clock time that recomposes once a second. */
@Composable
fun rememberTicker(periodMs: Long = 1000L): State<Long> {
    val state = remember { mutableLongStateOf(System.currentTimeMillis()) }
    var value by state
    LaunchedEffect(periodMs) {
        while (true) {
            value = System.currentTimeMillis()
            delay(periodMs)
        }
    }
    return state
}
