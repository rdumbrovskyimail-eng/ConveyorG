package com.conveyorg

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import com.conveyorg.presentation.ChatViewModel
import com.conveyorg.util.AppLogger
import android.graphics.Color as AndroidColor

class MainActivity : ComponentActivity() {

    private val chatViewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLogger.i(AppLogger.TAG_APP, "onCreate: Старт MainActivity (savedInstanceState=$savedInstanceState, action=${intent?.action})")

        window.setBackgroundDrawable(ColorDrawable(AndroidColor.BLACK))

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )

        super.onCreate(savedInstanceState)

        configureDisplayWindow()

        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        setContent {
            ChatGptScreen(viewModel = chatViewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AppLogger.i(AppLogger.TAG_APP, "onNewIntent: Получен Intent: action=${intent.action}, data=${intent.data}")
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            Intent.ACTION_SEND -> {
                val fileUri: Uri? = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri

                if (fileUri != null) {
                    AppLogger.i(AppLogger.TAG_APP, "handleIncomingIntent: ACTION_SEND файл: $fileUri")
                    chatViewModel.onAttachFileUri(fileUri)
                }

                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

                if (!sharedText.isNullOrBlank()) {
                    AppLogger.i(AppLogger.TAG_APP, "handleIncomingIntent: ACTION_SEND текст (${sharedText.length} символов)")
                    chatViewModel.onInputTextChanged(sharedText)
                }
                intent.action = null
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val fileUris: ArrayList<Uri>? = IntentCompat.getParcelableArrayListExtra(
                    intent,
                    Intent.EXTRA_STREAM,
                    Uri::class.java
                )

                if (!fileUris.isNullOrEmpty()) {
                    AppLogger.i(AppLogger.TAG_APP, "handleIncomingIntent: ACTION_SEND_MULTIPLE: ${fileUris.size} файлов")
                    fileUris.forEach { uri -> chatViewModel.onAttachFileUri(uri) }
                } else {
                    intent.clipData?.let { clip ->
                        AppLogger.i(AppLogger.TAG_APP, "handleIncomingIntent: ACTION_SEND_MULTIPLE через clipData: ${clip.itemCount} файлов")
                        for (i in 0 until clip.itemCount) {
                            clip.getItemAt(i).uri?.let { uri ->
                                chatViewModel.onAttachFileUri(uri)
                            }
                        }
                    }
                }
                intent.action = null
            }
        }
    }

    private fun configureDisplayWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false

        val params = window.attributes
        params.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        window.attributes = params

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false
        AppLogger.d(AppLogger.TAG_APP, "configureDisplayWindow: Edge-to-Edge и Cutout настроены")
    }
}