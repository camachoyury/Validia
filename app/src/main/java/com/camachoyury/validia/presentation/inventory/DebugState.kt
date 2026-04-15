package com.camachoyury.validia.presentation.inventory

/**
 * State for the debug panel shown at the bottom of the screen
 * during and after a Gemma 4 E2B inference.
 *
 * @param isRunning    True while Gemma is generating tokens.
 * @param tokenCount   Number of chunks received from Flow<Message>.
 * @param elapsedMs    Elapsed time in ms (updates during and at completion).
 * @param isCompleted  True when inference finished successfully.
 */
data class DebugState(
    val isRunning: Boolean = false,
    val tokenCount: Int = 0,
    val elapsedMs: Long = 0L,
    val isCompleted: Boolean = false
)
