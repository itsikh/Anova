package com.template.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.drawToBitmap
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A floating, draggable bug-report button rendered as an overlay on all screens.
 *
 * Only rendered when [visible] is `true` (admin mode on + "Bug Report Button" toggled in
 * Settings → Debug — unlock admin mode by tapping the version number 7 times).
 *
 * ## Interaction
 * - **Drag** — repositions the button anywhere on screen.
 * - **Tap** (total movement < 10 px) — hides the button for one frame, captures a screenshot
 *   via [View.drawToBitmap], then calls [onScreenshotCaptured] with the [Bitmap].
 *
 * ## Bug fix note
 * The previous implementation used `detectDragGestures` which requires movement beyond the
 * system touch-slop before it fires `onDragStart`. A simple tap never crosses that threshold,
 * so `onDragEnd` was never called and taps were silently ignored. This version uses
 * `awaitEachGesture` + `awaitFirstDown` to handle both taps and drags in a single handler.
 */
@Composable
fun FloatingBugButton(
    visible: Boolean,
    onScreenshotCaptured: (Bitmap) -> Unit
) {
    if (!visible) return

    var offsetX by remember { mutableFloatStateOf(16f) }
    var offsetY by remember { mutableFloatStateOf(400f) }
    var capturing by remember { mutableStateOf(false) }
    val view = LocalView.current

    LaunchedEffect(capturing) {
        if (capturing) {
            delay(80) // one frame — lets the button hide before the screenshot
            val bitmap = view.drawToBitmap()
            capturing = false
            onScreenshotCaptured(bitmap)
        }
    }

    if (!capturing) {
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .size(52.dp)
                .shadow(6.dp, CircleShape)
                .background(MaterialTheme.colorScheme.error, CircleShape)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var moved = Offset.Zero

                        // Track all pointer movement until the finger lifts
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            val delta = change.positionChange()
                            moved += Offset(abs(delta.x), abs(delta.y))

                            // Only reposition the button if it's clearly a drag
                            if (moved.x > 10f || moved.y > 10f) {
                                offsetX += delta.x
                                offsetY += delta.y
                            }
                            change.consume()
                        } while (event.changes.any { it.pressed })

                        // Finger lifted — was it a tap or a drag?
                        if (moved.x <= 10f && moved.y <= 10f) {
                            capturing = true
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.BugReport,
                contentDescription = "Report Bug",
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}
