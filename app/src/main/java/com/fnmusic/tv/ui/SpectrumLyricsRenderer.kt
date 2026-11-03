package com.fnmusic.tv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameNanos
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** 行尾过渡窗: 当前唱行结束后向前滚动到下一行的准备时长(ms)。 */
private const val GLIDE_WINDOW_MS = 600L

/**
 * Spectrum 频谱模式的自绘歌词渲染器。
 *
 * 核心:
 *  - 当前行的视觉中心永远精确锚定屏幕垂直正中: 滚动位置是播放时间的连续函数,
 *    当前行播放期间向下一行平缓推进; 恢复/seek 为纯函数直接落点, 不等待行尾。
 *  - 亮度只由"行中心与屏幕中心的距离"决定: smoothstep 渐隐直达屏幕顶/底边 (alpha=0),
 *    中线上下完全对称, 任何分辨率/字号下两侧亮度天然一致。
 *  - 全部几何在像素域计算, 行高来自 TextMeasurer 实测, 不做密度/字号假设。
 *  - 动画: 加载/切歌整体淡入 + 上移回落; seek 短暂变暗再回亮; 当前行逐音节扫亮。
 */
@Composable
internal fun SpectrumLyricsRenderer(
    lyrics: SyncedLyrics?,
    positionMs: Long,
    loading: Boolean,
    failed: Boolean,
    staticLyric: String?,
    modifier: Modifier = Modifier,
) {
    val positionState = rememberUpdatedState(positionMs)
    val jumpAlpha = remember { Animatable(1f) }
    val entryAlpha = remember { Animatable(1f) }
    val entryOffset = remember { Animatable(1f) }
    val lastSeenTimeMs = remember { mutableLongStateOf(Long.MIN_VALUE) }

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }
        val textMeasurer = rememberTextMeasurer()
        val mainTextStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp)
        val translationTextStyle = TextStyle(fontSize = 17.sp, lineHeight = 22.sp)
        val translationGapPx = with(density) { 12.dp.toPx() }
        val rowGapPx = with(density) { 16.dp.toPx() }
        val sidePadPx = with(density) { 12.dp.toPx() }

        val rows by produceState<List<SpectrumLyricRowMetric>?>(initialValue = null, lyrics, widthPx) {
            if (lyrics == null) {
                value = null
                return@produceState
            }
            value = withContext(Dispatchers.Default) {
                measureSpectrumRows(
                    lyrics = lyrics,
                    maxWidthPx = widthPx,
                    textMeasurer = textMeasurer,
                    mainTextStyle = mainTextStyle,
                    translationTextStyle = translationTextStyle,
                    translationGapPx = translationGapPx,
                    rowGapPx = rowGapPx,
                )
            }
        }

        // 加载/切歌的进场动画: 淡入 + 内容自下而上进入定位。
        LaunchedEffect(lyrics) {
            entryAlpha.snapTo(0.1f)
            entryOffset.snapTo(1f)
            entryAlpha.animateTo(1f, tween(320, easing = LinearOutSlowInEasing))
            entryOffset.animateTo(0f, tween(320, easing = LinearOutSlowInEasing))
        }

        val pulse by rememberInfiniteTransition(label = "spectrumLyricsStatics").animateFloat(
            initialValue = 0.35f,
            targetValue = 0.75f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "spectrumLyricsPulse",
        )

        LaunchedEffect(Unit) {
            var jumpedNow = false
            while (isActive) {
                withFrameNanos {
                    val t = positionState.value.toLong()
                    val prev = lastSeenTimeMs.longValue
                    if (prev != Long.MIN_VALUE && abs(t - prev) > 2500) {
                        lastSeenTimeMs.longValue = t
                        jumpedNow = true
                    } else {
                        lastSeenTimeMs.longValue = t
                    }
                }
                if (jumpedNow) {
                    jumpedNow = false
                    jumpAlpha.snapTo(0.35f)
                    jumpAlpha.animateTo(1f, tween(260, easing = LinearEasing))
                }
            }
        }

        Canvas(modifier = modifier.fillMaxSize()) {
            val time = positionState.value.toLong()
            val viewHeight = size.height
            val viewCenterY = viewHeight / 2f
            val globalAlpha = jumpAlpha.value * entryAlpha.value
            val entryTranslatePx = 36.dp.toPx() * entryOffset.value

            fun drawCenteredStatic(text: TextLayoutResult, alpha: Float) {
                drawText(
                    textLayoutResult = text,
                    color = FnColors.Text,
                    topLeft = Offset(
                        x = (size.width - text.size.width) / 2f,
                        y = viewHeight / 2f - text.size.height / 2f + entryTranslatePx,
                    ),
                    alpha = alpha * globalAlpha,
                )
            }

            val rowsValue = rows
            when {
                loading -> drawCenteredStatic(
                    textMeasurer.measure(
                        text = "歌词加载中",
                        style = TextStyle(fontSize = 20.sp),
                        constraints = Constraints(maxWidth = widthPx),
                    ),
                    pulse,
                )
                failed -> drawCenteredStatic(
                    textMeasurer.measure(
                        text = "歌词暂时无法加载",
                        style = TextStyle(fontSize = 20.sp),
                        constraints = Constraints(maxWidth = widthPx),
                    ),
                    pulse,
                )
                rowsValue == null -> return@Canvas
                rowsValue.isEmpty() || staticLyric != null -> drawCenteredStatic(
                    textMeasurer.measure(
                        text = staticLyric ?: "纯音乐或暂无歌词",
                        style = TextStyle(fontSize = 20.sp, lineHeight = 28.sp),
                        constraints = Constraints(maxWidth = widthPx),
                    ),
                    0.55f,
                )
            }

            val measuredRows = rowsValue ?: return@Canvas
            if (measuredRows.isEmpty() || staticLyric != null) return@Canvas

            var activeIndex = 0
            for (index in measuredRows.indices) {
                if (measuredRows[index].startMs <= time) activeIndex = index else break
            }
            val active = measuredRows[activeIndex]
            val next = measuredRows.getOrNull(activeIndex + 1)

            // 滚动推进只发生在"行尾的短促过渡窗 + 行间间隙"(默认窗口 = 行尾 600ms)。
            // 演唱期间"主行文字中心"精确钉在屏幕正中(译文挂在主行下方, 不参与对齐,
            // 否则主行会被译文推得偏上); 间奏中平缓滑向下一行, 行衔接处无跳变。
            val glideStartMs = (active.endMs - GLIDE_WINDOW_MS).coerceAtLeast(active.startMs)
            val nextStartMs = next?.startMs ?: active.endMs
            val glideDenominator = (nextStartMs - glideStartMs).coerceAtLeast(1)
            val glideFraction = ((time - glideStartMs).toFloat() / glideDenominator).coerceIn(0f, 1f)

            val activeCenterY = active.topPx + active.mainHeight / 2f
            var targetScroll = activeCenterY - viewCenterY
            if (next != null && glideFraction > 0f) {
                val nextCenterY = next.topPx + next.mainHeight / 2f
                targetScroll += (nextCenterY - activeCenterY) * glideFraction
            }

            for (row in measuredRows) {
                val topSafe = row.topPx - targetScroll + entryTranslatePx
                if (topSafe + row.heightPx < -64f || topSafe > size.height + 64f) continue
                val rowCenter = topSafe + row.heightPx / 2f
                val relative = (abs(rowCenter - viewCenterY) / viewCenterY).coerceIn(0f, 1f)
                val linear = 1f - relative
                val fade = linear * linear * (3f - 2f * linear) // smoothstep → 屏幕顶/底处 alpha=0
                if (fade <= 0.015f) continue

                val isActiveRow = row.startMs == active.startMs
                val mainTop = topSafe
                val translationTop = mainTop + row.mainHeight + row.translationGapPx
                val sweepPx = when {
                    row.syllables != null -> row.sweepX(time, textMeasurer)
                    isActiveRow -> row.mainLayout.size.width.toFloat() *
                        ((time - row.startMs).toFloat() / (row.endMs - row.startMs).coerceAtLeast(1))
                        .coerceIn(0f, 1f)
                    else -> 0f
                }

                if (isActiveRow) {
                    // 卡拉OK: 未唱部分暗灰, 已唱部分亮白(逐音节/整行进度精确)。
                    drawText(
                        textLayoutResult = row.mainLayout,
                        color = FnColors.Text.copy(alpha = 0.36f * fade),
                        topLeft = Offset(sidePadPx, mainTop),
                    )
                    if (sweepPx > 0f) {
                        clipRect(
                            left = sidePadPx,
                            top = mainTop,
                            right = sidePadPx + sweepPx,
                            bottom = mainTop + row.mainHeight,
                        ) {
                            drawText(
                                textLayoutResult = row.mainLayout,
                                color = FnColors.Text.copy(alpha = fade),
                                topLeft = Offset(sidePadPx, mainTop),
                            )
                        }
                    }
                    val translation = row.translationLayout
                    if (translation != null) {
                        drawText(
                            textLayoutResult = translation,
                            color = FnColors.Text.copy(alpha = 0.55f * fade),
                            topLeft = Offset(sidePadPx, translationTop),
                        )
                    }
                } else {
                    drawText(
                        textLayoutResult = row.mainLayout,
                        color = FnColors.Text.copy(alpha = 0.36f * fade),
                        topLeft = Offset(sidePadPx, mainTop),
                    )
                    val translation = row.translationLayout
                    if (translation != null) {
                        drawText(
                            textLayoutResult = translation,
                            color = FnColors.Text.copy(alpha = 0.2f * fade),
                            topLeft = Offset(sidePadPx, translationTop),
                        )
                    }
                }
            }
        }

        // 调试中轴刻度线: 常显标尺, 便于目测当前行是否正中及淡出范围。
        LyricsDebugRuler(
            style = LyricsDebugFadeStyle.CenterSmoothstep,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 频谱模式一行歌词的实测度量(全部像素域), 行高含译本行与行间隙。 */
internal class SpectrumLyricRowMetric(
    val startMs: Long,
    val endMs: Long,
    val mainLayout: TextLayoutResult,
    val translationLayout: TextLayoutResult?,
    val translationGapPx: Float,
    val syllables: List<KaraokeSyllable>?,
) {
    var topPx: Float = 0f
        internal set
    var sweepContent: String = ""
        internal set

    val mainHeight: Int = mainLayout.size.height
    val heightPx: Int =
        mainHeight + (translationLayout?.size?.height ?: 0) + translationGapPx.roundToInt()

    /** 依据音节时间(逐音节精确)计算已唱前缀宽度(px)。 */
    fun sweepX(time: Long, measurer: TextMeasurer): Float {
        val all = syllables ?: return mainHeight.toFloat()
        var characters = 0
        for (syllable in all) {
            if (time >= syllable.end) {
                characters += syllable.content.length
                continue
            }
            if (time > syllable.start) {
                val span = (syllable.end - syllable.start).coerceAtLeast(1)
                val local = (time - syllable.start).toFloat() / span
                characters += (syllable.content.length * local).roundToInt()
            }
            break
        }
        if (characters <= 0) return 0f
        val prefix = sweepContent.take(characters.coerceIn(0, sweepContent.length))
        val layout = measurer.measure(
            text = prefix,
            style = mainLayout.layoutInput.style,
            constraints = Constraints(maxWidth = max(mainLayout.size.width, 1)),
            maxLines = 1,
            softWrap = false,
        )
        return layout.size.width.toFloat()
    }
}

/** 行度量: 在后台线程逐行实测, 并累计每行顶部位置。 */
private fun measureSpectrumRows(
    lyrics: SyncedLyrics,
    maxWidthPx: Int,
    textMeasurer: TextMeasurer,
    mainTextStyle: TextStyle,
    translationTextStyle: TextStyle,
    translationGapPx: Float,
    rowGapPx: Float,
): List<SpectrumLyricRowMetric> {
    val result = mutableListOf<SpectrumLyricRowMetric>()
    var top = 0f
    for (line in lyrics.lines) {
        when (line) {
            is KaraokeLine -> {
                if (line.syllables.isEmpty()) continue
                val content = line.syllables.joinToString(separator = "") { it.content }
                val mainLayout = textMeasurer.measure(
                    text = content,
                    style = mainTextStyle,
                    constraints = Constraints(maxWidth = maxWidthPx),
                    maxLines = 3,
                )
                val translationLayout = line.translation
                    ?.takeIf(String::isNotBlank)
                    ?.let {
                        textMeasurer.measure(
                            text = it,
                            style = translationTextStyle,
                            constraints = Constraints(maxWidth = maxWidthPx),
                            maxLines = 2,
                        )
                    }
                val metric = SpectrumLyricRowMetric(
                    startMs = line.start.toLong(),
                    endMs = line.end.toLong(),
                    mainLayout = mainLayout,
                    translationLayout = translationLayout,
                    translationGapPx = translationGapPx,
                    syllables = line.syllables,
                )
                metric.topPx = top
                metric.sweepContent = content
                result.add(metric)
                top += metric.heightPx + rowGapPx
            }

            is SyncedLine -> {
                if (line.content.isBlank()) continue
                val mainLayout = textMeasurer.measure(
                    text = line.content,
                    style = mainTextStyle,
                    constraints = Constraints(maxWidth = maxWidthPx),
                    maxLines = 3,
                )
                val translationLayout = line.translation
                    ?.takeIf(String::isNotBlank)
                    ?.let {
                        textMeasurer.measure(
                            text = it,
                            style = translationTextStyle,
                            constraints = Constraints(maxWidth = maxWidthPx),
                            maxLines = 2,
                        )
                    }
                val metric = SpectrumLyricRowMetric(
                    startMs = line.start.toLong(),
                    endMs = line.end.toLong(),
                    mainLayout = mainLayout,
                    translationLayout = translationLayout,
                    translationGapPx = translationGapPx,
                    syllables = null,
                )
                metric.topPx = top
                metric.sweepContent = line.content
                result.add(metric)
                top += metric.heightPx + rowGapPx
            }
        }
    }
    return result
}
