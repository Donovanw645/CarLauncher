package com.donovan.carlauncher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.ForkLeft
import androidx.compose.material.icons.rounded.ForkRight
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material.icons.rounded.RampLeft
import androidx.compose.material.icons.rounded.RampRight
import androidx.compose.material.icons.rounded.RoundaboutLeft
import androidx.compose.material.icons.rounded.RoundaboutRight
import androidx.compose.material.icons.rounded.Straight
import androidx.compose.material.icons.rounded.TurnLeft
import androidx.compose.material.icons.rounded.TurnRight
import androidx.compose.material.icons.rounded.TurnSharpLeft
import androidx.compose.material.icons.rounded.TurnSharpRight
import androidx.compose.material.icons.rounded.TurnSlightLeft
import androidx.compose.material.icons.rounded.TurnSlightRight
import androidx.compose.material.icons.rounded.UTurnLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.donovan.carlauncher.data.Units
import com.donovan.carlauncher.nav.NavState
import com.donovan.carlauncher.nav.formatDistance

fun maneuverIcon(type: String, modifier: String): ImageVector {
    val left = modifier.contains("left")
    return when {
        type == "arrive" -> Icons.Rounded.Flag
        type.startsWith("roundabout") || type.startsWith("rotary") ->
            if (left) Icons.Rounded.RoundaboutLeft else Icons.Rounded.RoundaboutRight
        type == "merge" -> Icons.Rounded.Merge
        type == "fork" -> if (left) Icons.Rounded.ForkLeft else Icons.Rounded.ForkRight
        type == "on ramp" || type == "off ramp" ->
            if (left) Icons.Rounded.RampLeft else Icons.Rounded.RampRight
        modifier == "uturn" -> Icons.Rounded.UTurnLeft
        modifier == "sharp left" -> Icons.Rounded.TurnSharpLeft
        modifier == "sharp right" -> Icons.Rounded.TurnSharpRight
        modifier == "slight left" -> Icons.Rounded.TurnSlightLeft
        modifier == "slight right" -> Icons.Rounded.TurnSlightRight
        modifier == "left" -> Icons.Rounded.TurnLeft
        modifier == "right" -> Icons.Rounded.TurnRight
        else -> Icons.Rounded.Straight
    }
}

/**
 * The "in 400 ft, turn right onto Elm Street" block. Deliberately the loudest thing
 * on screen while a route is running.
 */
@Composable
fun NavBanner(
    nav: NavState,
    units: Units,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val step = nav.upcomingStep ?: return
    val scheme = MaterialTheme.colorScheme

    Box(
        modifier = modifier
            .background(scheme.surface.copy(alpha = 0.96f), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = if (compact) 13.dp else 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(if (compact) 58.dp else 64.dp)
                    .background(scheme.primaryContainer, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = maneuverIcon(step.maneuverType, step.modifier),
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(if (compact) 35.dp else 40.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = formatDistance(nav.distanceToManeuverM, units),
                    // The Home map is small, but the turn distance is the one thing you
                    // read at a glance while driving, so the compact banner is scaled up
                    // rather than left at the size the layout would otherwise suggest.
                    style = if (compact) MaterialTheme.typography.titleLarge.scaled()
                    else MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = scheme.primary,
                )
                Text(
                    text = step.instruction,
                    style = if (compact) MaterialTheme.typography.bodyMedium.scaled()
                    else MaterialTheme.typography.titleLarge,
                    color = scheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val then = nav.followingStep
                if (then != null && !compact) {
                    Spacer(Modifier.padding(top = 2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "then",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                        )
                        Icon(
                            imageVector = maneuverIcon(then.maneuverType, then.modifier),
                            contentDescription = null,
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            then.instruction,
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Compact status line: distance left, time left, arrival clock. */
@Composable
fun NavSummary(
    nav: NavState,
    units: Units,
    etaText: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SummaryCell("Arrive", etaText)
        SummaryCell("Left", formatDistance(nav.remainingDistanceM, units))
        SummaryCell(
            "Time",
            com.donovan.carlauncher.nav.formatDuration(nav.remainingDurationS),
        )
    }
}

@Composable
private fun SummaryCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 25% up on the token size. Compose's type scale moves in coarse steps - titleLarge to
 * headlineMedium is a 27% jump in one direction and 0% in the other - so the compact
 * banner scales its own tokens instead of hunting for a token that happens to fit.
 */
private fun TextStyle.scaled(factor: Float = 1.25f): TextStyle = copy(
    fontSize = fontSize * factor,
    lineHeight = if (lineHeight.isSpecified) lineHeight * factor else lineHeight,
)
