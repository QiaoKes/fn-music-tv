package com.fnmusic.tv.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path

internal fun heartPath(size: Size): Path = Path().apply {
    moveTo(size.width * 0.50f, size.height * 0.90f)
    cubicTo(
        size.width * 0.43f, size.height * 0.84f,
        size.width * 0.10f, size.height * 0.64f,
        size.width * 0.10f, size.height * 0.37f,
    )
    cubicTo(
        size.width * 0.10f, size.height * 0.19f,
        size.width * 0.23f, size.height * 0.08f,
        size.width * 0.38f, size.height * 0.10f,
    )
    cubicTo(
        size.width * 0.45f, size.height * 0.11f,
        size.width * 0.49f, size.height * 0.17f,
        size.width * 0.50f, size.height * 0.25f,
    )
    cubicTo(
        size.width * 0.51f, size.height * 0.17f,
        size.width * 0.56f, size.height * 0.11f,
        size.width * 0.63f, size.height * 0.10f,
    )
    cubicTo(
        size.width * 0.79f, size.height * 0.08f,
        size.width * 0.90f, size.height * 0.20f,
        size.width * 0.90f, size.height * 0.37f,
    )
    cubicTo(
        size.width * 0.90f, size.height * 0.64f,
        size.width * 0.57f, size.height * 0.84f,
        size.width * 0.50f, size.height * 0.90f,
    )
    close()
}
