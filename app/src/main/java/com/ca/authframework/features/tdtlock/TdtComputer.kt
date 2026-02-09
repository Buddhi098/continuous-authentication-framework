package com.ca.authframework.features.tdtlock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Computes TDT (Trust Decision Threshold) accuracy using a non-overlapping window approach.
 *
 * This class accumulates authentication results and computes a window-based trust metric. Each
 * window contains [TdtLockConfig.windowSize] results. A window is considered "authenticated" if the
 * fraction of authenticated results meets or exceeds [TdtLockConfig.windowMajorityThreshold].
 *
 * TDT Accuracy = (authenticated windows) / (total windows)
 */
class TdtComputer(private val config: TdtLockConfig = TdtLockConfig()) {

    private val authWindow = ArrayDeque<Boolean>(config.windowSize)

    private val _totalWindows = MutableStateFlow(0)
    val totalWindows: StateFlow<Int> = _totalWindows.asStateFlow()

    private val _authenticatedWindows = MutableStateFlow(0)
    val authenticatedWindows: StateFlow<Int> = _authenticatedWindows.asStateFlow()

    private val _tdtAccuracy = MutableStateFlow(0f)
    val tdtAccuracy: StateFlow<Float> = _tdtAccuracy.asStateFlow()

    /**
     * Add a new authentication result to the window. When window is full, compute window decision
     * and update TDT accuracy.
     */
    @Synchronized
    fun addResult(isAuthenticated: Boolean) {
        authWindow.addLast(isAuthenticated)

        if (authWindow.size == config.windowSize) {
            val authenticatedCount = authWindow.count { it }
            val fraction = authenticatedCount.toFloat() / config.windowSize
            val isAuthenticatedWindow = fraction >= config.windowMajorityThreshold

            _totalWindows.value++

            if (isAuthenticatedWindow) {
                _authenticatedWindows.value++
            }

            _tdtAccuracy.value =
                    _authenticatedWindows.value.toFloat() / _totalWindows.value.toFloat()

            // Non-overlapping window → clear
            authWindow.clear()
        }
    }

    /** Reset all state to initial values. */
    @Synchronized
    fun reset() {
        authWindow.clear()
        _totalWindows.value = 0
        _authenticatedWindows.value = 0
        _tdtAccuracy.value = 0f
    }
}
