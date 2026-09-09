package com.conveyorg.presentation

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.conveyorg.agent.BuilderCardUiModel
import com.conveyorg.agent.BuilderRole
import com.conveyorg.agent.BuilderStatus
import com.conveyorg.agent.OrchestratorPhase

private val TerminalBg = Color(0xFF08080A)
private val MonolithBlack = Color(0xFF000000)
private val SurfaceDark = Color(0xFF101114)
private val SurfaceLight = Color(0xFF16181D)
private val BorderDim = Color(0xFF22252B)

private val CardClassA = Color(0xFF131518)
private val CardClassB = Color(0xFF1B1E24)

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

@Composable
fun ConveyorMissionScreen(viewModel: ConveyorViewModel) {
    val state by viewModel.screenState.collectAsState()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val mainScrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        viewModel.sideEffects.collect { effect ->
            when (effect) {
                is ConveyorScreenSideEffect.HapticReportTick -> haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                is ConveyorScreenSideEffect.HapticGreenLightIgnited,
                is ConveyorScreenSideEffect.HapticMissionCompleted,
                is ConveyorScreenSideEffect.HapticMissionFailed -> haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                is ConveyorScreenSideEffect.ShowToast -> Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                else -> Unit
            }
        }
    }

    BackHandler(enabled = state.isDeepLogOpen || state.expandedBuilderTaskId != null || state.isSettingsDialogOpen) {
        when {
            state.isDeepLogOpen -> viewModel.onToggleDeepLog()
            state.expandedBuilderTaskId != null -> viewModel.onCollapseExpandedCard()
            state.isSettingsDialogOpen -> viewModel.onCloseSettingsDialog()
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
                .padding(bottom = 90.dp)
        ) {
            MissionHeaderBar(
                state = state,
                onOpenSettings = { viewModel.onOpenSettingsDialog() },
                onToggleDeepLog = { viewModel.onToggleDeepLog() }
            )

            AnimatedVisibility(visible = state.activeBannerError != null) {
                ErrorBannerCard(errorMessage = state.activeBannerError ?: "") { viewModel.onDismissBannerError() }
            }

            OrchestratorMonolithCard(
                state = state,
                onOpenLog = { viewModel.onToggleDeepLog() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
            )

            RendezvousBarrierBar(
                state = state,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)
            )

            AnimatedVisibility(visible = !state.mission.isGreenLightOn) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "РОЙ СТРОИТЕЛЕЙ (10 A • 10 B)",
                        color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        style = MonospaceTypography, modifier = Modifier.padding(start = 18.dp, top = 6.dp, bottom = 6.dp)
                    )

                    if (state.mission.builderCards.isEmpty()) {
                        EmptySwarmPlaceholder(state.mission.isRunning)
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 165.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(horizontal = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(items = state.mission.builderCards, key = { it.taskId }) { card ->
                                BuilderCardItem(
                                    card = card,
                                    isExpanded = state.expandedBuilderTaskId == card.taskId,
                                    onToggle = { viewModel.onToggleBuilderCard(card.taskId) },
                                    onCollapse = { viewModel.onCollapseExpandedCard() }
                                )
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(visible = state.mission.isGreenLightOn) {
                PostBarrierVerificationConsole(
                    state = state,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }

        MissionControlCapsule(
            state = state,
            onRepoChanged = { viewModel.onRepoInputChanged(it) },
            onBranchChanged = { viewModel.onBranchChanged(it) },
            onObjectiveChanged = { viewModel.onObjectiveInputChanged(it) },
            onStart = {
                viewModel.onStartMission()
                focusManager.clearFocus()
                keyboardController?.hide()
            },
            onCancel = { viewModel.onCancelMission() },
            onCollapseCard = { viewModel.onCollapseExpandedCard() },
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.ime).padding(horizontal = 14.dp, vertical = 8.dp)
        )

        if (state.isSettingsDialogOpen) {
            KnoxVaultSettingsDialog(
                currentGeminiKey = state.geminiApiKeyMasked,
                currentGitHubPat = state.githubPatMasked,
                onSave = { gemini, github -> viewModel.onSaveCredentials(gemini, github) },
                onDismiss = { viewModel.onCloseSettingsDialog() }
            )
        }

        if (state.isDeepLogOpen) {
            DeepLogDialog(
                logContent = state.mission.deepInvestigationLog.ifBlank { "Журнал сессии пока пуст. Запустите задачу для формирования этапов 1-4." },
                onDismiss = { viewModel.onToggleDeepLog() }
            )
        }
    }
}

@Composable
private fun MissionHeaderBar(
    state: ConveyorScreenUiState,
    onOpenSettings: () -> Unit,
    onToggleDeepLog: () -> Unit
) {
    Surface(color = SurfaceDark, border = BorderStroke(1.dp, BorderDim), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(if (state.mission.isRunning) NeonCyan else LedDimGray))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (state.mission.isRunning) state.mission.repositoryName else "CONVEYOR-G",
                        color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, style = MonospaceTypography
                    )
                }
                Text(
                    text = "${state.mission.totalTokensBurned} tok • $${String.format(java.util.Locale.US, "%.4f", state.mission.estimatedCostUsd)}",
                    color = NeonGreen, fontSize = 10.sp, style = MonospaceTypography, modifier = Modifier.padding(top = 2.dp)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (state.mission.deepInvestigationLog.isNotBlank()) Color(0xFF1E293B) else Color(0xFF111318),
                    border = BorderStroke(1.dp, if (state.mission.deepInvestigationLog.isNotBlank()) NeonCyan else BorderDim),
                    modifier = Modifier.clickable { onToggleDeepLog() }
                ) {
                    Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "ЖУРНАЛ / LOG",
                            color = if (state.mission.deepInvestigationLog.isNotBlank()) NeonCyan else TextMuted,
                            fontSize = 10.sp, fontWeight = FontWeight.Bold, style = MonospaceTypography
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp), color = Color(0xFF0F172A), border = BorderStroke(1.dp, Color(0xFF1E293B)),
                    modifier = Modifier.clickable { onOpenSettings() }
                ) {
                    Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, contentDescription = "Knox", tint = if (state.hasValidGeminiKey) NeonGreen else NeonAmber, modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("KNOX", color = if (state.hasValidGeminiKey) NeonGreen else NeonAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold, style = MonospaceTypography)
                    }
                }
            }
        }
    }
}

@Composable
private fun DeepLogDialog(
    logContent: String,
    onDismiss: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0A0A0C),
        shape = RoundedCornerShape(12.dp),
        title = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(NeonCyan))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("ЖУРНАЛ СЕССИИ (DEEP LOG)", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, style = MonospaceTypography)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = TextMuted, modifier = Modifier.size(16.dp))
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 280.dp, max = 560.dp)
                    .background(Color(0xFF050506), RoundedCornerShape(8.dp))
                    .border(1.dp, BorderDim, RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                SelectionContainer(modifier = Modifier.verticalScroll(scrollState)) {
                    Text(
                        text = logContent,
                        color = Color(0xFFE2E8F0),
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        style = MonospaceTypography
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(logContent))
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        Toast.makeText(context, "Лог скопирован в буфер", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("КОПИРОВАТЬ", color = NeonCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, style = MonospaceTypography)
                }
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("ЗАКРЫТЬ", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold, style = MonospaceTypography)
                }
            }
        }
    )
}

@Composable
private fun OrchestratorMonolithCard(
    state: ConveyorScreenUiState,
    onOpenLog: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, BorderDim, RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = MonolithBlack)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CanvasLedIndicator(color = if (state.mission.isRunning) NeonBlue else NeonGreen, isPulsing = state.mission.isRunning)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("GEMINI 3.8 FLASH • ORCHESTRATOR", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Black, style = MonospaceTypography)
                }
                Text("[ШАГ ${state.mission.currentStep}/${state.mission.maxSteps}]", color = NeonCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, style = MonospaceTypography)
            }

            Spacer(modifier = Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 90.dp, max = 160.dp)
                    .background(Color(0xFF0A0C10), RoundedCornerShape(8.dp))
                    .border(0.5.dp, Color(0xFF1B202A), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = state.mission.liveOrchestratorThought.ifBlank { state.mission.statusMessage },
                        color = Color(0xFFE2E8F0), fontSize = 11.sp, lineHeight = 16.sp, style = MonospaceTypography
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(text = state.mission.statusMessage, color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MonospaceTypography, modifier = Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(4.dp), color = Color(0xFF16202E), border = BorderStroke(0.5.dp, NeonCyan),
                    modifier = Modifier.clickable { onOpenLog() }
                ) {
                    Text("ЛОГ ↗", color = NeonCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold, style = MonospaceTypography, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun RendezvousBarrierBar(state: ConveyorScreenUiState, modifier: Modifier = Modifier) {
    val barrierTotal = state.mission.barrierTotal.coerceAtLeast(1)
    val remaining = state.mission.barrierRemaining
    val completed = (barrierTotal - remaining).coerceAtLeast(0)
    val progress = (completed.toFloat() / barrierTotal.toFloat()).coerceIn(0f, 1f)

    Card(modifier = modifier.clip(RoundedCornerShape(12.dp)), colors = CardDefaults.cardColors(containerColor = SurfaceLight), border = BorderStroke(1.dp, if (state.mission.isGreenLightOn) NeonGreen else BorderDim)) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            CanvasMainGreenLamp(isIgnited = state.mission.isGreenLightOn)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = if (state.mission.isGreenLightOn) "🟢 ЗЕЛЕНАЯ ЛАМПОЧКА" else "РАНДЕВУ-БАРЬЕР", color = if (state.mission.isGreenLightOn) NeonGreen else TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, style = MonospaceTypography)
                    Text(text = "$completed / $barrierTotal", color = NeonCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, style = MonospaceTypography)
                }
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = if (state.mission.isGreenLightOn) NeonGreen else NeonCyan, trackColor = Color(0xFF1E222A))
            }
        }
    }
}

@Composable
private fun BuilderCardItem(card: BuilderCardUiModel, isExpanded: Boolean, onToggle: () -> Unit, onCollapse: () -> Unit) {
    val isClassA = card.role == BuilderRole.PRIMARY_A
    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, if (isExpanded) NeonCyan else BorderDim, RoundedCornerShape(10.dp)).clickable { onToggle() },
        colors = CardDefaults.cardColors(containerColor = if (isClassA) CardClassA else CardClassB)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CanvasLedIndicator(color = if (card.status == BuilderStatus.SUCCESS) NeonGreen else NeonCyan, isPulsing = card.status == BuilderStatus.RUNNING)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = if (isClassA) "A${card.stageNumber}" else "B${card.stageNumber}", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, style = MonospaceTypography)
                }
                Text(text = card.status.name.take(6), color = if (card.status == BuilderStatus.SUCCESS) NeonGreen else TextMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold, style = MonospaceTypography)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = card.targetFile.substringAfterLast('/'), color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MonospaceTypography)
        }
    }
}

@Composable
private fun PostBarrierVerificationConsole(state: ConveyorScreenUiState, modifier: Modifier = Modifier) {
    Card(modifier = modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, NeonGreen.copy(alpha = 0.5f), RoundedCornerShape(14.dp)), colors = CardDefaults.cardColors(containerColor = MonolithBlack)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, contentDescription = "OK", tint = NeonGreen, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("ДВУХКОНТУРНАЯ ВЕРИФИКАЦИЯ", color = NeonGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold, style = MonospaceTypography)
            }
            if (state.mission.lastCommitSha != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text("COMMIT: ${state.mission.lastCommitSha}", color = NeonCyan, fontSize = 10.sp, style = MonospaceTypography)
            }
        }
    }
}

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
    Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF121418), border = BorderStroke(1.dp, BorderDim), shadowElevation = 12.dp, modifier = modifier) {
        Column(modifier = Modifier.padding(10.dp)) {
            if (!state.mission.isRunning) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.weight(1.8f)) {
                        BasicTextField(
                            value = state.repoInput, onValueChange = onRepoChanged,
                            textStyle = TextStyle(color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace), singleLine = true, cursorBrush = SolidColor(NeonCyan),
                            decorationBox = { if (state.repoInput.isEmpty()) Text("owner/repo", color = TextMuted, fontSize = 12.sp, style = MonospaceTypography); it() }
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        BasicTextField(
                            value = state.branchInput, onValueChange = onBranchChanged,
                            textStyle = TextStyle(color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace), singleLine = true, cursorBrush = SolidColor(NeonCyan),
                            decorationBox = { if (state.branchInput.isEmpty()) Text("branch", color = TextMuted, fontSize = 12.sp, style = MonospaceTypography); it() }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f).background(Color(0xFF0A0C0E), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (state.objectiveInput.isEmpty()) {
                        Text(if (state.mission.isRunning) "Выполняется..." else "Задача для Конвейера...", color = TextMuted, fontSize = 12.sp, style = MonospaceTypography)
                    }
                    BasicTextField(
                        value = state.objectiveInput, onValueChange = onObjectiveChanged, enabled = !state.mission.isRunning,
                        textStyle = TextStyle(color = TextPrimary, fontSize = 12.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace), maxLines = 4, cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { if (!state.mission.isRunning) onStart() }),
                        modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && (event.isCtrlPressed || event.isMetaPressed) && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                                if (!state.mission.isRunning) onStart(); true
                            } else false
                        }
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                if (state.mission.isRunning) {
                    Button(onClick = onCancel, colors = ButtonDefaults.buttonColors(containerColor = NeonRed), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                        Text("СТОП", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, style = MonospaceTypography)
                    }
                } else {
                    Button(onClick = onStart, colors = ButtonDefaults.buttonColors(containerColor = Color.White), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                        Text("ПУСК", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold, style = MonospaceTypography)
                    }
                }
            }
        }
    }
}

@Composable
private fun CanvasLedIndicator(color: Color, isPulsing: Boolean = false, modifier: Modifier = Modifier.size(10.dp)) {
    val infiniteTransition = rememberInfiniteTransition(label = "Led")
    val alpha by infiniteTransition.animateFloat(initialValue = 0.4f, targetValue = 1f, animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "A")
    val currentAlpha = if (isPulsing) alpha else 1f
    Canvas(modifier = modifier) {
        val centerOffset = Offset(size.width / 2f, size.height / 2f)
        drawCircle(color = color.copy(alpha = currentAlpha), radius = size.minDimension / 2f, center = centerOffset)
    }
}

@Composable
private fun CanvasMainGreenLamp(isIgnited: Boolean) {
    Canvas(modifier = Modifier.size(24.dp)) {
        val centerOffset = Offset(size.width / 2f, size.height / 2f)
        drawCircle(color = if (isIgnited) NeonGreen else LedDimGray, radius = size.minDimension / 2f, center = centerOffset)
    }
}

@Composable
private fun EmptySwarmPlaceholder(isRunning: Boolean) {
    Box(modifier = Modifier.fillMaxWidth().height(80.dp).padding(horizontal = 14.dp).background(SurfaceDark, RoundedCornerShape(10.dp)).border(1.dp, BorderDim, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        Text(text = if (isRunning) "Оркестратор координирует задачи..." else "Рой строителей активируется при масштабных задачах.", color = TextMuted, fontSize = 11.sp, style = MonospaceTypography)
    }
}

@Composable
private fun ErrorBannerCard(errorMessage: String, onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF2E1215)), border = BorderStroke(1.dp, NeonRed.copy(alpha = 0.6f))) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = errorMessage, color = Color(0xFFFFB4AB), fontSize = 11.sp, style = MonospaceTypography, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Close, contentDescription = "X", tint = NeonRed, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun KnoxVaultSettingsDialog(currentGeminiKey: String, currentGitHubPat: String, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var geminiKey by remember { mutableStateOf("") }
    var githubPat by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Color(0xFF121418),
        title = { Text("Samsung Knox Vault", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, style = MonospaceTypography) },
        text = {
            Column {
                OutlinedTextField(value = geminiKey, onValueChange = { geminiKey = it }, label = { Text("Gemini API Key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = githubPat, onValueChange = { githubPat = it }, label = { Text("GitHub PAT") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { Button(onClick = { onSave(geminiKey, githubPat) }) { Text("СОХРАНИТЬ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ОТМЕНА") } }
    )
}