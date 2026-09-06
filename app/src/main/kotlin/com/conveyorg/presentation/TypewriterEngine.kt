package com.conveyorg.presentation

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import com.conveyorg.util.AppLogger
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class TypewriterEngine(
    private val onFrame: (thoughtDelta: String, contentDelta: String) -> Unit,
    private val onComplete: () -> Unit = {}
) {
    private val targetThought = StringBuilder()
    private val targetContent = StringBuilder()

    private var renderedThoughtLength = 0
    private var renderedContentLength = 0

    @Volatile
    private var isStreamEnded = false

    @Volatile
    private var isThinkingPhaseEnded = false

    private var animationJob: Job? = null

    @Synchronized
    fun enqueueThought(delta: String) {
        if (delta.isEmpty()) return
        targetThought.append(delta)
        AppLogger.v(AppLogger.TAG_ENGINE, "enqueueThought: +${delta.length} chars (очередь мыслей: ${targetThought.length - renderedThoughtLength})")
    }

    @Synchronized
    fun enqueueContent(delta: String) {
        if (delta.isEmpty()) return
        targetContent.append(delta)
        AppLogger.v(AppLogger.TAG_ENGINE, "enqueueContent: +${delta.length} chars (очередь контента: ${targetContent.length - renderedContentLength})")
    }

    fun markThinkingEnded() {
        AppLogger.d(AppLogger.TAG_ENGINE, "markThinkingEnded: Фаза мыслей завершена сервером")
        isThinkingPhaseEnded = true
    }

    fun markStreamEnded() {
        AppLogger.d(AppLogger.TAG_ENGINE, "markStreamEnded: Сервер закрыл SSE-поток")
        isThinkingPhaseEnded = true
        isStreamEnded = true
    }

    fun start(scope: CoroutineScope) {
        if (animationJob?.isActive == true) return
        AppLogger.d(AppLogger.TAG_ENGINE, "start: Запуск VSYNC-цикла на Dispatchers.Main.immediate")

        animationJob = scope.launch(Dispatchers.Main.immediate) {
            var lastFrameTimeNanos = SystemClock.elapsedRealtimeNanos()

            while (isActive) {
                val frameTimeNanos = awaitDisplayFrame()
                val deltaNanos = (frameTimeNanos - lastFrameTimeNanos).coerceAtLeast(1_000_000L)
                lastFrameTimeNanos = frameTimeNanos

                val deltaRatio = deltaNanos / 16_666_666f

                var thoughtSlice = ""
                var contentSlice = ""

                synchronized(this@TypewriterEngine) {
                    val thoughtQueue = targetThought.length - renderedThoughtLength
                    val contentQueue = targetContent.length - renderedContentLength

                    if (thoughtQueue > 0) {
                        val speedFactor = calculateAdaptiveSpeed(thoughtQueue, isThinkingPhaseEnded)
                        val requestedChars = max(1, ceil(speedFactor * deltaRatio).toInt())
                        val safeLen = computeSafeSliceLength(
                            text = targetThought,
                            currentLength = renderedThoughtLength,
                            requestedChars = requestedChars
                        )
                        if (safeLen > 0) {
                            thoughtSlice = targetThought.substring(renderedThoughtLength, renderedThoughtLength + safeLen)
                            renderedThoughtLength += safeLen
                        }
                    }

                    if (contentQueue > 0) {
                        val speedFactor = calculateAdaptiveSpeed(contentQueue, isStreamEnded)
                        val requestedChars = max(1, ceil(speedFactor * deltaRatio).toInt())
                        val safeLen = computeSafeSliceLength(
                            text = targetContent,
                            currentLength = renderedContentLength,
                            requestedChars = requestedChars
                        )
                        if (safeLen > 0) {
                            contentSlice = targetContent.substring(renderedContentLength, renderedContentLength + safeLen)
                            renderedContentLength += safeLen
                        }
                    }

                    if (isStreamEnded && thoughtQueue == 0 && contentQueue == 0) {
                        AppLogger.i(AppLogger.TAG_ENGINE, "TypewriterEngine: Очереди пусты, вывод завершен ($renderedThoughtLength chars мыслей, $renderedContentLength chars ответа)")
                        if (thoughtSlice.isNotEmpty() || contentSlice.isNotEmpty()) {
                            onFrame(thoughtSlice, contentSlice)
                        }
                        onComplete()
                        return@launch
                    }
                }

                if (thoughtSlice.isNotEmpty() || contentSlice.isNotEmpty()) {
                    onFrame(thoughtSlice, contentSlice)
                }
            }
        }
    }

    fun stopAndFlush() {
        AppLogger.w(AppLogger.TAG_ENGINE, "stopAndFlush: Принудительный сброс очередей")
        animationJob?.cancel()
        animationJob = null

        synchronized(this) {
            var thoughtRemaining = ""
            var contentRemaining = ""

            if (renderedThoughtLength < targetThought.length) {
                thoughtRemaining = targetThought.substring(renderedThoughtLength)
                renderedThoughtLength = targetThought.length
            }
            if (renderedContentLength < targetContent.length) {
                contentRemaining = targetContent.substring(renderedContentLength)
                renderedContentLength = targetContent.length
            }

            if (thoughtRemaining.isNotEmpty() || contentRemaining.isNotEmpty()) {
                onFrame(thoughtRemaining, contentRemaining)
            }
        }
        onComplete()
    }

    private fun calculateAdaptiveSpeed(backlog: Int, isFlushing: Boolean): Float {
        if (isFlushing) {
            return max(4f, backlog / 4f)
        }
        return when {
            backlog <= 4 -> 1.2f
            backlog <= 15 -> 2.5f
            backlog <= 60 -> 6.0f
            backlog <= 200 -> 14.0f
            backlog <= 1000 -> 30.0f
            else -> backlog / 15f
        }
    }

    private fun computeSafeSliceLength(
        text: CharSequence,
        currentLength: Int,
        requestedChars: Int
    ): Int {
        val targetLength = min(text.length, currentLength + requestedChars)
        if (targetLength >= text.length) return text.length - currentLength

        var safeEnd = targetLength
        if (Character.isHighSurrogate(text[safeEnd - 1])) {
            if (safeEnd < text.length && Character.isLowSurrogate(text[safeEnd])) {
                safeEnd++
            } else {
                safeEnd--
            }
        }
        return max(0, safeEnd - currentLength)
    }

    private suspend fun awaitDisplayFrame(): Long {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            delay(16)
            return SystemClock.elapsedRealtimeNanos()
        }

        return suspendCancellableCoroutine { continuation ->
            val callback = Choreographer.FrameCallback { frameTimeNanos ->
                if (continuation.isActive) {
                    continuation.resume(frameTimeNanos)
                }
            }
            Choreographer.getInstance().postFrameCallback(callback)
            continuation.invokeOnCancellation {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    Choreographer.getInstance().removeFrameCallback(callback)
                } else {
                    Handler(Looper.getMainLooper()).post {
                        Choreographer.getInstance().removeFrameCallback(callback)
                    }
                }
            }
        }
    }
}