package com.fnmusic.tv.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.tv.material3.Button as TvMaterialButton
import androidx.tv.material3.ButtonBorder
import androidx.tv.material3.ButtonColors
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ButtonScale
import androidx.tv.material3.ButtonShape

@Composable
internal fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    scale: ButtonScale = ButtonDefaults.scale(),
    shape: ButtonShape = ButtonDefaults.shape(),
    colors: ButtonColors = ButtonDefaults.colors(),
    border: ButtonBorder = ButtonDefaults.border(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    TvMaterialButton(
        onClick = onClick,
        modifier = modifier.touchCompatibleClick(enabled = enabled, onClick = onClick),
        enabled = enabled,
        scale = scale,
        shape = shape,
        colors = colors,
        border = border,
        contentPadding = contentPadding,
        content = content,
    )
}

private fun Modifier.touchCompatibleClick(enabled: Boolean, onClick: () -> Unit): Modifier =
    if (!enabled) {
        this
    } else {
        pointerInput(enabled, onClick) {
            awaitEachGesture {
                val down = awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial,
                )
                val pointerId = down.id
                val downPosition = down.position
                var moved = false

                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                    if ((change.position - downPosition).getDistance() > viewConfiguration.touchSlop) {
                        moved = true
                    }
                    if (!change.pressed && change.previousPressed) {
                        if (!moved) {
                            change.consume()
                            onClick()
                        }
                        break
                    }
                    if (!change.pressed) break
                }
            }
        }
    }
