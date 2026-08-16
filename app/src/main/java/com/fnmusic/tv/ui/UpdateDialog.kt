package com.fnmusic.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.fnmusic.tv.BuildConfig
import com.fnmusic.tv.update.UpdateController
import com.fnmusic.tv.update.UpdateManifest
import com.fnmusic.tv.update.UpdateUiState
import kotlinx.coroutines.launch

@Composable
internal fun UpdateDialogHost(state: UpdateUiState, controller: UpdateController) {
    when (state) {
        UpdateUiState.Disabled, UpdateUiState.Idle -> Unit
        is UpdateUiState.Checking -> if (state.source == com.fnmusic.tv.update.UpdateCheckSource.Manual) {
            UpdateStatusDialog("正在检查更新", "正在从更新服务器读取最新版本…", null, null)
        }
        is UpdateUiState.UpToDate -> UpdateStatusDialog(
            title = "已是最新版本",
            message = "当前版本 ${state.currentVersionName} 已是最新版本。",
            primaryLabel = "知道了",
            onPrimary = controller::dismiss,
        )
        is UpdateUiState.Available -> UpdateAvailableDialog(state, controller)
        is UpdateUiState.Downloading -> UpdateProgressDialog(
            title = "正在下载更新",
            message = "下载完成后会先校验安装包，再由系统确认安装。",
            progress = state.downloadedBytes.toFloat() / state.manifest.apkSize.toFloat(),
            progressText = "${((state.downloadedBytes * 100) / state.manifest.apkSize).coerceIn(0, 100)}% · " +
                "${formatUpdateBytes(state.downloadedBytes)} / ${formatUpdateBytes(state.manifest.apkSize)}",
            onCancel = controller::cancelDownload,
        )
        is UpdateUiState.Verifying -> UpdateProgressDialog(
            title = "正在校验安装包",
            message = "正在检查文件完整性、应用标识、版本和签名。",
            progress = null,
            progressText = "请稍候…",
            onCancel = controller::cancelDownload,
        )
        is UpdateUiState.AwaitingInstallPermission -> UpdateStatusDialog(
            title = "允许安装更新",
            message = "需要先允许回声台安装来自本应用的更新。开启后返回这里，系统仍会再次请你确认安装。",
            primaryLabel = "去开启",
            onPrimary = controller::openInstallPermissionSettings,
            secondaryLabel = "取消",
            onSecondary = controller::dismiss,
        )
        is UpdateUiState.PreparingInstaller -> UpdateStatusDialog(
            title = "正在准备安装",
            message = "安装包已校验通过，正在打开系统安装确认页…",
            primaryLabel = null,
            onPrimary = null,
        )
        is UpdateUiState.AwaitingSystemConfirmation -> UpdateStatusDialog(
            title = "请在系统页面确认安装",
            message = "安装是否继续由 Android 系统最终确认。",
            primaryLabel = null,
            onPrimary = null,
        )
        is UpdateUiState.Error -> UpdateStatusDialog(
            title = if (state.manifest == null) "检查更新失败" else "更新失败",
            message = state.message,
            primaryLabel = "重试",
            onPrimary = if (state.manifest == null) controller::checkManually else controller::startDownload,
            secondaryLabel = "关闭",
            onSecondary = controller::dismiss,
        )
    }
}

@Composable
private fun UpdateAvailableDialog(state: UpdateUiState.Available, controller: UpdateController) {
    val updateFocus = remember { FocusRequester() }
    val laterFocus = remember { FocusRequester() }
    val ignoreFocus = remember { FocusRequester() }
    val notesFocus = remember { FocusRequester() }
    val notesScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val noteBlocks = remember(state.manifest.notes) { parseUpdateNotes(state.manifest.notes) }
    BackHandler(onBack = controller::dismiss)
    Dialog(
        onDismissRequest = controller::dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val dialogWidth = minOf(maxWidth * 0.88f, 640.dp)
            val dialogHeight = minOf(maxHeight * 0.86f, 420.dp)
            val dialogShape = RoundedCornerShape(22.dp)
            Box(
                Modifier
                    .width(dialogWidth)
                    .height(dialogHeight)
                    .clip(dialogShape)
                    .background(
                        Brush.linearGradient(listOf(Color(0xFF20242B), Color(0xFF181B21))),
                        dialogShape,
                    )
                    .border(1.dp, Color(0xFF454B56), dialogShape),
            ) {
                Column(Modifier.fillMaxSize().padding(22.dp)) {
                    UpdateDialogHeader(state)
                    Spacer(Modifier.height(12.dp))
                    VersionComparison(state.manifest)
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("本次更新", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        if (notesScroll.maxValue > 0) {
                            Text("使用遥控器上下键查看更多", color = Color(0xFF737C88), fontSize = 10.sp)
                        }
                    }
                    Spacer(Modifier.height(7.dp))
                    Box(
                        Modifier.fillMaxWidth().weight(1f)
                            .background(Color(0xFF14171C), RoundedCornerShape(14.dp))
                            .border(1.dp, Color(0xFF303640), RoundedCornerShape(14.dp)),
                    ) {
                        Column(
                            Modifier.fillMaxSize()
                                .focusProperties { down = updateFocus }
                                .focusRequester(notesFocus)
                                .onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    val next = when (event.key) {
                                        Key.DirectionUp -> (notesScroll.value - 72).coerceAtLeast(0)
                                        Key.DirectionDown -> (notesScroll.value + 72).coerceAtMost(notesScroll.maxValue)
                                        else -> return@onPreviewKeyEvent false
                                    }
                                    if (next == notesScroll.value) return@onPreviewKeyEvent false
                                    scope.launch { notesScroll.animateScrollTo(next) }
                                    true
                                }
                                .focusable()
                                .verticalScroll(notesScroll)
                                .padding(horizontal = 16.dp, vertical = 11.dp)
                                .padding(end = 9.dp),
                        ) {
                            if (noteBlocks.isEmpty()) {
                                Text("本次更新暂无详细说明。", color = FnColors.Muted, fontSize = 12.sp)
                            } else {
                                UpdateNoteContent(noteBlocks)
                            }
                        }
                        if (notesScroll.maxValue > 0) {
                            BoxWithConstraints(
                                Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(12.dp)
                                    .padding(vertical = 10.dp),
                            ) {
                                val thumbHeight = 30.dp
                                val thumbTravel = (maxHeight - thumbHeight).coerceAtLeast(0.dp)
                                Box(
                                    Modifier.align(Alignment.TopCenter).width(3.dp).fillMaxHeight()
                                        .background(Color(0xFF292E36), RoundedCornerShape(99.dp)),
                                )
                                Box(
                                    Modifier.align(Alignment.TopCenter).graphicsLayer {
                                        translationY = thumbTravel.toPx() *
                                            notesScroll.value.toFloat() / notesScroll.maxValue.toFloat()
                                    }
                                        .width(3.dp).height(thumbHeight)
                                        .background(Color(0xFF7A8491), RoundedCornerShape(99.dp)),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth().height(44.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = controller::startDownload,
                            modifier = Modifier.weight(1.15f).fillMaxHeight()
                                .focusProperties { up = notesFocus; right = laterFocus }
                                .focusRequester(updateFocus),
                            scale = ButtonDefaults.scale(focusedScale = 1.025f),
                            colors = updateButtonColors(),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text("↓", fontSize = 16.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)
                                    Text("立即更新", fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Button(
                            onClick = controller::dismiss,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                                .focusProperties { up = notesFocus; left = updateFocus; right = ignoreFocus }
                                .focusRequester(laterFocus),
                            scale = ButtonDefaults.scale(focusedScale = 1.025f),
                            colors = updateButtonColors(),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("稍后提醒", fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Button(
                            onClick = controller::ignoreAvailableVersion,
                            modifier = Modifier.weight(1.2f).fillMaxHeight()
                                .focusProperties { up = notesFocus; left = laterFocus }
                                .focusRequester(ignoreFocus),
                            scale = ButtonDefaults.scale(focusedScale = 1.025f),
                            colors = updateButtonColors(),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    "忽略 ${state.manifest.versionName}",
                                    fontSize = 13.sp,
                                    lineHeight = 17.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth().height(34.dp)
                            .background(Color(0xFF28211C), RoundedCornerShape(10.dp))
                            .border(1.dp, Color(0xFF49372E), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Box(
                            Modifier.size(16.dp).background(Color(0xFF4A382E), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Text("i", color = Color(0xFFE8B184), fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                        Text(
                            "忽略后，此版本不再自动提示；发布更高版本时仍会正常提醒",
                            color = Color(0xFFD0B297),
                            fontSize = 10.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text("返回键关闭", color = Color(0xFF887568), fontSize = 9.5.sp)
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { updateFocus.requestFocus() }
}

@Composable
private fun UpdateDialogHeader(state: UpdateUiState.Available) {
    Row(
        Modifier.fillMaxWidth().height(42.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(40.dp).background(Color(0xFF392D2C), CircleShape)
                .border(1.dp, Color(0xFF604039), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("↓", color = FnColors.Coral, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
        Text("发现新版本", fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
        if (state.ignored) {
            Text(
                "此版本已忽略",
                color = FnColors.Warning,
                fontSize = 10.sp,
                modifier = Modifier.background(Color(0xFF352F20), RoundedCornerShape(99.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun UpdateNoteContent(blocks: List<UpdateNoteBlock>) {
    blocks.forEachIndexed { index, block ->
        when (block) {
            is UpdateNoteBlock.Heading -> {
                if (index > 0) Spacer(Modifier.height(9.dp))
                Text(block.text, color = Color(0xFFDDE2E8), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
            }
            is UpdateNoteBlock.Item -> {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.padding(top = 6.dp).size(4.dp).background(FnColors.Coral, CircleShape))
                    Text(block.text, color = FnColors.Muted, fontSize = 12.sp, lineHeight = 17.sp)
                }
                Spacer(Modifier.height(3.dp))
            }
            is UpdateNoteBlock.Paragraph -> {
                Text(block.text, color = FnColors.Muted, fontSize = 12.sp, lineHeight = 17.sp)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun VersionComparison(manifest: UpdateManifest) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).background(Color(0xFF14171A), RoundedCornerShape(14.dp))
            .border(1.dp, Color(0xFF32363C), RoundedCornerShape(14.dp)).padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            Column {
                Text("当前版本", color = FnColors.Muted, fontSize = 10.sp)
                Text(BuildConfig.VERSION_NAME, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text("⟶", color = Color(0xFF7D8692), fontSize = 23.sp)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Row(
                Modifier.width(172.dp).fillMaxHeight().background(Color(0xFF17312E), RoundedCornerShape(11.dp))
                    .border(1.dp, Color(0xFF28574F), RoundedCornerShape(11.dp)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("最新版本", color = Color(0xFF87A39D), fontSize = 9.5.sp)
                    Text(manifest.versionName, color = FnColors.Teal, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "推荐",
                    color = Color(0xFF83E1CD),
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.background(Color(0xFF23433D), RoundedCornerShape(99.dp))
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                )
            }
        }
    }
}

internal sealed interface UpdateNoteBlock {
    val text: String

    data class Heading(override val text: String) : UpdateNoteBlock
    data class Item(override val text: String) : UpdateNoteBlock
    data class Paragraph(override val text: String) : UpdateNoteBlock
}

internal fun parseUpdateNotes(notes: String): List<UpdateNoteBlock> {
    val lines = notes.lines().map(String::trim)
    return buildList {
        lines.forEachIndexed { index, line ->
            if (line.isBlank()) return@forEachIndexed
            val explicitHeading = UPDATE_HEADING_PREFIX.find(line)
            val nextContent = lines.drop(index + 1).firstOrNull(String::isNotBlank)
            when {
                explicitHeading != null -> add(
                    UpdateNoteBlock.Heading(line.removeRange(explicitHeading.range).trim()),
                )
                UPDATE_BULLET_PREFIX.containsMatchIn(line) -> add(
                    UpdateNoteBlock.Item(line.replaceFirst(UPDATE_BULLET_PREFIX, "").trim()),
                )
                nextContent != null && UPDATE_BULLET_PREFIX.containsMatchIn(nextContent) -> add(
                    UpdateNoteBlock.Heading(line),
                )
                else -> add(UpdateNoteBlock.Paragraph(line))
            }
        }
    }
}

private val UPDATE_HEADING_PREFIX = Regex("^#{1,6}\\s+")
private val UPDATE_BULLET_PREFIX = Regex("^(?:[-*+•]\\s*)+")

@Composable
private fun UpdateProgressDialog(
    title: String,
    message: String,
    progress: Float?,
    progressText: String,
    onCancel: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier.width(620.dp).background(Color(0xFF1B1C22), RoundedCornerShape(22.dp))
                .border(1.dp, Color(0xFF464A53), RoundedCornerShape(22.dp)).padding(38.dp),
        ) {
            Text(title, fontSize = 31.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(message, color = FnColors.Muted, fontSize = 18.sp)
            Spacer(Modifier.height(26.dp))
            Box(Modifier.fillMaxWidth().height(10.dp).background(Color(0xFF30343B), RoundedCornerShape(99.dp))) {
                Box(
                    Modifier.fillMaxWidth(progress?.coerceIn(0f, 1f) ?: 0.18f).height(10.dp)
                        .background(FnColors.Coral, RoundedCornerShape(99.dp)),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(progressText, color = FnColors.Muted, fontSize = 16.sp)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onCancel) { Text("取消") }
        }
    }
}

@Composable
private fun UpdateStatusDialog(
    title: String,
    message: String,
    primaryLabel: String?,
    onPrimary: (() -> Unit)?,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val firstFocus = remember { FocusRequester() }
    val secondFocus = remember { FocusRequester() }
    val dismiss = onSecondary ?: onPrimary
    if (dismiss != null) BackHandler(onBack = dismiss)
    Dialog(onDismissRequest = { dismiss?.invoke() }) {
        Column(
            Modifier.width(590.dp).background(Color(0xFF1B1C22), RoundedCornerShape(22.dp))
                .border(1.dp, Color(0xFF464A53), RoundedCornerShape(22.dp)).padding(38.dp),
        ) {
            Text(title, fontSize = 31.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(message, color = FnColors.Muted, fontSize = 18.sp)
            if (primaryLabel != null && onPrimary != null) {
                Spacer(Modifier.height(28.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Button(
                        onClick = onPrimary,
                        modifier = Modifier.focusProperties { if (secondaryLabel != null) right = secondFocus }
                            .focusRequester(firstFocus),
                        colors = updateButtonColors(),
                    ) { Text(primaryLabel) }
                    if (secondaryLabel != null && onSecondary != null) {
                        Button(
                            onClick = onSecondary,
                            modifier = Modifier.focusProperties { left = firstFocus }.focusRequester(secondFocus),
                        ) { Text(secondaryLabel) }
                    }
                }
                LaunchedEffect(Unit) { firstFocus.requestFocus() }
            }
        }
    }
}

@Composable
private fun updateButtonColors() = ButtonDefaults.colors(
    containerColor = Color(0xFF343740),
    contentColor = Color(0xFFD7DBE1),
    focusedContainerColor = FnColors.Coral,
    focusedContentColor = Color.White,
)

private fun formatUpdateBytes(bytes: Long): String = if (bytes >= 1024L * 1024L) {
    "%.1f MB".format(bytes / (1024.0 * 1024.0))
} else {
    "%.1f KB".format(bytes / 1024.0)
}
