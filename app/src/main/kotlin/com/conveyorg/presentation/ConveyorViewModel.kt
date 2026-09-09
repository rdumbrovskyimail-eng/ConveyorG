package com.conveyorg.presentation

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.conveyorg.agent.AutonomousConveyorController
import com.conveyorg.agent.ConveyorMissionUiEvent
import com.conveyorg.agent.ConveyorMissionUiState
import com.conveyorg.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ConveyorScreenUiState(
    val repoInput: String = "owner/repository",
    val branchInput: String = "main",
    val objectiveInput: String = "",
    val mission: ConveyorMissionUiState = ConveyorMissionUiState(),
    val expandedBuilderTaskId: String? = null,
    val isSettingsDialogOpen: Boolean = false,
    val isDeepLogOpen: Boolean = false,
    val geminiApiKeyMasked: String = "",
    val githubPatMasked: String = "",
    val hasValidGeminiKey: Boolean = false,
    val hasValidGitHubPat: Boolean = false,
    val activeBannerError: String? = null
)

sealed interface ConveyorScreenSideEffect {
    data object HapticReportTick : ConveyorScreenSideEffect
    data object HapticGreenLightIgnited : ConveyorScreenSideEffect
    data object HapticMissionCompleted : ConveyorScreenSideEffect
    data object HapticMissionFailed : ConveyorScreenSideEffect
    data class ShowToast(val message: String) : ConveyorScreenSideEffect
    data object ScrollToActiveSection : ConveyorScreenSideEffect
}

class ConveyorViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    companion object {
        private const val PREF_FILE_SECURE = "conveyorg_knox_vault"
        private const val PREF_FILE_FALLBACK = "conveyorg_vault_fallback"
        private const val KEY_GEMINI_KEY = "vault_gemini_api_key"
        private const val KEY_GITHUB_PAT = "vault_github_pat_token"
        private const val STATE_KEY_REPO = "saved_repo_input"
        private const val STATE_KEY_BRANCH = "saved_branch_input"
        private const val STATE_KEY_OBJECTIVE = "saved_objective_input"
    }

    @Volatile private var cachedGeminiKey: String = ""
    @Volatile private var cachedGitHubPat: String = ""
    @Volatile private var securePrefs: SharedPreferences? = null

    private val conveyorController: AutonomousConveyorController

    private val _screenState = MutableStateFlow(
        ConveyorScreenUiState(
            repoInput = savedStateHandle.get<String>(STATE_KEY_REPO) ?: "rdumbrovskyimail-eng/TestC",
            branchInput = savedStateHandle.get<String>(STATE_KEY_BRANCH) ?: "main",
            objectiveInput = savedStateHandle.get<String>(STATE_KEY_OBJECTIVE) ?: ""
        )
    )
    val screenState: StateFlow<ConveyorScreenUiState> = _screenState.asStateFlow()

    private val _sideEffects = Channel<ConveyorScreenSideEffect>(
        capacity = Channel.BUFFERED,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val sideEffects: Flow<ConveyorScreenSideEffect> = _sideEffects.receiveAsFlow()

    init {
        val initialPrefs = getSecurePreferences(getApplication())
        val initialGemini = initialPrefs.getString(KEY_GEMINI_KEY, "") ?: ""
        val initialGitHub = initialPrefs.getString(KEY_GITHUB_PAT, "") ?: ""

        cachedGeminiKey = initialGemini
        cachedGitHubPat = initialGitHub

        _screenState.update { current ->
            current.copy(
                geminiApiKeyMasked = AppLogger.maskKey(initialGemini),
                githubPatMasked = AppLogger.maskKey(initialGitHub),
                hasValidGeminiKey = initialGemini.isNotBlank(),
                hasValidGitHubPat = initialGitHub.isNotBlank()
            )
        }

        conveyorController = AutonomousConveyorController(
            context = getApplication(),
            geminiApiKeyProvider = { cachedGeminiKey },
            gitHubTokenProvider = { cachedGitHubPat }
        )

        viewModelScope.launch {
            conveyorController.uiState.collect { missionState ->
                _screenState.update { it.copy(mission = missionState) }
            }
        }

        viewModelScope.launch {
            conveyorController.uiEvents.collect { event ->
                handleConveyorUiEvent(event)
            }
        }
    }

    fun onRepoInputChanged(newValue: String) {
        val trimmed = newValue.trim()
        savedStateHandle[STATE_KEY_REPO] = trimmed
        _screenState.update { it.copy(repoInput = trimmed) }
    }

    fun onBranchChanged(newValue: String) {
        val trimmed = newValue.trim()
        savedStateHandle[STATE_KEY_BRANCH] = trimmed
        _screenState.update { it.copy(branchInput = trimmed) }
    }

    fun onObjectiveInputChanged(newValue: String) {
        savedStateHandle[STATE_KEY_OBJECTIVE] = newValue
        _screenState.update { it.copy(objectiveInput = newValue) }
    }

    fun onStartMission() {
        val current = _screenState.value
        if (cachedGeminiKey.isBlank() || cachedGitHubPat.isBlank()) {
            _screenState.update { it.copy(isSettingsDialogOpen = true, activeBannerError = "Укажите ключи доступа.") }
            return
        }

        val repoParts = current.repoInput.split('/')
        if (repoParts.size != 2 || repoParts[0].isBlank() || repoParts[1].isBlank()) {
            _screenState.update { it.copy(activeBannerError = "Формат: owner/repository.") }
            return
        }

        _screenState.update { it.copy(activeBannerError = null, expandedBuilderTaskId = null) }

        conveyorController.startMission(
            owner = repoParts[0].trim(),
            repo = repoParts[1].trim(),
            branch = current.branchInput.trim(),
            userObjective = current.objectiveInput.trim()
        )
    }

    fun onCancelMission() {
        conveyorController.cancelMission()
    }

    fun onToggleDeepLog() {
        _screenState.update { it.copy(isDeepLogOpen = !it.isDeepLogOpen) }
    }

    fun onOpenSettingsDialog() {
        _screenState.update { it.copy(isSettingsDialogOpen = true) }
    }

    fun onCloseSettingsDialog() {
        _screenState.update { it.copy(isSettingsDialogOpen = false) }
    }

    fun onToggleBuilderCard(taskId: String) {
        _screenState.update { it.copy(expandedBuilderTaskId = if (it.expandedBuilderTaskId == taskId) null else taskId) }
    }

    fun onCollapseExpandedCard() {
        _screenState.update { it.copy(expandedBuilderTaskId = null) }
    }

    fun onSaveCredentials(geminiKey: String, githubPat: String) {
        val cleanGemini = geminiKey.trim().ifBlank { cachedGeminiKey }
        val cleanGitHub = githubPat.trim().ifBlank { cachedGitHubPat }

        cachedGeminiKey = cleanGemini
        cachedGitHubPat = cleanGitHub

        viewModelScope.launch(Dispatchers.IO) {
            val editor = getSecurePreferences(getApplication()).edit()
            editor.putString(KEY_GEMINI_KEY, cleanGemini)
            editor.putString(KEY_GITHUB_PAT, cleanGitHub)
            editor.commit()

            _screenState.update {
                it.copy(
                    geminiApiKeyMasked = AppLogger.maskKey(cleanGemini),
                    githubPatMasked = AppLogger.maskKey(cleanGitHub),
                    hasValidGeminiKey = cleanGemini.isNotBlank(),
                    hasValidGitHubPat = cleanGitHub.isNotBlank(),
                    isSettingsDialogOpen = false
                )
            }
        }
    }

    fun onDismissBannerError() {
        _screenState.update { it.copy(activeBannerError = null) }
    }

    private fun handleConveyorUiEvent(event: ConveyorMissionUiEvent) {
        when (event) {
            is ConveyorMissionUiEvent.HapticTrigger -> _sideEffects.trySend(if (event.isStrong) ConveyorScreenSideEffect.HapticGreenLightIgnited else ConveyorScreenSideEffect.HapticReportTick)
            is ConveyorMissionUiEvent.GreenLightIgnited -> _sideEffects.trySend(ConveyorScreenSideEffect.HapticGreenLightIgnited)
            is ConveyorMissionUiEvent.MissionCompleted -> _sideEffects.trySend(ConveyorScreenSideEffect.HapticMissionCompleted)
            is ConveyorMissionUiEvent.MissionFailed -> {
                _sideEffects.trySend(ConveyorScreenSideEffect.HapticMissionFailed)
                _screenState.update { it.copy(activeBannerError = event.reason) }
            }
            is ConveyorMissionUiEvent.ToastNotification -> _sideEffects.trySend(ConveyorScreenSideEffect.ShowToast(event.message))
        }
    }

    private fun getSecurePreferences(context: Context): SharedPreferences {
        return securePrefs ?: synchronized(this) {
            securePrefs ?: createSafeSharedPreferences(context).also { securePrefs = it }
        }
    }

    private fun createSafeSharedPreferences(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                context, PREF_FILE_SECURE, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Throwable) {
            context.getSharedPreferences(PREF_FILE_FALLBACK, Context.MODE_PRIVATE)
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch(NonCancellable) { conveyorController.close() }
    }
}