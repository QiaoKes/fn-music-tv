package com.fnmusic.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.fnmusic.tv.AppContainer
import com.fnmusic.tv.core.data.repository.SessionState
import com.fnmusic.tv.core.data.server.ServerUrlNormalizer
import com.fnmusic.tv.core.data.server.ServerUrlResult
import com.fnmusic.tv.core.model.AppError
import com.fnmusic.tv.core.model.AppException
import com.fnmusic.tv.core.model.Playlist
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Route { Home, My, Player }

@Composable
fun FnMusicApp(container: AppContainer, onMoveToBackground: () -> Unit) {
    val session by container.sessionRepository.state.collectAsStateWithLifecycle()
    val playback by container.playbackController.state.collectAsStateWithLifecycle()
    FnMusicTheme {
        Box(Modifier.fillMaxSize().background(FnColors.Background)) {
            when (val current = session) {
                SessionState.Loading -> BrandLoading()
                is SessionState.SignedOut -> {
                    LoginScreen(
                        savedServer = current.savedServer,
                        recentServers = current.recentServers,
                        initialError = current.error,
                        onLogin = { server, https, user, password, remember ->
                            container.sessionRepository.login(server, https, user, password, remember)
                        },
                    )
                }
                is SessionState.SignedIn -> {
                    AuthenticatedApp(container, current, playback, onMoveToBackground)
                }
            }
        }
    }
}

@Composable
private fun BrandLoading() {
    Column(Modifier.fillMaxSize().padding(64.dp), verticalArrangement = Arrangement.Center) {
        Text("飞牛音乐 TV", color = FnColors.Text, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("正在载入", color = FnColors.Muted, fontSize = 24.sp)
    }
}

@Composable
internal fun LoginScreen(
    savedServer: String,
    recentServers: List<String>,
    initialError: AppError?,
    onLogin: suspend (String, Boolean, String, CharArray, Boolean) -> Unit,
) {
    val initialServer = remember(savedServer) { ServerUrlNormalizer.editableInput(savedServer, false) }
    var server by remember(initialServer) { mutableStateOf(initialServer.address) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rememberLogin by remember { mutableStateOf(true) }
    var https by remember(initialServer) { mutableStateOf(initialServer.useHttps) }
    var passwordVisible by remember { mutableStateOf(false) }
    var showServerHistory by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var loginAttempted by remember { mutableStateOf(false) }
    var error by remember(initialError) { mutableStateOf(initialError) }
    val scope = rememberCoroutineScope()
    val serverFocus = remember { FocusRequester() }
    val historyFocus = remember { FocusRequester() }
    val usernameFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val visibilityFocus = remember { FocusRequester() }
    val rememberFocus = remember { FocusRequester() }
    val httpsFocus = remember { FocusRequester() }
    val loginFocus = remember { FocusRequester() }
    val validServer = ServerUrlNormalizer.normalize(server, https) is ServerUrlResult.Valid
    val canSubmit = !submitting && validServer && username.isNotBlank() && password.isNotBlank()
    LaunchedEffect(Unit) { if (savedServer.isBlank()) serverFocus.requestFocus() else usernameFocus.requestFocus() }

    Row(
        Modifier.fillMaxSize().padding(horizontal = 80.dp, vertical = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("飞牛音乐 TV", color = FnColors.Text, fontSize = 52.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                listOf(34, 62, 48, 78).forEachIndexed { index, barHeight ->
                    Box(
                        Modifier.width(14.dp).height(barHeight.dp)
                            .background(if (index == 3) FnColors.Coral else FnColors.Teal),
                    )
                }
            }
        }
        Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("连接音乐服务器", color = FnColors.Text, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                TvTextField(
                    value = server,
                    onValueChange = {
                        val edited = ServerUrlNormalizer.editableInput(it, https)
                        server = edited.address
                        https = edited.useHttps
                    },
                    label = "NAS 地址",
                    modifier = Modifier.weight(1f),
                    downFocus = usernameFocus,
                    rightFocus = historyFocus.takeIf { recentServers.isNotEmpty() },
                    inputModifier = Modifier.focusRequester(serverFocus).focusProperties {
                        right = if (recentServers.isNotEmpty()) historyFocus else FocusRequester.Cancel
                        down = usernameFocus
                    },
                )
                Button(
                    enabled = recentServers.isNotEmpty(),
                    onClick = { showServerHistory = true },
                    modifier = Modifier.width(104.dp).height(56.dp)
                        .semantics { contentDescription = "历史" }
                        .focusRequester(historyFocus)
                        .focusProperties {
                            left = serverFocus
                            down = usernameFocus
                        },
                ) { Text("历史", fontSize = 19.sp) }
            }
            TvTextField(
                username,
                { username = it },
                "账号",
                upFocus = serverFocus,
                downFocus = passwordFocus,
                inputModifier = Modifier.focusRequester(usernameFocus).focusProperties {
                    up = serverFocus
                    down = passwordFocus
                },
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                TvTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "密码",
                    modifier = Modifier.weight(1f),
                    upFocus = usernameFocus,
                    downFocus = rememberFocus,
                    rightFocus = visibilityFocus,
                    inputModifier = Modifier.focusRequester(passwordFocus).focusProperties {
                        up = usernameFocus
                        right = visibilityFocus
                        down = rememberFocus
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                )
                Button(
                    onClick = { passwordVisible = !passwordVisible },
                    modifier = Modifier.width(136.dp).height(56.dp)
                        .semantics { contentDescription = "显示或隐藏密码" }
                        .focusRequester(visibilityFocus)
                        .focusProperties {
                            up = usernameFocus
                            left = passwordFocus
                            down = rememberFocus
                        },
                ) {
                    Text(if (passwordVisible) "隐藏密码" else "显示密码", fontSize = 18.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallToggle(
                    "保持登录",
                    rememberLogin,
                    Modifier.focusRequester(rememberFocus).focusProperties {
                        up = passwordFocus
                        right = httpsFocus
                        down = httpsFocus
                    },
                ) { rememberLogin = !rememberLogin }
                SmallToggle(
                    "HTTPS",
                    https,
                    Modifier.focusRequester(httpsFocus).focusProperties {
                        up = rememberFocus
                        left = rememberFocus
                        down = if (canSubmit) loginFocus else FocusRequester.Cancel
                    },
                ) { https = !https }
            }
            val statusMessage = error?.let {
                if (loginAttempted && it == AppError.Unauthenticated) "账号或密码错误" else errorMessage(it)
            } ?: if (!https) "局域网 HTTP 连接未加密" else ""
            Text(
                statusMessage,
                color = if (error == null) FnColors.Warning else FnColors.Coral,
                fontSize = 19.sp,
                maxLines = 1,
            )
            Button(
                enabled = canSubmit,
                onClick = {
                    if (submitting) return@Button
                    submitting = true
                    loginAttempted = true
                    error = null
                    val submittedPassword = password.toCharArray()
                    password = ""
                    scope.launch {
                        runCatching { onLogin(server, https, username, submittedPassword, rememberLogin) }
                            .onFailure { error = (it as? AppException)?.error ?: AppError.Unknown() }
                        submitting = false
                    }
                },
                modifier = Modifier.fillMaxWidth().height(58.dp)
                    .semantics { contentDescription = "登录" }
                    .focusRequester(loginFocus)
                    .focusProperties { up = httpsFocus },
            ) {
                Text(if (submitting) "正在登录" else "登录", fontSize = 23.sp)
            }
        }
    }

    if (showServerHistory) {
        val firstHistoryFocus = remember { FocusRequester() }
        Dialog(onDismissRequest = { showServerHistory = false }) {
            Column(
                Modifier.width(540.dp).background(FnColors.Surface, RoundedCornerShape(8.dp)).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("最近服务器", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                recentServers.forEachIndexed { index, recent ->
                    val editable = ServerUrlNormalizer.editableInput(recent, https)
                    Button(
                        onClick = {
                            server = editable.address
                            https = editable.useHttps
                            showServerHistory = false
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                            .then(if (index == 0) Modifier.focusRequester(firstHistoryFocus) else Modifier),
                    ) {
                        Text(editable.address, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Button(
                    onClick = { showServerHistory = false },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text("取消", fontSize = 20.sp) }
            }
        }
        LaunchedEffect(Unit) { firstHistoryFocus.requestFocus() }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    inputModifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    upFocus: FocusRequester? = null,
    downFocus: FocusRequester? = null,
    leftFocus: FocusRequester? = null,
    rightFocus: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var imeWasVisible by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible

    LaunchedEffect(editing) {
        if (editing) {
            delay(200)
            keyboard?.show()
        }
    }
    LaunchedEffect(editing, imeVisible) {
        when {
            !editing -> imeWasVisible = false
            imeVisible -> imeWasVisible = true
            imeWasVisible -> {
                editing = false
                imeWasVisible = false
            }
        }
    }
    BackHandler(enabled = focused && editing) {
        editing = false
        imeWasVisible = false
        keyboard?.hide()
    }

    fun finishEditing(target: FocusRequester?) {
        editing = false
        imeWasVisible = false
        keyboard?.hide()
        target?.requestFocus()
    }

    Column(
        modifier.onPreviewKeyEvent { event ->
            if (editing) return@onPreviewKeyEvent false
            if (event.key == Key.Enter || event.key == Key.DirectionCenter) {
                if (event.type == KeyEventType.KeyDown) editing = true
                return@onPreviewKeyEvent true
            }
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val target = when (event.key) {
                Key.DirectionUp -> upFocus
                Key.DirectionDown -> downFocus
                Key.DirectionLeft -> leftFocus
                Key.DirectionRight -> rightFocus
                else -> null
            }
            when {
                target != null -> {
                    target.requestFocus()
                    true
                }
                else -> false
            }
        },
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(label, color = FnColors.Muted, fontSize = 18.sp)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = inputModifier.fillMaxWidth().height(56.dp)
                .semantics { contentDescription = label }
                .onFocusChanged {
                    focused = it.isFocused
                    if (!it.isFocused) {
                        editing = false
                        imeWasVisible = false
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (waitForUpOrCancellation(pass = PointerEventPass.Initial) != null) {
                            editing = true
                        }
                    }
                }
                .background(FnColors.Surface, RoundedCornerShape(6.dp))
                .border(if (focused) 3.dp else 1.dp, if (focused) FnColors.Coral else Color(0xFF454A50), RoundedCornerShape(6.dp))
                .padding(horizontal = 16.dp, vertical = 13.dp),
            readOnly = !editing,
            textStyle = TextStyle(color = FnColors.Text, fontSize = 22.sp),
            cursorBrush = SolidColor(FnColors.Coral),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = if (downFocus == null) ImeAction.Done else ImeAction.Next),
            keyboardActions = KeyboardActions(
                onNext = { finishEditing(downFocus) },
                onDone = { finishEditing(null) },
            ),
            visualTransformation = visualTransformation,
        )
    }
}

@Composable
private fun SmallToggle(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.height(52.dp).semantics { contentDescription = label }) {
        Text("${if (selected) "ON" else "OFF"}  $label", fontSize = 18.sp)
    }
}

@Composable
private fun TopBar(nowPlaying: Boolean, selected: Route, onHome: () -> Unit, onMy: () -> Unit, onPlayer: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(82.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (nowPlaying) {
            Button(onClick = onPlayer, modifier = Modifier.width(340.dp).height(64.dp)) { Text("正在播放", fontSize = 22.sp) }
        } else {
            Text("飞牛音乐 TV", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onHome, enabled = selected != Route.Home) { Text("首页", fontSize = 22.sp) }
            Button(onClick = onMy, enabled = selected != Route.My) { Text("我的", fontSize = 22.sp) }
        }
    }
}

@Composable
private fun HomeScreen(nowPlaying: Boolean, loadPlaylists: suspend () -> List<Playlist>, onMy: () -> Unit, onPlayer: () -> Unit) {
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { loadPlaylists() }.onSuccess { playlists = it }.onFailure { error = true }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 40.dp)) {
        TopBar(nowPlaying, Route.Home, {}, onMy, onPlayer)
        Spacer(Modifier.height(42.dp))
        Text("听点什么", fontSize = 42.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            item { MediaCard("随机漫游", "从曲库里遇见下一首", FnColors.Teal, enabled = false) { } }
            items(playlists, key = { it.guid.value }) { playlist ->
                MediaCard(playlist.name, "歌单", FnColors.Coral, enabled = false) { }
            }
            item {
                MediaCard(
                    "全部歌单",
                    if (error) "暂时无法载入" else "浏览完整列表",
                    FnColors.Muted,
                    enabled = false,
                ) { }
            }
        }
    }
}

@Composable
private fun MediaCard(title: String, subtitle: String, accent: Color, enabled: Boolean, onClick: () -> Unit) {
    Button(enabled = enabled, onClick = onClick, modifier = Modifier.size(width = 300.dp, height = 220.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.width(54.dp).height(7.dp).background(accent))
            Spacer(Modifier.height(22.dp))
            Text(title, fontSize = 27.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Text(subtitle, color = FnColors.Muted, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MyScreen(
    username: String,
    serverName: String,
    nowPlaying: Boolean,
    onHome: () -> Unit,
    onLogout: suspend () -> Unit,
    onPlayer: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var signingOut by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 40.dp)) {
        TopBar(nowPlaying, Route.My, onHome, {}, onPlayer)
        Row(
            Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("我的音乐", fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Text("$username · $serverName", color = FnColors.Muted, fontSize = 20.sp)
            }
            Button(
                enabled = !signingOut,
                onClick = {
                    signingOut = true
                    scope.launch { runCatching { onLogout() } }
                },
            ) { Text("切换账号", fontSize = 20.sp) }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { LibraryBand("歌手", listOf("常听歌手", "全部歌手")) }
            item { LibraryBand("专辑", listOf("最近专辑", "全部专辑")) }
            item { LibraryBand("音乐库", listOf("全部歌曲", "共享音乐库")) }
        }
    }
}

@Composable
private fun LibraryBand(title: String, entries: List<String>) {
    Column {
        Text(title, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(entries) { entry ->
                Button(enabled = false, onClick = {}, modifier = Modifier.size(width = 250.dp, height = 92.dp)) {
                    Text(entry, fontSize = 22.sp)
                }
            }
        }
    }
}

@Composable
private fun PlayerScreen(
    title: String,
    artist: String,
    isPlaying: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 58.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(64.dp),
    ) {
        Box(
            Modifier.size(470.dp).background(FnColors.Surface, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(title.take(1).uppercase(), fontSize = 116.sp, color = FnColors.Teal)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 48.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Text(artist.ifBlank { "飞牛音乐" }, fontSize = 28.sp, color = FnColors.Muted)
            Spacer(Modifier.height(52.dp))
            Text("纯音乐或暂无歌词", fontSize = 34.sp, color = FnColors.Muted)
            Spacer(Modifier.height(62.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Button(onClick = onPrevious, modifier = Modifier.semantics { contentDescription = "上一首" }) { Text("上一首") }
                Button(onClick = onPlayPause, modifier = Modifier.semantics { contentDescription = if (isPlaying) "暂停" else "播放" }) {
                    Text(if (isPlaying) "暂停" else "播放")
                }
                Button(onClick = onNext, modifier = Modifier.semantics { contentDescription = "下一首" }) { Text("下一首") }
            }
        }
    }
}

private fun errorMessage(error: AppError): String = when (error) {
    AppError.Unauthenticated -> "登录已失效，请重新登录"
    AppError.AccountDisabled -> "账号已禁用"
    AppError.NetworkUnavailable -> "无法连接 NAS，请检查地址和网络"
    AppError.NotFound -> "服务器接口不可用"
    else -> "连接失败，请重试"
}
