package com.conveyorg

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.conveyorg.data.ChatSessionMetadata
import com.conveyorg.network.ChatRole
import com.conveyorg.network.GroundingSource
import com.conveyorg.network.TextAttachment
import com.conveyorg.network.ThinkingLevel
import com.conveyorg.presentation.*
import kotlinx.coroutines.launch
import java.util.Locale

// ====================================================================
// Палитра True Dark AMOLED (WCAG 2.1 AA)
// ====================================================================

private val AmoledCardBg = Color(0xFF101010)
private val AmoledSurface = Color(0xFF171717)
private val AmoledInputBarBg = Color(0xFF1E1E1E)
private val AmoledButtonBg = Color(0xFF282828)
private val TextPrimary = Color(0xFFF2F2F2)
private val TextSecondary = Color(0xFFB0B0B5)
private val TextMuted = Color(0xFF9E9E9E)

private val ThinkingGlowStart = Color(0xFF4C8DFF)
private val ThinkingGlowEnd = Color(0xFF6C5CE7)
private val ThinkingCardBg = Color(0xFF0E1218)
private val ThinkingBorder = Color(0xFF1A2230)

private val SearchActiveBg = Color(0xFF0F1E2E)
private val SearchActiveBorder = Color(0xFF183B5E)
private val SearchActiveText = Color(0xFF8AB4F8)

private val BubbleUserShape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
private val CardStandardShape = RoundedCornerShape(14.dp)
private val ChipStandardShape = RoundedCornerShape(12.dp)
private val InputBarCapsuleShape = RoundedCornerShape(26.dp)

private val DefaultTextStyle = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false)
)

private val ArrowUpwardIcon: ImageVector by lazy {
    ImageVector.Builder("ArrowUp", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(4f, 12f)
            lineToRelative(1.41f, 1.41f)
            lineTo(11f, 7.83f)
            verticalLineTo(20f)
            horizontalLineToRelative(2f)
            verticalLineTo(7.83f)
            lineToRelative(5.58f, 5.59f)
            lineTo(20f, 12f)
            lineToRelative(-8f, -8f)
            close()
        }
    }.build()
}

private val StopSquareIcon: ImageVector by lazy {
    ImageVector.Builder("StopSquare", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(6f, 6f)
            horizontalLineToRelative(12f)
            verticalLineToRelative(12f)
            horizontalLineTo(6f)
            close()
        }
    }.build()
}

private val GlobeIcon: ImageVector by lazy {
    ImageVector.Builder("Globe", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(12f, 2f)
            curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
            curveToRelative(0f, 5.52f, 4.48f, 10f, 10f, 10f)
            curveToRelative(5.52f, 0f, 10f, -4.48f, 10f, -10f)
            curveTo(22f, 6.48f, 17.52f, 2f, 12f, 2f)
            close()
            moveTo(11f, 19.93f)
            curveTo(7.05f, 19.44f, 4f, 16.08f, 4f, 12f)
            curveToRelative(0f, -0.94f, 0.16f, -1.84f, 0.45f, -2.68f)
            lineToRelative(4.55f, 4.55f)
            verticalLineToRelative(1.06f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            verticalLineToRelative(2.93f)
            close()
        }
    }.build()
}

private val KeyIcon: ImageVector by lazy {
    ImageVector.Builder("Key", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(7f, 11f)
            curveToRelative(-1.66f, 0f, -3f, 1.34f, -3f, 3f)
            curveToRelative(0f, 1.31f, 0.84f, 2.41f, 2f, 2.83f)
            verticalLineTo(21f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(1.17f)
            curveToRelative(1.16f, -0.42f, 2f, -1.52f, 2f, -2.83f)
            curveToRelative(0f, -1.66f, -1.34f, -3f, -3f, -3f)
            horizontalLineTo(7f)
            close()
        }
    }.build()
}

private val CopyIcon: ImageVector by lazy {
    ImageVector.Builder("Copy", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(16f, 1f)
            horizontalLineTo(4f)
            curveTo(2.9f, 1f, 2f, 1.9f, 2f, 3f)
            verticalLineToRelative(14f)
            horizontalLineToRelative(2f)
            verticalLineTo(3f)
            horizontalLineToRelative(12f)
            verticalLineTo(1f)
            close()
            moveTo(19f, 5f)
            horizontalLineTo(8f)
            curveTo(6.9f, 5f, 6f, 5.9f, 6f, 7f)
            verticalLineToRelative(14f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            horizontalLineToRelative(11f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineTo(7f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
            close()
            moveTo(19f, 21f)
            horizontalLineTo(8f)
            verticalLineTo(7f)
            horizontalLineToRelative(11f)
            verticalLineToRelative(14f)
            close()
        }
    }.build()
}

// ====================================================================
// Главный экран: ChatGptScreen со шторкой истории
// ====================================================================

@Composable
fun ChatGptScreen(
    viewModel: ChatViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var showClearChatDialog by remember { mutableStateOf(false) }

    val currentView = LocalView.current
    DisposableEffect(uiState.isGenerating) {
        currentView.keepScreenOn = uiState.isGenerating
        onDispose {
            currentView.keepScreenOn = false
        }
    }

    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val total = layoutInfo.totalItemsCount
            if (total == 0) return@derivedStateOf true
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= total - 1
        }
    }

    val lastMessage = uiState.messages.lastOrNull()
    val textBucket = (lastMessage?.text?.length ?: 0) / 32
    val thoughtBucket = (lastMessage?.thoughtText?.length ?: 0) / 48

    LaunchedEffect(textBucket, thoughtBucket) {
        if (lastMessage?.isStreaming == true && isAtBottom && uiState.messages.isNotEmpty()) {
            listState.scrollToItem(uiState.messages.lastIndex)
        }
    }

    BackHandler(enabled = drawerState.isOpen || uiState.isApiKeyDialogOpen || uiState.isGenerating || showClearChatDialog) {
        if (drawerState.isOpen) {
            coroutineScope.launch { drawerState.close() }
        } else if (showClearChatDialog) {
            showClearChatDialog = false
        } else if (uiState.isApiKeyDialogOpen) {
            viewModel.onCloseApiKeyDialog()
        } else if (uiState.isGenerating) {
            viewModel.onCancelGeneration()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.uiEffects.collect { effect ->
            when (effect) {
                is ChatUiSideEffect.ScrollToBottom -> {
                    if (uiState.messages.isNotEmpty()) {
                        listState.animateScrollToItem(uiState.messages.lastIndex)
                    }
                }
                is ChatUiSideEffect.HapticLightTick -> {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                is ChatUiSideEffect.HapticThinkingCompleted -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                is ChatUiSideEffect.HapticGenerationFinished -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                is ChatUiSideEffect.ShowToast -> {
                    android.widget.Toast.makeText(context, effect.message, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.onAttachFileUri(it) }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        scrimColor = Color.Black.copy(alpha = 0.65f),
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color(0xFF101010),
                drawerContentColor = TextPrimary,
                modifier = Modifier
                    .width(310.dp)
                    .fillMaxHeight()
                    .border(1.dp, Color(0xFF222222), RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp))
            ) {
                ChatHistoryDrawerContent(
                    activeSessionId = uiState.currentSessionId,
                    sessions = uiState.sessionList,
                    onNewChatClick = {
                        viewModel.onNewSession()
                        coroutineScope.launch { drawerState.close() }
                    },
                    onSelectSession = { id ->
                        viewModel.onSelectSession(id)
                        coroutineScope.launch { drawerState.close() }
                    },
                    onDeleteSession = { id ->
                        viewModel.onDeleteSession(id)
                    }
                )
            }
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                ChatTopBar(
                    currentLevel = uiState.thinkingLevel,
                    hasPinnedCache = uiState.pinnedCacheId != null,
                    onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                    onClearChat = { showClearChatDialog = true },
                    onOpenApiKeyDialog = { viewModel.onOpenApiKeyDialog() },
                    onThinkingLevelSelected = { viewModel.onThinkingLevelChanged(it) }
                )

                // Баннер фиксации явного кэша токенов в памяти TPU Google
                AnimatedVisibility(
                    visible = uiState.isPinningCache,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        color = Color(0xFF0F1E2E),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF183B5E))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = SearchActiveText
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Фиксация контекста в Google TPU (скидка 90% на повтор)...",
                                color = SearchActiveText,
                                fontSize = 12.sp,
                                style = DefaultTextStyle
                            )
                        }
                    }
                }

                if (uiState.errorMessage != null) {
                    ErrorBanner(
                        message = uiState.errorMessage ?: "",
                        retrySeconds = uiState.retryCountdownSeconds,
                        onRetry = { viewModel.onRetryLastMessage() },
                        onOpenKeyDialog = { viewModel.onOpenApiKeyDialog() },
                        onDismiss = { viewModel.onDismissError() }
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (uiState.messages.isEmpty()) {
                        EmptyStateHero(
                            onSuggestionClick = { prompt ->
                                viewModel.onInputTextChanged(prompt)
                            }
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            items(
                                items = uiState.messages,
                                key = { it.id }
                            ) { message ->
                                ChatMessageItem(
                                    message = message,
                                    onToggleThinking = { viewModel.onToggleThinkingAccordion(message.id) },
                                    onOpenUrl = { url ->
                                        runCatching {
                                            val uri = Uri.parse(url)
                                            val scheme = uri.scheme?.lowercase(Locale.US)
                                            if (scheme == "https" || scheme == "http") {
                                                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                                    if (context !is Activity) {
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                }
                                                context.startActivity(intent)
                                            }
                                        }
                                    },
                                    onRegenerate = { viewModel.onRetryMessage(message.id) },
                                    onShareText = { textToShare ->
                                        val sendIntent = Intent().apply {
                                            action = Intent.ACTION_SEND
                                            putExtra(Intent.EXTRA_TEXT, textToShare)
                                            type = "text/plain"
                                        }
                                        val chooser = Intent.createChooser(sendIntent, "Поделиться").apply {
                                            if (context !is Activity) {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                        }
                                        context.startActivity(chooser)
                                    }
                                )
                            }
                        }

                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            AnimatedVisibility(
                                visible = !isAtBottom && uiState.messages.isNotEmpty(),
                                enter = scaleIn(animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f)) + fadeIn(),
                                exit = scaleOut() + fadeOut()
                            ) {
                                FloatingScrollBottomButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            if (uiState.messages.isNotEmpty()) {
                                                listState.animateScrollToItem(uiState.messages.lastIndex)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                if (uiState.attachedFiles.isNotEmpty()) {
                    AttachmentChipsBar(
                        attachments = uiState.attachedFiles,
                        onRemove = { viewModel.onRemoveAttachment(it) }
                    )
                }

                ChatGptInputBar(
                    text = uiState.inputText,
                    isGenerating = uiState.isGenerating,
                    enableSearch = uiState.enableSearch,
                    thinkingLevel = uiState.thinkingLevel,
                    hasAttachments = uiState.attachedFiles.isNotEmpty(),
                    onTextChanged = { viewModel.onInputTextChanged(it) },
                    onToggleSearch = { viewModel.onToggleSearch(!uiState.enableSearch) },
                    onThinkingLevelSelected = { viewModel.onThinkingLevelChanged(it) },
                    onSendMessage = { viewModel.onSendMessage() },
                    onCancelGeneration = { viewModel.onCancelGeneration() },
                    onAttachClick = {
                        filePickerLauncher.launch(
                            arrayOf(
                                "text/*",
                                "application/json",
                                "application/xml",
                                "application/javascript",
                                "application/x-yaml"
                            )
                        )
                    }
                )
            }

            if (uiState.isApiKeyDialogOpen) {
                ApiKeyDialog(
                    currentKey = uiState.apiKey,
                    onSave = { viewModel.onSaveApiKey(it) },
                    onDismiss = { viewModel.onCloseApiKeyDialog() }
                )
            }

            if (showClearChatDialog) {
                AlertDialog(
                    onDismissRequest = { showClearChatDialog = false },
                    containerColor = AmoledSurface,
                    title = { Text("Очистить историю диалога?", color = TextPrimary, fontWeight = FontWeight.Bold) },
                    text = { Text("Все сообщения текущей сессии будут удалены. Зафиксированный кэш в Google TPU будет освобожден.", color = TextSecondary) },
                    confirmButton = {
                        Button(
                            onClick = {
                                viewModel.onClearChat()
                                showClearChatDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCF6679))
                        ) {
                            Text("Очистить", color = Color.White)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearChatDialog = false }) {
                            Text("Отмена", color = TextSecondary)
                        }
                    }
                )
            }
        }
    }
}

// ====================================================================
// Содержимое боковой шторки истории (Material 3 Drawer)
// ====================================================================

@Composable
private fun ChatHistoryDrawerContent(
    activeSessionId: String,
    sessions: List<ChatSessionMetadata>,
    onNewChatClick: () -> Unit,
    onSelectSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit
) {
    val drawerScrollState = rememberLazyListState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 18.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF1E1E1E),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2E2E2E)),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNewChatClick() }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Add, contentDescription = "Новый диалог", tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Новый диалог",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    style = DefaultTextStyle
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "ИСТОРИЯ СЕССИЙ",
            color = TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            style = DefaultTextStyle,
            modifier = Modifier.padding(start = 6.dp, bottom = 10.dp)
        )

        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text("История диалогов пуста", color = TextMuted, fontSize = 13.sp, style = DefaultTextStyle)
            }
        } else {
            LazyColumn(
                state = drawerScrollState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sessions, key = { it.id }) { session ->
                    val isSelected = session.id == activeSessionId
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) Color(0xFF16202E) else Color.Transparent,
                        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF24364D)) else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectSession(session.id) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = session.title,
                                        color = if (isSelected) SearchActiveText else TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = DefaultTextStyle,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (session.pinnedCacheId != null) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "TPU",
                                            color = Color(0xFF81C784),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            style = DefaultTextStyle
                                        )
                                    }
                                }

                                if (session.previewSnippet.isNotBlank()) {
                                    Text(
                                        text = session.previewSnippet,
                                        color = TextSecondary,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = DefaultTextStyle,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }

                            IconButton(
                                onClick = { onDeleteSession(session.id) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Удалить сессию",
                                    tint = Color(0xFF6E6E6E),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ====================================================================
// Стартовый экран (Empty State Hero)
// ====================================================================

@Composable
private fun EmptyStateHero(
    onSuggestionClick: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color(0xFF1E2A38), Color(0xFF0D1217))
                        )
                    )
                    .border(1.dp, Color(0xFF283A4E), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✦",
                    color = SearchActiveText,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Чем я могу помочь?",
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                style = DefaultTextStyle
            )

            Text(
                text = "Gemini 3.8 Flash • Deep Reasoning • Grounding",
                color = TextSecondary,
                fontSize = 13.sp,
                style = DefaultTextStyle,
                modifier = Modifier.padding(top = 6.dp, bottom = 32.dp)
            )

            val suggestions = listOf(
                "Свежие новости о релизе Android 16",
                "Архитектура корутин без дропов кадров на 120 Гц",
                "Проанализируй прикрепленный файл логов"
            )

            suggestions.forEach { prompt ->
                Surface(
                    shape = CardStandardShape,
                    color = AmoledSurface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF222222)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onSuggestionClick(prompt) }
                ) {
                    Text(
                        text = prompt,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        style = DefaultTextStyle,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                    )
                }
            }
        }
    }
}

// ====================================================================
// Верхняя панель (Top Bar) с кнопкой Drawer и индикатором TPU Cache
// ====================================================================

@Composable
private fun ChatTopBar(
    currentLevel: ThinkingLevel,
    hasPinnedCache: Boolean,
    onOpenDrawer: () -> Unit,
    onClearChat: () -> Unit,
    onOpenApiKeyDialog: () -> Unit,
    onThinkingLevelSelected: (ThinkingLevel) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onOpenDrawer,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(AmoledSurface)
            ) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "История диалогов",
                    tint = TextPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "ClientG",
                        color = TextPrimary,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-0.5).sp,
                        style = DefaultTextStyle
                    )
                    Spacer(modifier = Modifier.width(6.dp))

                    Box {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF16202E),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF24364D)),
                            modifier = Modifier.clickable { menuExpanded = true }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "3.8 FLASH • ${currentLevel.name}",
                                    color = SearchActiveText,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    style = DefaultTextStyle
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Уровень рассуждений",
                                    tint = SearchActiveText,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            modifier = Modifier
                                .background(AmoledSurface)
                                .border(1.dp, Color(0xFF2D2D2D), RoundedCornerShape(12.dp))
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = if (currentLevel == ThinkingLevel.HIGH) "● " else "○ ",
                                            color = if (currentLevel == ThinkingLevel.HIGH) SearchActiveText else TextMuted
                                        )
                                        Column {
                                            Text("HIGH", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                            Text("Глубокий анализ (максимум рассуждений)", color = TextSecondary, fontSize = 11.sp)
                                        }
                                    }
                                },
                                onClick = {
                                    onThinkingLevelSelected(ThinkingLevel.HIGH)
                                    menuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = if (currentLevel == ThinkingLevel.MEDIUM) "● " else "○ ",
                                            color = if (currentLevel == ThinkingLevel.MEDIUM) SearchActiveText else TextMuted
                                        )
                                        Column {
                                            Text("MEDIUM (Рекомендуется)", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                            Text("Сбалансированное рассуждение", color = TextSecondary, fontSize = 11.sp)
                                        }
                                    }
                                },
                                onClick = {
                                    onThinkingLevelSelected(ThinkingLevel.MEDIUM)
                                    menuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = if (currentLevel == ThinkingLevel.LOW) "● " else "○ ",
                                            color = if (currentLevel == ThinkingLevel.LOW) SearchActiveText else TextMuted
                                        )
                                        Column {
                                            Text("LOW", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                            Text("Быстрый ответ (минимум задержки)", color = TextSecondary, fontSize = 11.sp)
                                        }
                                    }
                                },
                                onClick = {
                                    onThinkingLevelSelected(ThinkingLevel.LOW)
                                    menuExpanded = false
                                }
                            )
                        }
                    }

                    if (hasPinnedCache) {
                        Spacer(modifier = Modifier.width(5.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF132A1C),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF26593A))
                        ) {
                            Text(
                                text = "⚡ TPU CACHE",
                                color = Color(0xFF81C784),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onClearChat,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(AmoledSurface)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Очистить диалог",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = onOpenApiKeyDialog,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(AmoledSurface)
            ) {
                Icon(
                    imageVector = KeyIcon,
                    contentDescription = "Настройки API ключа",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ====================================================================
// Баннер ошибок и квот
// ====================================================================

@Composable
private fun ErrorBanner(
    message: String,
    retrySeconds: Long?,
    onRetry: () -> Unit,
    onOpenKeyDialog: () -> Unit,
    onDismiss: () -> Unit
) {
    val isKeyEmpty = message.contains("API-ключ", ignoreCase = true)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF261212)),
        shape = CardStandardShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF4A2020))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message,
                    color = Color(0xFFFFB4A9),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    style = DefaultTextStyle
                )
                if (retrySeconds != null) {
                    Text(
                        text = "Повтор доступен через $retrySeconds сек...",
                        color = Color(0xFFFFDAD4),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        style = DefaultTextStyle,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            if (isKeyEmpty) {
                TextButton(onClick = onOpenKeyDialog) {
                    Text("Ввести ключ", color = Color(0xFFFF897D), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            } else if (retrySeconds == null || retrySeconds <= 0) {
                TextButton(onClick = onRetry) {
                    Text("Повторить", color = Color(0xFFFF897D), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color(0xFFFFB4A9), modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ====================================================================
// Сообщение чата
// ====================================================================

@Composable
private fun ChatMessageItem(
    message: UiChatMessage,
    onToggleThinking: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onRegenerate: () -> Unit,
    onShareText: (String) -> Unit
) {
    val isUser = message.role == ChatRole.USER
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    var isCopied by remember { mutableStateOf(false) }

    LaunchedEffect(isCopied) {
        if (isCopied) {
            kotlinx.coroutines.delay(1500)
            isCopied = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (isUser) {
            if (message.attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    message.attachments.forEach { att ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = AmoledSurface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF282828))
                        ) {
                            Text(
                                text = "📄 ${att.fileName}",
                                color = TextSecondary,
                                fontSize = 11.sp,
                                style = DefaultTextStyle,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            if (message.text.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 310.dp)
                        .clip(BubbleUserShape)
                        .background(Color(0xFF242424))
                        .border(1.dp, Color(0xFF333333), BubbleUserShape)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    SelectionContainer {
                        Text(
                            text = message.text,
                            color = TextPrimary,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            style = DefaultTextStyle
                        )
                    }
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (message.hasThoughts || message.thoughtText.isNotEmpty() || message.isThinkingActive) {
                    ThinkingAccordionCard(
                        thoughtText = message.thoughtText,
                        durationMs = message.thinkingDurationMs,
                        isActive = message.isThinkingActive,
                        isExpanded = message.isThinkingExpanded,
                        onToggle = onToggleThinking
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }

                if (message.searchQueries.isNotEmpty() || message.sources.isNotEmpty() || !message.searchSuggestionsHtml.isNullOrBlank()) {
                    SearchGroundingBlock(
                        queries = message.searchQueries,
                        sources = message.sources,
                        searchSuggestions = message.searchSuggestionsHtml,
                        onOpenUrl = onOpenUrl
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }

                if (message.text.isNotEmpty()) {
                    if (message.isStreaming) {
                        StreamingMarkdownContent(
                            text = message.text,
                            citations = message.citations,
                            onOpenUrl = onOpenUrl
                        )
                    } else {
                        SelectionContainer {
                            StreamingMarkdownContent(
                                text = message.text,
                                citations = message.citations,
                                onOpenUrl = onOpenUrl
                            )
                        }
                    }
                }

                if (!message.isStreaming && message.text.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        message.usage?.let { usage ->
                            Text(
                                text = "Вход: ${usage.promptTokens} • Мысли: ${usage.thoughtsTokens} • Выход: ${usage.candidateTokens} • Кэш: ${usage.cacheHitPercentage.toInt()}%",
                                color = TextMuted,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                style = DefaultTextStyle
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(message.text))
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    isCopied = true
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = if (isCopied) Icons.Default.Check else CopyIcon,
                                    contentDescription = "Скопировать ответ",
                                    tint = if (isCopied) Color(0xFF81C784) else TextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            IconButton(
                                onClick = { onShareText(message.text) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Поделиться",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            IconButton(
                                onClick = onRegenerate,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Повторить этот ответ",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ====================================================================
// Thinking Accordion Card
// ====================================================================

@Composable
private fun ThinkingAccordionCard(
    thoughtText: String,
    durationMs: Long,
    isActive: Boolean,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ThinkingPulse")
    val animatedAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "GlowAlpha"
    )
    val glowAlpha = if (isActive) animatedAlpha else 1f

    val gradientBrush = remember(glowAlpha) {
        Brush.horizontalGradient(
            listOf(
                ThinkingGlowStart.copy(alpha = glowAlpha),
                ThinkingGlowEnd.copy(alpha = glowAlpha)
            )
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardStandardShape)
            .then(
                if (isActive) {
                    Modifier.border(width = 1.dp, brush = gradientBrush, shape = CardStandardShape)
                } else {
                    Modifier.border(1.dp, ThinkingBorder, CardStandardShape)
                }
            )
            .clickable(onClick = onToggle, role = Role.Button),
        colors = CardDefaults.cardColors(containerColor = ThinkingCardBg)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .graphicsLayer { alpha = if (isActive) glowAlpha else 1f }
                            .clip(CircleShape)
                            .background(if (isActive) Color(0xFF4C8DFF) else Color(0xFF757575))
                    )
                    Spacer(modifier = Modifier.width(9.dp))
                    Text(
                        text = if (isActive) "Размышляет над задачей..." else "Ход мыслей (${String.format(Locale.US, "%.1f", durationMs / 1000f)}с)",
                        color = if (isActive) Color(0xFF8AB4F8) else TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        style = DefaultTextStyle
                    )
                }

                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Свернуть" else "Развернуть",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
            ) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    HorizontalDivider(color = ThinkingBorder, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(8.dp))

                    if (isActive) {
                        Text(
                            text = thoughtText.ifEmpty { "Анализ контекста и планирование решения..." },
                            color = Color(0xFFB0B0B0),
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            fontFamily = FontFamily.Monospace,
                            style = DefaultTextStyle
                        )
                    } else {
                        SelectionContainer {
                            Text(
                                text = thoughtText.ifEmpty { "Анализ контекста и планирование решения..." },
                                color = Color(0xFFB0B0B0),
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                fontFamily = FontFamily.Monospace,
                                style = DefaultTextStyle
                            )
                        }
                    }
                }
            }
        }
    }
}

// ====================================================================
// Google Search Grounding: карточки источников
// ====================================================================

@Composable
private fun SearchGroundingBlock(
    queries: List<String>,
    sources: List<GroundingSource>,
    searchSuggestions: String?,
    onOpenUrl: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (queries.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                queries.forEach { q ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = SearchActiveBg,
                        border = androidx.compose.foundation.BorderStroke(1.dp, SearchActiveBorder)
                    ) {
                        Text(
                            text = "🔍 $q",
                            color = SearchActiveText,
                            fontSize = 11.sp,
                            style = DefaultTextStyle,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        if (sources.isNotEmpty()) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sources.forEachIndexed { index, source ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = AmoledCardBg,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF222222)),
                        modifier = Modifier
                            .clickable { onOpenUrl(source.url) }
                            .widthIn(max = 210.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "[${index + 1}] ${source.title}",
                                color = Color(0xFFE2E2E2),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold,
                                style = DefaultTextStyle
                            )
                            Text(
                                text = source.url.removePrefix("https://").removePrefix("http://").substringBefore('/'),
                                color = TextSecondary,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = DefaultTextStyle
                            )
                        }
                    }
                }
            }
        }

        if (!searchSuggestions.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = AmoledSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF222222)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Поисковые подсказки Google активны",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    style = DefaultTextStyle,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

// ====================================================================
// Панель вложений
// ====================================================================

@Composable
private fun AttachmentChipsBar(
    attachments: List<TextAttachment>,
    onRemove: (TextAttachment) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        attachments.forEach { att ->
            Surface(
                shape = ChipStandardShape,
                color = AmoledSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2E2E2E))
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📄 ${att.fileName}",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        style = DefaultTextStyle
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = { onRemove(att) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Удалить вложение",
                            tint = TextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

// ====================================================================
// Парящая кнопка скролла вниз
// ====================================================================

@Composable
private fun FloatingScrollBottomButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(44.dp),
        shape = CircleShape,
        color = AmoledSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF333333)),
        shadowElevation = 6.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Вниз",
                tint = TextPrimary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

// ====================================================================
// Капсула ввода
// ====================================================================

@Composable
fun ChatGptInputBar(
    text: String,
    isGenerating: Boolean,
    enableSearch: Boolean,
    thinkingLevel: ThinkingLevel,
    hasAttachments: Boolean,
    onTextChanged: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onThinkingLevelSelected: (ThinkingLevel) -> Unit,
    onSendMessage: () -> Unit,
    onCancelGeneration: () -> Unit,
    onAttachClick: () -> Unit
) {
    val isSendEnabled = (text.isNotBlank() || hasAttachments) && !isGenerating
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val inputScrollState = rememberScrollState()
    var thinkingMenuExpanded by remember { mutableStateOf(false) }

    val sendButtonBg by animateColorAsState(
        targetValue = if (isGenerating) Color.White else if (isSendEnabled) Color.White else Color(0xFF333333),
        animationSpec = tween(150),
        label = "btnBg"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .clip(InputBarCapsuleShape)
            .background(AmoledInputBarBg)
            .border(1.dp, Color(0xFF292929), InputBarCapsuleShape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(AmoledButtonBg)
                .clickable(onClick = onAttachClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Прикрепить файл",
                tint = Color(0xFFD1D1D1),
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (enableSearch) SearchActiveBg else AmoledButtonBg)
                .border(
                    width = 1.dp,
                    color = if (enableSearch) SearchActiveBorder else Color.Transparent,
                    shape = CircleShape
                )
                .toggleable(
                    value = enableSearch,
                    role = Role.Switch,
                    onValueChange = { onToggleSearch() }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = GlobeIcon,
                contentDescription = "Веб-поиск",
                tint = if (enableSearch) SearchActiveText else TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        Box {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E1C2B))
                    .border(
                        width = 1.dp,
                        color = Color(0xFF5D4BB3),
                        shape = CircleShape
                    )
                    .clickable { thinkingMenuExpanded = true },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(text = "🧠", fontSize = 11.sp)
                    Text(
                        text = when (thinkingLevel) {
                            ThinkingLevel.HIGH -> "HIGH"
                            ThinkingLevel.MEDIUM -> "MED"
                            ThinkingLevel.LOW -> "LOW"
                        },
                        color = Color(0xFFD3C8FF),
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 8.sp,
                        style = DefaultTextStyle
                    )
                }
            }

            DropdownMenu(
                expanded = thinkingMenuExpanded,
                onDismissRequest = { thinkingMenuExpanded = false },
                modifier = Modifier
                    .background(AmoledSurface)
                    .border(1.dp, Color(0xFF2D2D2D), RoundedCornerShape(12.dp))
            ) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (thinkingLevel == ThinkingLevel.HIGH) "● " else "○ ",
                                color = if (thinkingLevel == ThinkingLevel.HIGH) Color(0xFFD3C8FF) else TextMuted
                            )
                            Column {
                                Text("HIGH", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("Глубокий анализ", color = TextSecondary, fontSize = 11.sp)
                            }
                        }
                    },
                    onClick = {
                        onThinkingLevelSelected(ThinkingLevel.HIGH)
                        thinkingMenuExpanded = false
                    }
                )
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (thinkingLevel == ThinkingLevel.MEDIUM) "● " else "○ ",
                                color = if (thinkingLevel == ThinkingLevel.MEDIUM) Color(0xFFD3C8FF) else TextMuted
                            )
                            Column {
                                Text("MEDIUM (Рекомендуется)", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("Сбалансированное рассуждение", color = TextSecondary, fontSize = 11.sp)
                            }
                        }
                    },
                    onClick = {
                        onThinkingLevelSelected(ThinkingLevel.MEDIUM)
                        thinkingMenuExpanded = false
                    }
                )
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (thinkingLevel == ThinkingLevel.LOW) "● " else "○ ",
                                color = if (thinkingLevel == ThinkingLevel.LOW) Color(0xFFD3C8FF) else TextMuted
                            )
                            Column {
                                Text("LOW", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("Быстрый ответ", color = TextSecondary, fontSize = 11.sp)
                            }
                        }
                    },
                    onClick = {
                        onThinkingLevelSelected(ThinkingLevel.LOW)
                        thinkingMenuExpanded = false
                    }
                )
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 8.dp, top = 4.dp)
                .heightIn(min = 24.dp, max = 120.dp)
                .bringIntoViewRequester(bringIntoViewRequester),
            contentAlignment = Alignment.CenterStart
        ) {
            if (text.isEmpty()) {
                Text(
                    text = "Спросить Gemini 3.8...",
                    color = Color(0xFF757575),
                    fontSize = 15.sp,
                    style = DefaultTextStyle
                )
            }

            BasicTextField(
                value = text,
                onValueChange = onTextChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(inputScrollState)
                    .onFocusEvent { event ->
                        if (event.isFocused) {
                            coroutineScope.launch { bringIntoViewRequester.bringIntoView() }
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        val isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter
                        if (isEnter && event.type == KeyEventType.KeyDown) {
                            if (!event.isShiftPressed && isSendEnabled) {
                                onSendMessage()
                                keyboardController?.hide()
                                true
                            } else false
                        } else false
                    },
                textStyle = TextStyle(
                    color = TextPrimary,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                ),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (isSendEnabled) {
                            onSendMessage()
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        }
                    }
                )
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(sendButtonBg)
                .clickable {
                    if (isGenerating) {
                        onCancelGeneration()
                    } else if (isSendEnabled) {
                        onSendMessage()
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (isGenerating) {
                Icon(
                    imageVector = StopSquareIcon,
                    contentDescription = "Остановить генерацию",
                    tint = Color.Black,
                    modifier = Modifier.size(16.dp)
                )
            } else {
                Icon(
                    imageVector = ArrowUpwardIcon,
                    contentDescription = "Отправить запрос",
                    tint = if (isSendEnabled) Color.Black else Color(0xFF6E6E6E),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// ====================================================================
// Диалог API-ключа
// ====================================================================

@Composable
private fun ApiKeyDialog(
    currentKey: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var key by remember(currentKey) { mutableStateOf(currentKey) }
    var passwordVisible by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val isKeyValid = key.trim().isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AmoledSurface,
        title = {
            Text("Gemini API Key", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(
                    text = "Ключ защищен аппаратным модулем Knox Vault (AES-256) и используется для вызовов модели Gemini 3.8 Flash.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    style = DefaultTextStyle
                )
                Spacer(modifier = Modifier.height(14.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = {
                            passwordVisible = !passwordVisible
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.Check else KeyIcon,
                                contentDescription = if (passwordVisible) "Скрыть ключ" else "Показать ключ",
                                tint = TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = Color(0xFF383838)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (isKeyValid) onSave(key.trim()) },
                enabled = isKeyValid,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, disabledContainerColor = Color(0xFF383838))
            ) {
                Text("Сохранить", color = if (isKeyValid) Color.Black else Color.Gray)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена", color = TextSecondary)
            }
        }
    )
}