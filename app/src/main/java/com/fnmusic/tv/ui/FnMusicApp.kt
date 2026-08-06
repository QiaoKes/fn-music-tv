package com.fnmusic.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.fnmusic.tv.AppUiDependencies
import com.fnmusic.tv.core.data.repository.LoginDraft
import com.fnmusic.tv.core.data.repository.LoginHistoryEntry
import com.fnmusic.tv.core.data.repository.SessionState
import com.fnmusic.tv.core.data.server.ConnectionResolver
import com.fnmusic.tv.core.data.server.ServerUrlNormalizer
import com.fnmusic.tv.core.data.server.ServerUrlResult
import com.fnmusic.tv.core.model.AppError
import com.fnmusic.tv.core.model.AppException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun FnMusicApp(container: AppUiDependencies, onExitApplication: () -> Unit) {
    val session by container.sessionRepository.state.collectAsStateWithLifecycle()
    val playback by container.playbackController.state.collectAsStateWithLifecycle()
    FnMusicTheme {
        Box(Modifier.fillMaxSize().background(FnColors.Background)) {
            when (val current = session) {
                SessionState.Loading -> BrandLoading()
                is SessionState.Recovering -> SessionRecoveryScreen(
                    state = current,
                    onRetry = container.authenticatedActions::retrySessionRestore,
                    onShowLogin = container.authenticatedActions::showLogin,
                )
                is SessionState.SignedOut -> {
                    LoginScreen(
                        savedServer = current.savedServer,
                        recentServers = current.recentServers,
                        loginHistory = current.loginHistory,
                        initialSelectedProfileId = current.selectedProfileId,
                        initialError = current.error,
                        onLogin = { server, https, user, password, remember, accessCode ->
                            container.sessionRepository.login(server, https, user, password, remember, accessCode)
                        },
                        onHistoryLogin = container.sessionRepository::loginWithHistory,
                        onHistoryDelete = container.sessionRepository::deleteLoginHistory,
                        onHistoryClear = container.sessionRepository::clearLoginHistory,
                        historyDraft = container.sessionRepository::loginDraft,
                    )
                }
                is SessionState.SignedIn -> {
                    AuthenticatedApp(container, current, playback, onExitApplication)
                }
            }
        }
    }
}

@Composable
private fun SessionRecoveryScreen(
    state: SessionState.Recovering,
    onRetry: suspend () -> Unit,
    onShowLogin: suspend () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val retryFocus = remember { FocusRequester() }
    val loginFocus = remember { FocusRequester() }
    Column(
        Modifier.fillMaxSize().padding(64.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("正在重新连接", color = FnColors.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(state.server, color = FnColors.Muted, fontSize = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        state.username?.let { username ->
            Spacer(Modifier.height(5.dp))
            Text(username, color = FnColors.Muted, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "${errorMessage(state.error)} · 第 ${state.attempt} 次重试",
            color = FnColors.Warning,
            fontSize = 19.sp,
            maxLines = 1,
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LoginActionButton(
                onClick = { scope.launch { onRetry() } },
                modifier = Modifier.width(190.dp).height(54.dp)
                    .focusProperties { right = loginFocus }
                    .focusRequester(retryFocus),
            ) { Text("立即重试", fontSize = 20.sp) }
            LoginActionButton(
                onClick = { scope.launch { onShowLogin() } },
                modifier = Modifier.width(190.dp).height(54.dp)
                    .focusProperties { left = retryFocus }
                    .focusRequester(loginFocus),
            ) { Text("切换登录", fontSize = 20.sp) }
        }
    }
    LaunchedEffect(Unit) { retryFocus.requestFocus() }
}

@Composable
private fun BrandLoading() {
    Column(Modifier.fillMaxSize().padding(64.dp), verticalArrangement = Arrangement.Center) {
        Text("回声台", color = FnColors.Text, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("正在载入", color = FnColors.Muted, fontSize = 24.sp)
    }
}

@Composable
internal fun LoginScreen(
    savedServer: String,
    recentServers: List<String>,
    initialError: AppError?,
    onLogin: suspend (String, Boolean, String, CharArray, Boolean, CharArray) -> Unit,
    loginHistory: List<LoginHistoryEntry> = emptyList(),
    initialSelectedProfileId: String? = null,
    onHistoryLogin: suspend (String, CharArray?, Boolean) -> Unit = { _, accessCode, _ ->
        accessCode?.fill('\u0000')
    },
    onHistoryDelete: suspend (String) -> Unit = {},
    onHistoryClear: suspend () -> Unit = {},
    historyDraft: (String) -> LoginDraft? = { null },
) {
    val selectedDraft = remember(initialSelectedProfileId, loginHistory) {
        initialSelectedProfileId?.let(historyDraft)
    }
    val initialServer = remember(savedServer, selectedDraft) {
        selectedDraft?.let { ServerUrlNormalizer.editableInput(it.server, it.useHttps) }
            ?: ServerUrlNormalizer.editableInput(savedServer, false)
    }
    var server by remember(initialServer, selectedDraft?.profileId) { mutableStateOf(initialServer.address) }
    var username by remember(selectedDraft?.profileId) { mutableStateOf(selectedDraft?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var accessCode by remember(selectedDraft?.profileId) { mutableStateOf(selectedDraft?.accessCode.orEmpty()) }
    var rememberLogin by remember { mutableStateOf(true) }
    var https by remember(initialServer) { mutableStateOf(initialServer.useHttps) }
    var selectedProfileId by remember(initialSelectedProfileId) { mutableStateOf(initialSelectedProfileId) }
    var hasSavedPassword by remember(selectedDraft?.profileId) {
        mutableStateOf(selectedDraft?.hasSavedPassword == true)
    }
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
    val accessCodeFocus = remember { FocusRequester() }
    val rememberFocus = remember { FocusRequester() }
    val httpsFocus = remember { FocusRequester() }
    val loginFocus = remember { FocusRequester() }
    val fnIdInput = ConnectionResolver.isFnId(server)
    val validServer = fnIdInput || ServerUrlNormalizer.normalize(server, https) is ServerUrlResult.Valid
    val canSubmit = !submitting && validServer && username.isNotBlank() &&
        (password.isNotBlank() || (hasSavedPassword && selectedProfileId != null))
    LaunchedEffect(Unit) { if (savedServer.isBlank()) serverFocus.requestFocus() else usernameFocus.requestFocus() }

    Box(
        Modifier.fillMaxSize().background(FnColors.Background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth(0.86f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp)
                .semantics { contentDescription = "登录表单" },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("回声台", color = FnColors.Teal, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("登录", color = FnColors.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
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
                        selectedProfileId = null
                        hasSavedPassword = false
                    },
                    label = "NAS 地址或 FNID",
                    modifier = Modifier.weight(1f),
                    downFocus = usernameFocus,
                    rightFocus = historyFocus.takeIf { loginHistory.isNotEmpty() || recentServers.isNotEmpty() },
                    inputModifier = Modifier.focusRequester(serverFocus).focusProperties {
                        right = if (loginHistory.isNotEmpty() || recentServers.isNotEmpty()) {
                            historyFocus
                        } else {
                            FocusRequester.Cancel
                        }
                        down = usernameFocus
                    },
                )
                LoginActionButton(
                    enabled = loginHistory.isNotEmpty() || recentServers.isNotEmpty(),
                    onClick = { showServerHistory = true },
                    modifier = Modifier.size(52.dp)
                        .semantics { contentDescription = "历史" }
                        .focusProperties {
                            left = serverFocus
                            down = usernameFocus
                        }
                        .focusRequester(historyFocus),
                ) {
                    HistoryIcon(enabled = loginHistory.isNotEmpty() || recentServers.isNotEmpty())
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        selectedProfileId = null
                        hasSavedPassword = false
                    },
                    label = "账号",
                    modifier = Modifier.weight(1f),
                    upFocus = serverFocus,
                    downFocus = passwordFocus,
                    rightFocus = accessCodeFocus,
                    inputModifier = Modifier.focusRequester(usernameFocus).focusProperties {
                        up = serverFocus
                        right = accessCodeFocus
                        down = passwordFocus
                    },
                )
                TvTextField(
                    value = accessCode,
                    onValueChange = { accessCode = it },
                    label = "安全码（未启用可留空）",
                    modifier = Modifier.weight(1f),
                    upFocus = serverFocus,
                    downFocus = passwordFocus,
                    leftFocus = usernameFocus,
                    inputModifier = Modifier.focusRequester(accessCodeFocus).focusProperties {
                        up = serverFocus
                        left = usernameFocus
                        down = passwordFocus
                    },
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                TvTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        hasSavedPassword = false
                    },
                    label = "密码",
                    placeholder = "已保存密码".takeIf { hasSavedPassword },
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
                LoginActionButton(
                    onClick = { passwordVisible = !passwordVisible },
                    modifier = Modifier.size(52.dp)
                        .semantics { contentDescription = "显示或隐藏密码" }
                        .focusProperties {
                            up = usernameFocus
                            left = passwordFocus
                            down = rememberFocus
                        }
                        .focusRequester(visibilityFocus),
                ) {
                    VisibilityIcon(hidden = passwordVisible)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LoginCheckbox(
                    label = "保持登录",
                    selected = rememberLogin,
                    modifier = Modifier.weight(1f)
                        .focusProperties {
                            up = passwordFocus
                            right = httpsFocus
                            down = httpsFocus
                        }
                        .focusRequester(rememberFocus),
                ) { rememberLogin = !rememberLogin }
                LoginCheckbox(
                    label = "HTTPS",
                    selected = https,
                    modifier = Modifier.weight(1f)
                        .focusProperties {
                            up = rememberFocus
                            left = rememberFocus
                            down = if (canSubmit) loginFocus else FocusRequester.Cancel
                        }
                        .focusRequester(httpsFocus),
                ) {
                    https = !https
                    selectedProfileId = null
                    hasSavedPassword = false
                }
            }
            val statusMessage = error?.let {
                if (loginAttempted && it == AppError.Unauthenticated) "账号或密码错误" else errorMessage(it)
            } ?: when {
                fnIdInput -> "FNID 将自动选择可用连接"
                !https -> "局域网 HTTP 连接未加密"
                else -> ""
            }
            Text(
                statusMessage,
                color = if (error == null) FnColors.Warning else FnColors.Coral,
                fontSize = 19.sp,
                maxLines = 1,
            )
            LoginActionButton(
                enabled = canSubmit,
                onClick = {
                    if (submitting) return@LoginActionButton
                    submitting = true
                    loginAttempted = true
                    error = null
                    val submittedPassword = password.toCharArray()
                    val submittedAccessCode = accessCode.toCharArray()
                    val savedProfileId = selectedProfileId.takeIf { hasSavedPassword && password.isBlank() }
                    password = ""
                    accessCode = ""
                    scope.launch {
                        runCatching {
                            if (savedProfileId != null) {
                                submittedPassword.fill('\u0000')
                                onHistoryLogin(savedProfileId, submittedAccessCode, rememberLogin)
                            } else {
                                onLogin(server, https, username, submittedPassword, rememberLogin, submittedAccessCode)
                            }
                        }
                            .onFailure { error = (it as? AppException)?.error ?: AppError.Unknown() }
                        submitting = false
                    }
                },
                modifier = Modifier.fillMaxWidth().height(54.dp)
                    .semantics { contentDescription = "登录" }
                    .focusProperties { up = httpsFocus }
                    .focusRequester(loginFocus),
            ) {
                Text(
                    if (submitting) "正在登录" else "登录",
                    modifier = Modifier.fillMaxWidth(),
                    color = if (canSubmit) FnColors.Text else FnColors.Muted,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    if (showServerHistory) {
        val profileIds = loginHistory.map(LoginHistoryEntry::id)
        val profileRowFocus = remember(profileIds) { List(loginHistory.size) { FocusRequester() } }
        val profileDeleteFocus = remember(profileIds) { List(loginHistory.size) { FocusRequester() } }
        val legacyRowFocus = remember(recentServers) { List(recentServers.size) { FocusRequester() } }
        val clearHistoryFocus = remember { FocusRequester() }
        val firstHistoryFocus = profileRowFocus.firstOrNull() ?: legacyRowFocus.firstOrNull()
        Dialog(onDismissRequest = { showServerHistory = false }) {
            Column(
                Modifier.width(600.dp).background(FnColors.Surface, RoundedCornerShape(8.dp)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("登录历史", fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
                loginHistory.forEachIndexed { index, entry ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LoginActionButton(
                            onClick = {
                                val draft = historyDraft(entry.id) ?: return@LoginActionButton
                                server = draft.server
                                https = draft.useHttps
                                username = draft.username
                                accessCode = draft.accessCode
                                password = ""
                                selectedProfileId = draft.profileId
                                hasSavedPassword = draft.hasSavedPassword
                                showServerHistory = false
                                submitting = true
                                loginAttempted = true
                                error = null
                                scope.launch {
                                    runCatching {
                                        onHistoryLogin(
                                            draft.profileId,
                                            draft.accessCode.toCharArray(),
                                            rememberLogin,
                                        )
                                    }.onFailure {
                                        error = (it as? AppException)?.error ?: AppError.Unknown()
                                    }
                                    submitting = false
                                }
                            },
                            modifier = Modifier.weight(1f).height(72.dp)
                                .semantics { contentDescription = "登录历史：${entry.username}" }
                                .focusProperties {
                                    right = profileDeleteFocus[index]
                                    up = profileRowFocus.getOrNull(index - 1) ?: FocusRequester.Cancel
                                    down = profileRowFocus.getOrNull(index + 1) ?: clearHistoryFocus
                                }
                                .focusRequester(profileRowFocus[index]),
                            shape = RoundedCornerShape(6.dp),
                            selected = entry.id == selectedProfileId,
                        ) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                Text(
                                    entry.username,
                                    color = FnColors.Text,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${entry.server} (${if (entry.useHttps) "HTTPS" else "HTTP"})",
                                    color = FnColors.Muted,
                                    fontSize = 16.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        LoginActionButton(
                            onClick = { scope.launch { onHistoryDelete(entry.id) } },
                            modifier = Modifier.size(72.dp)
                                .semantics { contentDescription = "删除历史：${entry.username}" }
                                .focusProperties {
                                    left = profileRowFocus[index]
                                    up = profileDeleteFocus.getOrNull(index - 1) ?: FocusRequester.Cancel
                                    down = profileDeleteFocus.getOrNull(index + 1) ?: clearHistoryFocus
                                }
                                .focusRequester(profileDeleteFocus[index]),
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            DeleteHistoryIcon()
                        }
                    }
                }
                if (loginHistory.isEmpty()) {
                    recentServers.forEachIndexed { index, recent ->
                        val editable = ServerUrlNormalizer.editableInput(recent, https)
                        LoginActionButton(
                            onClick = {
                                server = editable.address
                                https = editable.useHttps
                                selectedProfileId = null
                                hasSavedPassword = false
                                showServerHistory = false
                            },
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                                .focusProperties {
                                    up = legacyRowFocus.getOrNull(index - 1) ?: FocusRequester.Cancel
                                    down = legacyRowFocus.getOrNull(index + 1) ?: clearHistoryFocus
                                }
                                .focusRequester(legacyRowFocus[index]),
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(editable.address, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                LoginActionButton(
                    onClick = {
                        scope.launch {
                            onHistoryClear()
                            showServerHistory = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                        .focusProperties {
                            up = profileRowFocus.lastOrNull()
                                ?: legacyRowFocus.lastOrNull()
                                ?: FocusRequester.Cancel
                        }
                        .focusRequester(clearHistoryFocus),
                    shape = RoundedCornerShape(6.dp),
                ) { Text("清除所有历史记录", color = FnColors.Coral, fontSize = 19.sp) }
            }
        }
        LaunchedEffect(firstHistoryFocus) { firstHistoryFocus?.requestFocus() }
    }
}

@Composable
private fun HistoryIcon(enabled: Boolean) {
    Canvas(Modifier.size(25.dp)) {
        val iconColor = if (enabled) FnColors.Text else FnColors.Muted
        val stroke = 2.2.dp.toPx()
        val center = this.center
        val radius = size.minDimension * 0.34f
        drawArc(
            color = iconColor,
            startAngle = 35f,
            sweepAngle = 285f,
            useCenter = false,
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
        drawLine(
            color = iconColor,
            start = androidx.compose.ui.geometry.Offset(center.x - radius, center.y - radius * 0.2f),
            end = androidx.compose.ui.geometry.Offset(center.x - radius * 1.05f, center.y - radius * 0.9f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = iconColor,
            start = center,
            end = androidx.compose.ui.geometry.Offset(center.x, center.y - radius * 0.58f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = iconColor,
            start = center,
            end = androidx.compose.ui.geometry.Offset(center.x + radius * 0.48f, center.y),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun DeleteHistoryIcon() {
    Canvas(Modifier.size(24.dp)) {
        val stroke = 2.4.dp.toPx()
        drawLine(
            FnColors.Coral,
            start = androidx.compose.ui.geometry.Offset(size.width * 0.22f, size.height * 0.22f),
            end = androidx.compose.ui.geometry.Offset(size.width * 0.78f, size.height * 0.78f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            FnColors.Coral,
            start = androidx.compose.ui.geometry.Offset(size.width * 0.78f, size.height * 0.22f),
            end = androidx.compose.ui.geometry.Offset(size.width * 0.22f, size.height * 0.78f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun VisibilityIcon(hidden: Boolean) {
    Canvas(Modifier.size(26.dp)) {
        val stroke = 2.1.dp.toPx()
        val eye = Path().apply {
            moveTo(size.width * 0.08f, size.height * 0.5f)
            cubicTo(
                size.width * 0.27f, size.height * 0.18f,
                size.width * 0.73f, size.height * 0.18f,
                size.width * 0.92f, size.height * 0.5f,
            )
            cubicTo(
                size.width * 0.73f, size.height * 0.82f,
                size.width * 0.27f, size.height * 0.82f,
                size.width * 0.08f, size.height * 0.5f,
            )
        }
        drawPath(eye, FnColors.Text, style = Stroke(stroke, cap = StrokeCap.Round))
        drawCircle(FnColors.Text, radius = size.minDimension * 0.12f, center = center, style = Stroke(stroke))
        if (hidden) {
            drawLine(
                color = FnColors.Coral,
                start = androidx.compose.ui.geometry.Offset(size.width * 0.14f, size.height * 0.14f),
                end = androidx.compose.ui.geometry.Offset(size.width * 0.86f, size.height * 0.86f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
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
            if (editing) {
                val target = when (event.key) {
                    Key.DirectionUp -> upFocus
                    Key.DirectionDown -> downFocus
                    Key.DirectionLeft -> leftFocus
                    Key.DirectionRight -> rightFocus
                    else -> null
                }
                if (!imeVisible && event.type == KeyEventType.KeyDown && target != null) {
                    finishEditing(target)
                    return@onPreviewKeyEvent true
                }
                return@onPreviewKeyEvent false
            }
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
            modifier = inputModifier.fillMaxWidth().height(52.dp)
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
                .border(if (focused) 3.dp else 1.dp, if (focused) FnColors.Coral else Color(0xFF454A50), RoundedCornerShape(6.dp)),
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
            decorationBox = { innerTextField ->
                Box(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(placeholder, color = FnColors.Muted, fontSize = 22.sp)
                    }
                    innerTextField()
                }
            },
        )
    }
}

@Composable
private fun LoginCheckbox(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier.height(54.dp)
            .onFocusChanged { focused = it.isFocused }
            .background(FnColors.Surface, RoundedCornerShape(6.dp))
            .border(
                if (focused) 3.dp else 1.dp,
                if (focused) FnColors.Coral else Color(0xFF454A50),
                RoundedCornerShape(6.dp),
            )
            .focusable()
            .toggleable(
                value = selected,
                role = Role.Checkbox,
                onValueChange = { onClick() },
            )
            .semantics { contentDescription = label }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(28.dp)
                .background(if (selected) FnColors.Warning else Color.Transparent, RoundedCornerShape(3.dp))
                .border(2.dp, if (selected) FnColors.Warning else FnColors.Muted, RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Text("✓", color = Color(0xFF17201E), fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
        Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun LoginActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(50),
    selected: Boolean = false,
    content: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var centerKeyDown by remember { mutableStateOf(false) }
    Box(
        modifier
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) centerKeyDown = false
            }
            .background(
                when {
                    !enabled -> Color(0xFF292A2F)
                    focused -> Color(0xFF4A464F)
                    selected -> FnColors.FocusFill
                    else -> Color(0xFF414047)
                },
                shape,
            )
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) FnColors.Coral else Color(0xFF454A50),
                shape = shape,
            )
            .onPreviewKeyEvent { event ->
                if (!enabled || (event.key != Key.Enter && event.key != Key.DirectionCenter)) {
                    return@onPreviewKeyEvent false
                }
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        centerKeyDown = true
                        true
                    }
                    KeyEventType.KeyUp -> {
                        if (centerKeyDown) onClick()
                        centerKeyDown = false
                        true
                    }
                    else -> false
                }
            }
            .focusable(enabled)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

private fun errorMessage(error: AppError): String = when (error) {
    AppError.Unauthenticated -> "登录已失效，请重新登录"
    AppError.AccountDisabled -> "账号已禁用"
    AppError.AccessCodeRequired -> "此服务器需要安全码"
    AppError.InvalidAccessCode -> "安全码错误"
    AppError.NetworkUnavailable -> "无法连接 NAS，请检查地址和网络"
    AppError.NotFound -> "服务器接口不可用"
    AppError.FnIdUnavailable -> "FNID 无可用连接，请检查输入或网络"
    else -> "连接失败，请重试"
}
