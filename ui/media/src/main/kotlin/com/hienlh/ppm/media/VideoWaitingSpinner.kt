package com.hienlh.ppm.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.colorsFade
import kotlinx.coroutines.delay

/**
 * What video-player.tsx draws over a video that is waiting for data:
 * `<Loader2 className="absolute size-8 animate-spin text-white/80 pointer-events-none transition-opacity delay-300 starting:opacity-0" />`.
 * It is there from the moment the player waits, but clear for 300 ms and then faded in over 150 ms
 * (`transition-opacity` is the same ease as `transition-colors`), so a seek that decodes sooner never
 * flashes it over the picture, on every release and every arrow key. It takes no touch.
 */
@Composable
fun VideoWaitingSpinner(modifier: Modifier = Modifier) {
    var due by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(300); due = true }
    val opacity = colorsFade(due)
    PpmIcon(ProductIcon.Loader2, 32.dp, Color.White.copy(alpha = 0.8f), modifier.graphicsLayer { alpha = opacity }, spinning = true)
}
