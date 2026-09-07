package com.conveyorg.presentation

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.conveyorg.agent.BuilderCardUiModel
import com.conveyorg.agent.BuilderRole
import com.conveyorg.agent.BuilderStatus
import com.conveyorg.agent.OrchestratorPhase
import kotlinx.coroutines.launch

// ====================================================================
// Палитра True Dark AMOLED Terminal (WCAG 2.1 AAA)
// ====================================================================

private val TerminalBg = Color(0xFF08080A)
private val MonolithBlack = Color(0xFF000000)
private val SurfaceDark = Color(0xFF101114)
private val SurfaceLight = Color(0xFF16181D)
private val BorderDim = Color(0xFF22252B)

private val CardClassA = Color(0xFF131518)
private val CardClassB = Color(0xFF1B1E24)
private val CardBorderA = Color(0xFF262A33)
private val CardBorderB = Color(0xFF384050)

private val NeonBlue = Color(0xFF3B82F6)
private val NeonCyan = Color(0xFF06B6D4)
private val NeonGreen = Color(0xFF00E676)
private val NeonAmber = Color(0xFFF59E0B)
private val NeonRed = Color(0xFFFF5252)
private val LedDimGray = Color(0xFF4B5563)

private val TextPrimary = Color(0xFFF3F4F6)
private val TextSecondary = Color(0xFF9CA3AF)
private val TextMuted = Color(0xFF6B7280)

private val MonospaceTypography = TextStyle(
    fontFamily = FontFamily.Monospace,
    platformStyle = PlatformTextStyle(includeFontPadding = false)
)

// ====================================================================
// Главный Экран: ConveyorMissionScreen
// ====================================================================

@Composable
fun ConveyorMissionScreen(
    viewModel: ConveyorViewModel
) {
    val state by viewModel.screenState.collectAsState()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val mainScrollState = rememberScrollState()
    val gridState = rememberLazyGridState()

    // Обработка системных тактильных сигналов и уведомлений
    LaunchedEffect(Unit) {
        viewModel.sideEffects.collect { effect ->
            when (effect) {
                is ConveyorScreenSideEffect.HapticReportTick -> {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                is ConveyorScreenSideEffect.HapticGreenLightIgnited -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                is ConveyorScreenSideEffect.HapticMissionCompleted -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                is ConveyorScreenSideEffect.HapticMissionFailed -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                is ConveyorScreenSideEffect.ShowToast -> {
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                }
                is ConveyorScreenSideEffect.ScrollToActiveSection -> {
                    scope.launch { mainScrollState.animateScrollTo(200) }
                }
            }
        }
    }

    // Системный жест "Назад": сворачивание карточки, диалога или отмена
    BackHandler(
        enabled = state.expandedBuilderTaskId != null || state.isSettingsDialogOpen || state.mission.isRunning
    ) {
        when {
            state.expandedBuilderTaskId != null -> viewModel.onCollapseExpandedCard()
            state.isSettingsDialogOpen -> viewModel.onCloseSettingsDialog()
            state.mission.isRunning -> viewModel.onCancelMission()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(mainScrollState)
                .padding(bottom = 90.dp) // Запас под нижнюю капсулу ввода
        ) {
            // 1. СИСТЕМНЫЙ СТАТУС-БАР (ХЕДЕР)
            MissionHeaderBar(
                state = state,
                onOpenSettings = { viewModel.onOpenSettingsDialog() }
            )

            // Баннер ошибок (если есть)
            AnimatedVisibility(visible = state.activeBannerError != null) {
                ErrorBannerCard(
                    errorMessage = state.activeBannerError ?: "",
                    onDismiss = { viewModel.onDismissBannerError() }
                )
            }

            // 2. ВЕРХНИЙ МОНОЛИТНЫЙ КВАДРАТ ОРКЕСТРАТОРА (GEMINI 3.8 FLASH)
            OrchestratorMonolithCard(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            )

            // 3. РАНДЕВУ-БАР: 🟢 ГЛАВНАЯ ЗЕЛЕНАЯ ЛАМПА И ДИНАМИЧЕСКИЙ БАРЬЕР
            RendezvousBarrierBar(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )

            // 4. СЕТКА 20 СТРОИТЕЛЕЙ (СХЛОПЫВАНИЕ ПРИ ЗЕЛЕНОМ СВЕТЕ)
            AnimatedVisibility(
                visible = !state.mission.isGreenLightOn,
                enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                exit = shrinkVertically(tween(700, easing = FastOutSlowInEasing)) + fadeOut(tween(500))
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "РОЙ СТРОИТЕЛЕЙ (10 A • 10 B)",
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        style = MonospaceTypography,
                        modifier = Modifier.padding(start = 18.dp, top = 6.dp, bottom = 6.dp)
                    )

                    if (state.mission.builderCards.isEmpty()) {
                        EmptySwarmPlaceholder(state.mission.isRunning)
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 165.dp),
                            state = gridState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 640.dp)
                                .padding(horizontal = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                items = state.mission.builderCards,
                                key = { it.taskId }
                            ) { card ->
                                val isExpanded = state.expandedBuilderTaskId == card.taskId
                                BuilderCardItem(
                                    card = card,
                                    isExpanded = isExpanded,
                                    onToggle = { viewModel.onToggleBuilderCard(card.taskId) },
                                    onCollapse = { viewModel.onCollapseExpandedCard() }
                                )
                            }
                        }
                    }
                }
            }

            // 5. ПОСТ-БАРЬЕРНАЯ КОНСОЛЬ ВЕРИФИКАЦИИ (ОТКРЫВАЕТСЯ ПОСЛЕ СХЛОПЫВАНИЯ)
            AnimatedVisibility(
                visible = state.mission.isGreenLightOn,
                enter = expandVertically(tween(700)) + fadeIn(tween(500)),
                exit = shrinkVertically() + fadeOut()
            ) {
                PostBarrierVerificationConsole(
                    state = state,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }

        // 6. НИЖНЯЯ КАПСУЛА УПРАВЛЕНИЯ И ВВОДА
        MissionControlCapsule(
            state = state,
            onRepoChanged = { viewModel.onRepoInputChanged(it) },
            onBranchChanged = { viewModel.onBranchInputChanged(it) },
            onObjectiveChanged = { viewModel.onObjectiveInputChanged(it) },
            onStart = {
                viewModel.onStartMission()
                focusManager.clearFocus()
                keyboardController?.hide()
            },
            onCancel = { viewModel.onCancelMission() },
            onCollapseCard = { viewModel.onCollapseExpandedCard() },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        )

        // 7. ДИАЛОГ ХРАНИЛИЩА KNOX VAULT
        if (state.isSettingsDialogOpen) {
            KnoxVaultSettingsDialog(
                currentGeminiKey = state.geminiApiKeyMasked,
                currentGitHubPat = state.githubPatMasked,
                onSave = { gemini, github -> viewModel.onSaveCredentials(gemini, github) },
                onDismiss = { viewModel.onCloseSettingsDialog() }
            )
        }
    }
}

// ====================================================================
// Зона 1: Системный Хедер Миссии
// ====================================================================

@Composable
private fun MissionHeaderBar(
    state: ConveyorScreenUiState,
    onOpenSettings: () -> Unit
) {
    Surface(
        color = SurfaceDark,
        border = BorderStroke(1.dp, BorderDim),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (state.mission.isRunning) NeonCyan else LedDimGray)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (state.mission.isRunning) state.mission.repositoryName.ifEmpty { "MISSION ACTIVE" } else "CONVEYOR-G TERMINAL",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        style = MonospaceTypography
                    )
                    if (state.mission.targetBranch.isNotBlank()) {
                        Text(
                            text = ":${state.mission.targetBranch}",
                            color = NeonCyan,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            style = MonospaceTypography
                        )
                    }
                }

                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${state.mission.totalTokensBurned} tok",
                        color = TextMuted,
                        fontSize = 11.sp,
                        style = MonospaceTypography
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "•  $${String.format(java.util.Locale.US, "%.4f", state.mission.estimatedCostUsd)}",
                        color = NeonGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        style = MonospaceTypography
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF0F172A),
                    border = BorderStroke(1.dp, Color(0xFF1E293B)),
                    modifier = Modifier.clickable { onOpenSettings() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = "Knox Vault", tint = if (state.hasValidGeminiKey && state.hasValidGitHubPat) NeonGreen else NeonAmber, modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "KNOX",
                            color = if (state.hasValidGeminiKey && state.hasValidGitHubPat) NeonGreen else NeonAmber,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            style = MonospaceTypography
                        )
                    }
                }
            }
        }
    }
}

// ====================================================================
// Зона 2: Верхний Монолитный Квадрат (Оркестратор Gemini 3.8 Flash)
// ====================================================================

@Composable
private fun OrchestratorMonolithCard(
    state: ConveyorScreenUiState,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ThinkingPulse")
    val animatedGlowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "GlowAlpha"
    )

    val isThinking = state.mission.currentPhase == OrchestratorPhase.REASONING_AND_PLANNING ||
                     state.mission.currentPhase == OrchestratorPhase.EXECUTING_TOOL

    val borderBrush = remember(isThinking, animatedGlowAlpha) {
        if (isThinking) {
            Brush.horizontalGradient(
                listOf(NeonBlue.copy(alpha = animatedGlowAlpha), NeonCyan.copy(alpha = animatedGlowAlpha))
            )
        } else {
            SolidColor(BorderDim)
        }
    }

    Card(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, borderBrush, RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = MonolithBlack)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Верхняя плашка статуса Оркестратора
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CanvasLedIndicator(color = if (isThinking) NeonBlue else NeonGreen, isPulsing = isThinking)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "GEMINI 3.8 FLASH • ORCHESTRATOR",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp,
                        style = MonospaceTypography
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.mission.activeToolName != null) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF1E293B),
                            border = BorderStroke(0.5.dp, NeonCyan)
                        ) {
                            Text(
                                text = "TOOL: ${state.mission.activeToolName}",
                                color = NeonCyan,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                style = MonospaceTypography,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    Text(
                        text = "[ШАГ ${state.mission.currentStep}/${state.mission.maxSteps}]",
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        style = MonospaceTypography
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Текстовая область живых рассуждений (High Thinking)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 90.dp, max = 180.dp)
                    .background(Color(0xFF0A0C10), RoundedCornerShape(8.dp))
                    .border(0.5.dp, Color(0xFF1B202A), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                val thoughtScrollState = rememberScrollState()
                LaunchedEffect(state.mission.liveOrchestratorThought.length) {
                    thoughtScrollState.scrollTo(thoughtScrollState.maxValue)
                }

                SelectionContainer {
                    Text(
                        text = state.mission.liveOrchestratorThought.ifBlank {
                            if (state.mission.isRunning) "Инициализация рассуждений и построение плана миссии..."
                            else "Оркестратор находится в режиме ожидания. Введите задачу и нажмите Старт."
                        },
                        color = if (isThinking) Color(0xFFE2E8F0) else TextMuted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        style = MonospaceTypography,
                        modifier = Modifier.verticalScroll(thoughtScrollState)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Нижняя строка состояния: коммит, фаза, CI
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = state.mission.statusMessage,
                    color = if (state.mission.currentPhase == OrchestratorPhase.FAILED) NeonRed else TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MonospaceTypography,
                    modifier = Modifier.weight(1f, fill = false)
                )

                if (state.mission.lastCommitSha != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "SHA: ${state.mission.lastCommitSha.take(7)}",
                        color = NeonGreen,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        style = MonospaceTypography
                    )
                }
            }
        }
    }
}

// ====================================================================
// Зона 3: Рандеву-Бар с Главной Зеленой Лампой (Dynamic Barrier)
// ====================================================================

@Composable
private fun RendezvousBarrierBar(
    state: ConveyorScreenUiState,
    modifier: Modifier = Modifier
) {
    val isGreen = state.mission.isGreenLightOn
    val barrierTotal = state.mission.barrierTotal.coerceAtLeast(1)
    val remaining = state.mission.barrierRemaining
    val completed = (barrierTotal - remaining).coerceAtLeast(0)
    val progress = (completed.toFloat() / barrierTotal.toFloat()).coerceIn(0f, 1f)

    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "BarrierProgress"
    )

    Card(
        modifier = modifier.clip(RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceLight),
        border = BorderStroke(1.dp, if (isGreen) NeonGreen.copy(alpha = 0.8f) else BorderDim)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Главная Неоновая Лампа (Canvas)
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                contentAlignment = Alignment.Center
            ) {
                CanvasMainGreenLamp(isIgnited = isGreen)
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isGreen) "🟢 ВСЕ 20 БИЛДЕРОВ СДАЛИ ОТЧЕТЫ" else "ДИНАМИЧЕСКИЙ БАРЬЕР СИНХРОНИЗАЦИИ",
                        color = if (isGreen) NeonGreen else TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        style = MonospaceTypography
                    )
                    Text(
                        text = "$completed / $barrierTotal",
                        color = if (isGreen) NeonGreen else NeonCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        style = MonospaceTypography
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Шкала обратного отсчета барьера
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = if (isGreen) NeonGreen else NeonCyan,
                    trackColor = Color(0xFF1E222A)
                )
            }
        }
    }
}

// ====================================================================
// Зона 4: Карточка Строителя (Свернутая и Развернутая Аккордеон)
// ====================================================================

@Composable
private fun BuilderCardItem(
    card: BuilderCardUiModel,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onCollapse: () -> Unit
) {
    val isClassA = card.role == BuilderRole.PRIMARY_A
    val cardBg = if (isClassA) CardClassA else CardClassB
    val cardBorder = if (isExpanded) NeonCyan else if (isClassA) CardBorderA else CardBorderB

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, cardBorder, RoundedCornerShape(10.dp))
            .clickable { onToggle() }
            .animateContentSize(spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)),
        colors = CardDefaults.cardColors(containerColor = cardBg)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // Верхняя плашка карточки билдера
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val ledColor = when (card.status) {
                        BuilderStatus.RUNNING -> NeonCyan
                        BuilderStatus.SUCCESS -> NeonGreen
                        BuilderStatus.FAILED, BuilderStatus.ABORTED_UPSTREAM_CORRUPTED -> NeonRed
                        else -> LedDimGray
                    }
                    CanvasLedIndicator(color = ledColor, isPulsing = card.status == BuilderStatus.RUNNING)
                    Spacer(modifier = Modifier.width(6.dp))

                    Text(
                        text = if (isClassA) "АГЕНТ A${card.stageNumber}" else "АГЕНТ B${card.stageNumber}",
                        color = if (isClassA) TextPrimary else Color(0xFF93C5FD),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        style = MonospaceTypography
                    )
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Black.copy(alpha = 0.4f)
                ) {
                    Text(
                        text = card.status.name.take(7),
                        color = when (card.status) {
                            BuilderStatus.SUCCESS -> NeonGreen
                            BuilderStatus.RUNNING -> NeonCyan
                            BuilderStatus.FAILED -> NeonRed
                            else -> TextMuted
                        },
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        style = MonospaceTypography,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Целевой файл билдера
            Text(
                text = card.targetFile.substringAfterLast('/'),
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MonospaceTypography
            )

            // РАСКРЫВАЮЩИЙСЯ АККОРДЕОН ПО ТАПУ
            if (isExpanded) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = BorderDim, thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "ПОЛНЫЙ ПУТЬ:",
                    color = TextMuted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    style = MonospaceTypography
                )
                Text(
                    text = card.targetFile,
                    color = TextPrimary,
                    fontSize = 10.sp,
                    style = MonospaceTypography
                )

                if (card.summary.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "ОТЧЕТ АГЕНТА:",
                        color = TextMuted,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        style = MonospaceTypography
                    )
                    Text(
                        text = card.summary,
                        color = Color(0xFFE2E8F0),
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        style = MonospaceTypography
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Кнопка сворачивания карточки
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF222630),
                        border = BorderStroke(0.5.dp, BorderDim),
                        modifier = Modifier.clickable { onCollapse() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Свернуть", tint = TextPrimary, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "СВЕРНУТЬ",
                                color = TextPrimary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                style = MonospaceTypography
                            )
                        }
                    }
                }
            }
        }
    }
}

// ====================================================================
// Зона 5: Пост-Барьерная Консоль Верификации (После Схлопывания)
// ====================================================================

@Composable
private fun PostBarrierVerificationConsole(
    state: ConveyorScreenUiState,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, NeonGreen.copy(alpha = 0.5f), RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = MonolithBlack)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = "Verified", tint = NeonGreen, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "ДВУХКОНТУРНАЯ ВЕРИФИКАЦИЯ РЕЗУЛЬТАТА",
                        color = NeonGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        style = MonospaceTypography
                    )
                }

                if (state.mission.ciStatus != null) {
                    Text(
                        text = "CI: ${state.mission.ciStatus?.uppercase()}",
                        color = if (state.mission.ciConclusion == "success") NeonGreen else NeonCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        style = MonospaceTypography
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Все 20 строителей завершили сборку без конфликтов. " +
                       "Локальная файловая система UFS 4.0 зафиксирована. Единый атомарный коммит направлен в ветку ${state.mission.targetBranch}.",
                color = TextSecondary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                style = MonospaceTypography
            )

            if (state.mission.lastCommitSha != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF0F172A),
                    border = BorderStroke(0.5.dp, Color(0xFF1E293B)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "COMMIT: ${state.mission.lastCommitSha}",
                        color = NeonCyan,
                        fontSize = 10.sp,
                        style = MonospaceTypography,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

// ====================================================================
// Зона 6: Нижняя Капсула Управления и Горячие Клавиши DeX
// ====================================================================

@Composable
private fun MissionControlCapsule(
    state: ConveyorScreenUiState,
    onRepoChanged: (String) -> Unit,
    onBranchChanged: (String) -> Unit,
    onObjectiveChanged: (String) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onCollapseCard: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isRunning = state.mission.isRunning

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF121418),
        border = BorderStroke(1.dp, BorderDim),
        shadowElevation = 12.dp,
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // Строка конфигурации репозитория (показывается только когда миссия не запущена)
            if (!isRunning) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.weight(1.8f)) {
                        BasicTextField(
                            value = state.repoInput,
                            onValueChange = onRepoChanged,
                            textStyle = TextStyle(color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                            singleLine = true,
                            cursorBrush = SolidColor(NeonCyan),
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF0A0C0E), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 7.dp)
                                ) {
                                    if (state.repoInput.isEmpty()) {
                                        Text("owner/repository", color = TextMuted, fontSize = 12.sp, style = MonospaceTypography)
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        BasicTextField(
                            value = state.branchInput,
                            onValueChange = onBranchChanged,
                            textStyle = TextStyle(color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                            singleLine = true,
                            cursorBrush = SolidColor(NeonCyan),
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF0A0C0E), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 7.dp)
                                ) {
                                    if (state.branchInput.isEmpty()) {
                                        Text("branch", color = TextMuted, fontSize = 12.sp, style = MonospaceTypography)
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Основная строка ввода задачи
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF0A0C0E), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    if (state.objectiveInput.isEmpty()) {
                        Text(
                            text = if (isRunning) "Миссия выполняется..." else "Опишите задачу (Ctrl+Enter для старта)...",
                            color = TextMuted,
                            fontSize = 12.sp,
                            style = MonospaceTypography
                        )
                    }

                    BasicTextField(
                        value = state.objectiveInput,
                        onValueChange = onObjectiveChanged,
                        enabled = !isRunning,
                        textStyle = TextStyle(color = TextPrimary, fontSize = 12.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace),
                        maxLines = 4,
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (!isRunning) onStart() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onPreviewKeyEvent { event ->
                                // Поддержка Samsung DeX и физических клавиатур: Ctrl + Enter
                                if (event.type == KeyEventType.KeyDown) {
                                    if (event.key == Key.Escape) {
                                        onCollapseCard()
                                        true
                                    } else if ((event.isCtrlPressed || event.isMetaPressed) && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                                        if (!isRunning) onStart()
                                        true
                                    } else false
                                } else false
                            }
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Кнопка СТАРТ / СТОП
                if (isRunning) {
                    Button(
                        onClick = onCancel,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonRed),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Стоп", tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("СТОП", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, style = MonospaceTypography)
                    }
                } else {
                    Button(
                        onClick = onStart,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Старт", tint = Color.Black, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("ПУСК", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold, style = MonospaceTypography)
                    }
                }
            }
        }
    }
}

// ====================================================================
// Графика Canvas: Аппаратные Светодиоды (Zero-LayoutNode)
// ====================================================================

@Composable
private fun CanvasLedIndicator(
    color: Color,
    isPulsing: Boolean = false,
    modifier: Modifier = Modifier.size(12.dp)
) {
    val infiniteTransition = rememberInfiniteTransition(label = "LedPulse")
    val animatedAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "LedAlpha"
    )

    val currentAlpha = if (isPulsing) animatedAlpha else 1.0f

    Canvas(modifier = modifier) {
        val radius = size.minDimension / 2f
        val centerOffset = Offset(size.width / 2f, size.height / 2f)

        // Внешний фотонный ореол рассеивания
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = 0.6f * currentAlpha), color.copy(alpha = 0f)),
                center = centerOffset,
                radius = radius
            ),
            radius = radius,
            center = centerOffset
        )

        // Внутреннее физическое ядро кристалла
        drawCircle(
            color = color.copy(alpha = currentAlpha),
            radius = radius * 0.45f,
            center = centerOffset
        )
    }
}

@Composable
private fun CanvasMainGreenLamp(isIgnited: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "MainLampPulse")
    val animatedGlow by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "LampGlow"
    )

    val baseColor = if (isIgnited) NeonGreen else LedDimGray
    val alpha = if (isIgnited) animatedGlow else 0.4f

    Canvas(modifier = Modifier.fillMaxSize()) {
        val radius = size.minDimension / 2f
        val centerOffset = Offset(size.width / 2f, size.height / 2f)

        if (isIgnited) {
            // Мощный радиальный ореол
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(NeonGreen.copy(alpha = 0.8f * alpha), NeonGreen.copy(alpha = 0f)),
                    center = centerOffset,
                    radius = radius
                ),
                radius = radius,
                center = centerOffset
            )
        }

        // Ядро лампы
        drawCircle(
            color = baseColor.copy(alpha = if (isIgnited) 1f else 0.5f),
            radius = radius * 0.45f,
            center = centerOffset
        )
    }
}

// ====================================================================
// Вспомогательные Элементы (Placeholder & Knox Dialog)
// ====================================================================

@Composable
private fun EmptySwarmPlaceholder(isRunning: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .padding(horizontal = 14.dp)
            .background(SurfaceDark, RoundedCornerShape(10.dp))
            .border(1.dp, BorderDim, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (isRunning) "Оркестратор подготавливает и распределяет задачи роя..."
                   else "Рой из 20 строителей (10 A + 10 B) появится здесь после старта миссии.",
            color = TextMuted,
            fontSize = 11.sp,
            style = MonospaceTypography
        )
    }
}

@Composable
private fun ErrorBannerCard(errorMessage: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2E1215)),
        border = BorderStroke(1.dp, NeonRed.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = errorMessage,
                color = Color(0xFFFFB4AB),
                fontSize = 11.sp,
                style = MonospaceTypography,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = NeonRed, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun KnoxVaultSettingsDialog(
    currentGeminiKey: String,
    currentGitHubPat: String,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var geminiKey by remember { mutableStateOf("") }
    var githubPat by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF121418),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, contentDescription = "Knox", tint = NeonGreen, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Samsung Knox Vault", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, style = MonospaceTypography)
            }
        },
        text = {
            Column {
                Text(
                    text = "Ключи шифруются аппаратным модулем Knox Vault (AES-256-GCM) и никогда не передаются в открытом виде.",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    style = MonospaceTypography
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = geminiKey,
                    onValueChange = { geminiKey = it },
                    label = { Text("Gemini API Key (${currentGeminiKey.ifBlank { "пусто" }})", fontSize = 10.sp) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    textStyle = TextStyle(color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = githubPat,
                    onValueChange = { githubPat = it },
                    label = { Text("GitHub PAT (${currentGitHubPat.ifBlank { "пусто" }})", fontSize = 10.sp) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    textStyle = TextStyle(color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(geminiKey, githubPat) },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White)
            ) {
                Text("СОХРАНИТЬ", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold, style = MonospaceTypography)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("ОТМЕНА", color = TextSecondary, fontSize = 11.sp, style = MonospaceTypography)
            }
        }
    )
}