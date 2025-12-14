package com.ca.continuousauth.utils

import android.util.Log
import com.ca.continuousauth.config.AuthConfigManager

object Logger {

    private const val TAG = "CAFramework"

    // Use the config flag directly
    private var enabled = AuthConfigManager.config.enableLogging

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun d(msg: String) {
        if (enabled) Log.d(TAG, msg)
    }

    fun e(msg: String, ex: Throwable? = null) {
        if (enabled) Log.e(TAG, msg, ex)
    }
}
