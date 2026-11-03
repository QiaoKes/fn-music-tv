package com.fnmusic.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 调试刻度线开关: 需要目测歌词位置时改回 true 即整体显示。
internal const val SHOW_LYRICS_DEBUG_RULER = false

// 刻度线密度: 每 20dp 一道短刻度, 每 100dp 一道长刻度,
// 与默认淡出距离(顶部 20dp / 底部 100dp)同格, 便于直接目测淡出边界。
private val LYRICS_RULER_TICK_STEP = 20.dp

/**
 * 歌词淡出模型:
 * - [LyricsDebugFadeStyle.EdgeZones]: 视口顶部/底部各有固定长度的线性淡出区
 *   (顶部 [LyricsDebugRuler.topFadeDistance]dp 处开始变淡, 屏幕边缘完全消失)。
 * - [LyricsDebugFadeStyle.CenterSmoothstep]: 以屏幕正中为中心向上下两侧持续淡出,
 *   正中 alpha=1, 顶/底边缘 alpha=0。
 */
internal enum class LyricsDebugFadeStyle { EdgeZones, CenterSmoothstep }

/**
 * 调试垂直中轴刻度线: 覆盖整个歌词视口的常显标尺。
 *
 *  - 青色中轴 + 每 20dp 短刻度 / 每 100dp 长刻度。
 *  - 横贯全宽的水平正中线, 用于确认当前行是否落在屏幕正中。
 *  - 橙色实线标注"开始变淡"边界, 虚线标注"完全消失"的屏幕边缘。
 */
@Composable
internal fun LyricsDebugRuler(
    style: LyricsDebugFadeStyle,
    topFadeDistance: Dp = 20.dp,
    bottomFadeDistance: Dp = 100.dp,
    modifier: Modifier = Modifier,
) {
    if (!SHOW_LYRICS_DEBUG_RULER) return
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp)
    Canvas(modifier) {
        val centerX = size.width / 2f
        val tickStep = LYRICS_RULER_TICK_STEP.toPx()
        val stroke = 1.4f
        val rulerColor = Color(0xFF40E8C8)
        val fadeInkColor = Color(0xFFFFB155)

        // 中轴: 播放界面中间从顶部到底部的竖直标尺。
        drawLine(rulerColor.copy(alpha = 0.55f), Offset(centerX, 0f), Offset(centerX, size.height), stroke)

        // 刻度自正中向上下两侧生长: 每 20dp 一道短刻度, 每 100dp 一道长刻度;
        // 正中本身就是 0 刻度长刻度, 上下完全镜像对称, 便于读取当前行偏离正中多少格。
        val centerY = size.height / 2f
        val centerSteps = (centerY / tickStep).toInt()
        for (step in 0..centerSteps) {
            val y = centerY + step * tickStep
            val major = step % 5 == 0
            val half = if (major) 18.dp.toPx() else 8.dp.toPx()
            val ink = rulerColor.copy(alpha = if (major) 0.9f else 0.45f)
            drawLine(ink, Offset(centerX - half, y), Offset(centerX + half, y), stroke)
            if (step > 0) drawLine(ink, Offset(centerX - half, centerY - step * tickStep), Offset(centerX + half, centerY - step * tickStep), stroke)
        }

        // 垂直正中参考线: 用于确认当前行是否落在屏幕正中。
        drawLine(
            rulerColor.copy(alpha = 0.95f),
            Offset(0f, size.height / 2f),
            Offset(size.width, size.height / 2f),
            stroke,
        )

        // 淡出标注。
        val edgeDash = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))
        drawLine(fadeInkColor, Offset(0f, 0f), Offset(size.width, 0f), stroke, pathEffect = edgeDash)
        drawLine(fadeInkColor, Offset(0f, size.height), Offset(size.width, size.height), stroke, pathEffect = edgeDash)

        val labelX = centerX + 22.dp.toPx()
        drawText(textMeasurer, "顶部边缘 完全消失", style = labelStyle, topLeft = Offset(labelX, 2.dp.toPx()))
        drawText(
            textMeasurer,
            if (style == LyricsDebugFadeStyle.CenterSmoothstep) "屏幕正中 (不变淡)" else "屏幕正中",
            style = labelStyle,
            topLeft = Offset(labelX, size.height / 2f + 2.dp.toPx()),
        )
        drawText(
            textMeasurer,
            "底部边缘 完全消失",
            style = labelStyle,
            topLeft = Offset(labelX, size.height - 16.dp.toPx()),
        )
        if (style == LyricsDebugFadeStyle.EdgeZones) {
            val topFadePx = topFadeDistance.toPx()
            val bottomFadeStartPx = size.height - bottomFadeDistance.toPx()
            drawLine(fadeInkColor.copy(alpha = 0.95f), Offset(0f, topFadePx), Offset(size.width, topFadePx), stroke)
            drawLine(
                fadeInkColor.copy(alpha = 0.95f),
                Offset(0f, bottomFadeStartPx),
                Offset(size.width, bottomFadeStartPx),
                stroke,
            )
            drawText(
                textMeasurer,
                "顶部 ${topFadeDistance.value.toInt()}dp 开始变淡",
                style = labelStyle,
                topLeft = Offset(labelX, topFadePx + 2.dp.toPx()),
            )
            drawText(
                textMeasurer,
                "底部 ${bottomFadeDistance.value.toInt()}dp 开始变淡",
                style = labelStyle,
                topLeft = Offset(labelX, bottomFadeStartPx + 2.dp.toPx()),
            )
        }
    }
}
