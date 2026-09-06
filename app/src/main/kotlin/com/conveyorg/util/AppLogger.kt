package com.conveyorg.util

import android.util.Log

object AppLogger {
    const val TAG_APP = "ClientG:App"
    const val TAG_VM = "ClientG:VM"
    const val TAG_NET = "ClientG:Net"
    const val TAG_ENGINE = "ClientG:Engine"
    const val TAG_UI = "ClientG:UI"

    fun v(tag: String, message: String) {
        Log.v(tag, message)
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w(tag, message, throwable)
        } else {
            Log.w(tag, message)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }

    fun maskKey(key: String): String {
        val trimmed = key.trim()
        if (trimmed.length <= 8) return "******"
        return "${trimmed.take(6)}...${trimmed.takeLast(4)}"
    }
}