package com.ca.authframework.features.tdtlock

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * TDT Lock Feature - Plug-and-play facade for TDT-based app locking.
 *
 * This feature can be enabled or disabled independently. When enabled:
 * - Processes authentication results to compute TDT accuracy
 * - Automatically locks/unlocks based on configured thresholds
 *
 * Usage:
 * ```
 * val feature = TdtLockFeature(config)
 * feature.enable()
 *
 * // Process each authentication result
 * feature.processResult(isAuthenticated = true)
 *
 * // Observe lock state
 * feature.isLocked.collect { locked -> /* update UI */ }
 *
 * // Disable when not needed
 * feature.disable()
 * ```
 */
class TdtLockFeature(private val config: TdtLockConfig = TdtLockConfig()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    val computer = TdtComputer(config)
    private val lockController: TdtLockController = DefaultTdtLockController(config)

    /** Current lock state (always false if feature is disabled) */
    val isLocked: StateFlow<Boolean> = lockController.isLocked

    /** TDT accuracy from computer */
    val tdtAccuracy: StateFlow<Float> = computer.tdtAccuracy

    /** Total windows computed */
    val totalWindows: StateFlow<Int> = computer.totalWindows

    /** Authenticated windows count */
    val authenticatedWindows: StateFlow<Int> = computer.authenticatedWindows

    init {
        // Auto-evaluate lock when TDT accuracy changes
        scope.launch {
            combine(computer.tdtAccuracy, computer.totalWindows, _isEnabled) {
                    accuracy,
                    windows,
                    enabled ->
                Triple(accuracy, windows, enabled)
            }
                    .collect { (accuracy, windows, enabled) ->
                        if (enabled) {
                            lockController.evaluateTdt(accuracy, windows)
                        }
                    }
        }
    }

    /** Enable the TDT lock feature. */
    fun enable() {
        _isEnabled.value = true
    }

    /** Disable the TDT lock feature. Resets lock state but preserves TDT statistics. */
    fun disable() {
        _isEnabled.value = false
        lockController.reset()
    }

    /** Process an authentication result. Only processes if feature is enabled. */
    fun processResult(isAuthenticated: Boolean) {
        if (_isEnabled.value) {
            computer.addResult(isAuthenticated)
        }
    }

    /** Reset all state (TDT statistics and lock state). */
    fun reset() {
        computer.reset()
        lockController.reset()
    }
}
