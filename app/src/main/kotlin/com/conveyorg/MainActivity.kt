package com.conveyorg

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import com.conveyorg.presentation.ConveyorMissionScreen
import com.conveyorg.presentation.ConveyorViewModel
import com.conveyorg.util.AppLogger
import android.graphics.Color as AndroidColor

class MainActivity : ComponentActivity() {

    // Инициализация ViewModel конвейера с интеграцией Knox Vault и SavedStateHandle
    private val conveyorViewModel: ConveyorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLogger.i(
            AppLogger.TAG_APP,
            "onCreate: Запуск MainActivity -> Терминал Mission Control (savedInstanceState=$savedInstanceState, action=${intent?.action})"
        )

        // Черная аппаратная подложка для исключения белых вспышек до первого кадра Compose на AMOLED
        window.setBackgroundDrawable(ColorDrawable(AndroidColor.BLACK))

        // Полноэкранный режим Edge-to-Edge для прозрачных системных баров
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )

        super.onCreate(savedInstanceState)

        // Тонкая настройка вырезов экрана и системных инсетов
        configureDisplayWindow()

        // Обработка входящего текста задачи из внешних приложений
        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        // МГНОВЕННЫЙ ЗАПУСК НОВОГО ЭКРАНА УПРАВЛЕНИЯ КОНВЕЙЕРОМ
        setContent {
            ConveyorMissionScreen(viewModel = conveyorViewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AppLogger.i(AppLogger.TAG_APP, "onNewIntent: Получен внешний Intent: action=${intent.action}")
        handleIncomingIntent(intent)
    }

    /**
     * Перехват системного шаринга текста: текст автоматически заполняет поле задачи конвейера.
     */
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        if (intent.action == Intent.ACTION_SEND) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

            if (!sharedText.isNullOrBlank()) {
                AppLogger.i(AppLogger.TAG_APP, "handleIncomingIntent: Перехвачен текст задачи (${sharedText.length} символов)")
                conveyorViewModel.onObjectiveInputChanged(sharedText)
            }
            intent.action = null
        }
    }

    /**
     * Конфигурация выреза камеры и системных окон под экраны 120 Гц AMOLED и режим Samsung DeX.
     */
    private fun configureDisplayWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false

        // Использование области вокруг выреза фронтальной камеры (Cutout)
        val params = window.attributes
        params.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        window.attributes = params

        // Принудительные темные системные иконки в строке состояния
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false

        AppLogger.d(AppLogger.TAG_APP, "configureDisplayWindow: Edge-to-Edge и Cutout успешно сконфигурированы.")
    }
}