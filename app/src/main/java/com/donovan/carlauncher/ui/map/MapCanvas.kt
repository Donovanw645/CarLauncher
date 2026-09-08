package com.donovan.carlauncher.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Hosts the shared [MapHolder.mapView]. Because the same View instance is reused by the
 * Home preview and the Maps screen, it is explicitly detached from its previous parent
 * before being handed to a new [AndroidView].
 */
@Composable
fun MapCanvas(
    holder: MapHolder,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = {
            holder.detachFromParent()
            holder.mapView
        },
    )

    DisposableEffect(holder) {
        holder.onResume()
        onDispose { holder.onPause() }
    }
}
