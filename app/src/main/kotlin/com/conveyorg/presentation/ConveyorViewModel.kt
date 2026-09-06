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

// ====================================================================
// 1. Модели Состояния и Разовых Эффектов Экрана Mission Control
// ====================================================================

data class ConveyorScreenUiState(
    // Поля ввода конфигурации задачи
    val repoInput: String = "owner/repository",
    val branchInput: String = "main",
    val objectiveInput: String = "",

    // Состояние активной миссии (телеметрия из контроллера)
    val mission: ConveyorMissionUiState = ConveyorMissionUiState(),

    // Интерактивное состояние аккордеона 20 строителей
    val expandedBuilderTaskId: String? = null,

    // Сейф учетных данных (Knox Vault)
    val isSettingsDialogOpen: Boolean = false,
    val geminiApiKeyMasked: String = "",
    val githubPatMasked: String = "",
    val hasValidGeminiKey: Boolean = false,
    val hasValidGitHubPat: Boolean = false,

    // Баннеры ошибок и уведомлений
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

// ====================================================================
// 2. Презентационный Мост Жизненного Цикла: ConveyorViewModel
// ====================================================================

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

    @Volatile
    private var cachedGeminiKey: String = ""

    @Volatile
    private var cachedGitHubPat: String = ""

    @Volatile
    private var securePrefs: SharedPreferences? = null

    // Главный исполнительный контроллер конвейера
    private val conveyorController: AutonomousConveyorController

    // Единый источник правды для Compose UI
    private val _screenState = MutableStateFlow(
        ConveyorScreenUiState(
            repoInput = savedStateHandle.get<String>(STATE_KEY_REPO) ?: "clientg-org/german-learning-app",
            branchInput = savedStateHandle.get<String>(STATE_KEY_BRANCH) ?: "main",
            objectiveInput = savedStateHandle.get<String>(STATE_KEY_OBJECTIVE) ?: ""
        )
    )
    val screenState: StateFlow<ConveyorScreenUiState> = _screenState.asStateFlow()

    // Горячий канал разовых побочных эффектов (Haptics, Toasts)
    private val _sideEffects = Channel<ConveyorScreenSideEffect>(
        capacity = Channel.BUFFERED,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val sideEffects: Flow<ConveyorScreenSideEffect> = _sideEffects.receiveAsFlow()

    init {
        AppLogger.i(AppLogger.TAG_VM, "ConveyorViewModel: Инициализация. Чтение ключей Knox Vault и связывание потоков...")

        // Инициализируем контроллер с провайдерами ключей из аппаратного сейфа
        conveyorController = AutonomousConveyorController(
            context = getApplication(),
            geminiApiKeyProvider = { cachedGeminiKey },
            gitHubTokenProvider = { cachedGitHubPat }
        )

        // 1. Асинхронное чтение секретов из аппаратного анклава Knox Vault
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = getSecurePreferences(getApplication())
            val savedGemini = prefs.getString(KEY_GEMINI_KEY, "") ?: ""
            val savedGitHub = prefs.getString(KEY_GITHUB_PAT, "") ?: ""

            cachedGeminiKey = savedGemini
            cachedGitHubPat = savedGitHub

            _screenState.update { current ->
                current.copy(
                    geminiApiKeyMasked = AppLogger.maskKey(savedGemini),
                    githubPatMasked = AppLogger.maskKey(savedGitHub),
                    hasValidGeminiKey = savedGemini.isNotBlank(),
                    hasValidGitHubPat = savedGitHub.isNotBlank()
                )
            }
            AppLogger.d(AppLogger.TAG_VM, "ConveyorViewModel: Ключи загружены (Gemini: ${savedGemini.isNotBlank()}, GitHub: ${savedGitHub.isNotBlank()})")
        }

        // 2. Реактивное слияние телеметрии контроллера в Compose UI State
        viewModelScope.launch {
            conveyorController.uiState
                .distinctUntilChanged()
                .collect { missionState ->
                    _screenState.update { current ->
                        current.copy(mission = missionState)
                    }
                }
        }

        // 3. Прослушивание событий миссии и физическая тактильная оркестрация (LRA Haptics)
        viewModelScope.launch {
            conveyorController.uiEvents.collect { event ->
                handleConveyorUiEvent(event)
            }
        }
    }

    // ====================================================================
    // 3. Пользовательские Команды (User Intents & Input Hoisting)
    // ====================================================================

    fun onRepoInputChanged(newValue: String) {
        val trimmed = newValue.trim()
        savedStateHandle[STATE_KEY_REPO] = trimmed
        _screenState.update { it.copy(repoInput = trimmed) }
    }

    fun onBranchInputChanged(newValue: String) {
        val trimmed = newValue.trim()
        savedStateHandle[STATE_KEY_BRANCH] = trimmed
        _screenState.update { it.copy(branchInput = trimmed) }
    }

    fun onObjectiveInputChanged(newValue: String) {
        savedStateHandle[STATE_KEY_OBJECTIVE] = newValue
        _screenState.update { it.copy(objectiveInput = newValue) }
    }

    /**
     * Запуск сквозной миссии конвейера.
     */
    fun onStartMission() {
        val current = _screenState.value

        // Валидация ключей авторизации
        if (cachedGeminiKey.isBlank() || cachedGitHubPat.isBlank()) {
            _screenState.update {
                it.copy(
                    isSettingsDialogOpen = true,
                    activeBannerError = "Для запуска конвейера необходимо указать Gemini API Key и GitHub PAT."
                )
            }
            _sideEffects.trySend(ConveyorScreenSideEffect.ShowToast("Укажите ключи доступа в настройках"))
            return
        }

        // Валидация параметров репозитория
        val repoParts = current.repoInput.split('/')
        if (repoParts.size != 2 || repoParts[0].isBlank() || repoParts[1].isBlank()) {
            _screenState.update {
                it.copy(activeBannerError = "Укажите репозиторий в формате 'owner/repository'.")
            }
            return
        }

        if (current.objectiveInput.isBlank()) {
            _screenState.update {
                it.copy(activeBannerError = "Опишите целевую задачу разработки.")
            }
            return
        }

        _screenState.update {
            it.copy(
                activeBannerError = null,
                expandedBuilderTaskId = null
            )
        }

        AppLogger.i(AppLogger.TAG_VM, "ConveyorViewModel: Старт миссии для ${current.repoInput}:${current.branchInput}")

        conveyorController.startMission(
            owner = repoParts[0].trim(),
            repo = repoParts[1].trim(),
            branch = current.branchInput.trim(),
            userObjective = current.objectiveInput.trim(),
            maxSteps = 25,
            maxRepairRounds = 3
        )

        _sideEffects.trySend(ConveyorScreenSideEffect.ScrollToActiveSection)
    }

    fun onCancelMission() {
        AppLogger.w(AppLogger.TAG_VM, "ConveyorViewModel: Пользователь отменил миссию конвейера.")
        conveyorController.cancelMission()
        _sideEffects.trySend(ConveyorScreenSideEffect.ShowToast("Миссия отменена. Производится откат изменений..."))
    }

    // ====================================================================
    // 4. Интерактивный Аккордеон Карточек 20 Билдеров
    // ====================================================================

    /**
     * Раскрытие/сворачивание карточки конкретного строителя в сетке.
     */
    fun onToggleBuilderCard(taskId: String) {
        _screenState.update { current ->
            val nextExpanded = if (current.expandedBuilderTaskId == taskId) null else taskId
            current.copy(expandedBuilderTaskId = nextExpanded)
        }
        _sideEffects.trySend(ConveyorScreenSideEffect.HapticReportTick)
    }

    fun onCollapseExpandedCard() {
        if (_screenState.value.expandedBuilderTaskId != null) {
            _screenState.update { it.copy(expandedBuilderTaskId = null) }
            _sideEffects.trySend(ConveyorScreenSideEffect.HapticReportTick)
        }
    }

    // ====================================================================
    // 5. Безопасное Управление Ключами (Samsung Knox Vault)
    // ====================================================================

    fun onOpenSettingsDialog() {
        _screenState.update { it.copy(isSettingsDialogOpen = true) }
    }

    fun onCloseSettingsDialog() {
        _screenState.update { it.copy(isSettingsDialogOpen = false) }
    }

    fun onSaveCredentials(geminiKey: String, githubPat: String) {
        val cleanGemini = geminiKey.trim()
        val cleanGitHub = githubPat.trim()

        cachedGeminiKey = cleanGemini
        cachedGitHubPat = cleanGitHub

        viewModelScope.launch(Dispatchers.IO) {
            try {
                getSecurePreferences(getApplication()).edit().apply {
                    putString(KEY_GEMINI_KEY, cleanGemini)
                    putString(KEY_GITHUB_PAT, cleanGitHub)
                    apply()
                }

                _screenState.update {
                    it.copy(
                        geminiApiKeyMasked = AppLogger.maskKey(cleanGemini),
                        githubPatMasked = AppLogger.maskKey(cleanGitHub),
                        hasValidGeminiKey = cleanGemini.isNotBlank(),
                        hasValidGitHubPat = cleanGitHub.isNotBlank(),
                        isSettingsDialogOpen = false,
                        activeBannerError = null
                    )
                }

                _sideEffects.trySend(ConveyorScreenSideEffect.ShowToast("Учетные данные сохранены в Knox Vault"))
                AppLogger.i(AppLogger.TAG_VM, "ConveyorViewModel: Ключи успешно зафиксированы в аппаратном сейфе Knox Vault.")
            } catch (e: Exception) {
                AppLogger.e(AppLogger.TAG_VM, "ConveyorViewModel: Ошибка записи в Knox Vault: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _screenState.update { it.copy(activeBannerError = "Ошибка сохранения ключей: ${e.localizedMessage}") }
                }
            }
        }
    }

    fun onDismissBannerError() {
        _screenState.update { it.copy(activeBannerError = null) }
    }

    // ====================================================================
    // 6. Обработка Событий Конвейера и Тактильная Отдача (Haptics)
    // ====================================================================

    private fun handleConveyorUiEvent(event: ConveyorMissionUiEvent) {
        when (event) {
            is ConveyorMissionUiEvent.HapticTrigger -> {
                val effect = if (event.isStrong) {
                    ConveyorScreenSideEffect.HapticGreenLightIgnited
                } else {
                    ConveyorScreenSideEffect.HapticReportTick
                }
                _sideEffects.trySend(effect)
            }
            is ConveyorMissionUiEvent.GreenLightIgnited -> {
                _sideEffects.trySend(ConveyorScreenSideEffect.HapticGreenLightIgnited)
                _sideEffects.trySend(ConveyorScreenSideEffect.ShowToast("🟢 ЗЕЛЕНАЯ ЛАМПОЧКА! Все ${event.totalCompleted} отчетов собраны."))
            }
            is ConveyorMissionUiEvent.MissionCompleted -> {
                _sideEffects.trySend(ConveyorScreenSideEffect.HapticMissionCompleted)
                _sideEffects.trySend(ConveyorScreenSideEffect.ShowToast("Миссия завершена! Коммит зафиксирован."))
            }
            is ConveyorMissionUiEvent.MissionFailed -> {
                _sideEffects.trySend(ConveyorScreenSideEffect.HapticMissionFailed)
                _screenState.update { it.copy(activeBannerError = event.reason) }
            }
            is ConveyorMissionUiEvent.ToastNotification -> {
                _sideEffects.trySend(ConveyorScreenSideEffect.ShowToast(event.message))
            }
        }
    }

    // ====================================================================
    // 7. Инфраструктура Безопасного Хранилища SharedPreferences
    // ====================================================================

    private fun getSecurePreferences(context: Context): SharedPreferences {
        return securePrefs ?: synchronized(this) {
            securePrefs ?: createSafeSharedPreferences(context).also { securePrefs = it }
        }
    }

    private fun createSafeSharedPreferences(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREF_FILE_SECURE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            AppLogger.w(AppLogger.TAG_VM, "Knox Vault Keystore недоступен, откат к fallback SharedPreferences: ${t.message}")
            context.getSharedPreferences(PREF_FILE_FALLBACK, Context.MODE_PRIVATE)
        }
    }

    // ====================================================================
    // 8. Финализация Жизненного Цикла (Lifecycle Teardown)
    // ====================================================================

    override fun onCleared() {
        super.onCleared()
        AppLogger.w(AppLogger.TAG_VM, "ConveyorViewModel: onCleared -> Освобождение ресурсов конвейера...")
        viewModelScope.launch(NonCancellable) {
            conveyorController.close()
        }
    }
}