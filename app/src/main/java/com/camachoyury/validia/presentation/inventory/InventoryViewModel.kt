package com.camachoyury.validia.presentation.inventory

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.camachoyury.validia.data.ai.LiteRtAdkModel
import com.camachoyury.validia.data.litert.LocalInventoryRepository
import com.camachoyury.validia.domain.InventoryAgentFactory
import com.camachoyury.validia.domain.InventoryTools
import com.camachoyury.validia.domain.model.InventoryItem
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

class InventoryViewModel(
    private val repository: LocalInventoryRepository,
    private val modelPath: String
) : ViewModel() {

    companion object {
        private const val TAG = "InventoryViewModel"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ADK wiring
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Callback-based bridge: ADK tool execution fires this lambda when
     * [InventoryTools.registrarEntradaInventario] successfully parses the JSON.
     * The ViewModel stores the result and resolves the UI state.
     */
    private val inventoryTools = InventoryTools { items ->
        Log.d(TAG, "onItemsParsed fired — ${items.size} items received from ADK tool")
        resolveWithItems(items)
    }

    private val adkModel = LiteRtAdkModel(repository.engine)

    private val agent = InventoryAgentFactory.createRootAgent(
        adkModel = adkModel,
        tools    = inventoryTools
    )

    private val runner = InMemoryRunner(
        agent          = agent,
        sessionService = InMemorySessionService()
    )

    // ─────────────────────────────────────────────────────────────────────────
    // UI State
    // ─────────────────────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow<InventoryUiState>(InventoryUiState.Idle)
    val uiState: StateFlow<InventoryUiState> = _uiState.asStateFlow()

    private val _rawText = MutableStateFlow("")
    val rawText: StateFlow<String> = _rawText.asStateFlow()

    private val _debugState = MutableStateFlow<DebugState?>(null)
    val debugState: StateFlow<DebugState?> = _debugState.asStateFlow()

    /** Tracks whether the UI was already resolved (prevents double-resolution). */
    @Volatile private var resolved = false

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    init { initializeModel() }

    private fun initializeModel() {
        viewModelScope.launch {
            _uiState.value = InventoryUiState.InitializingModel
            try {
                repository.initializeModel(modelPath)
                _uiState.value = InventoryUiState.Ready
            } catch (e: Exception) {
                _uiState.value = InventoryUiState.Error(
                    "Failed to load the model. Verify: $modelPath\n(${e.message})"
                )
            }
        }
    }

    fun retryInitialization() = initializeModel()

    // ─────────────────────────────────────────────────────────────────────────
    // User input
    // ────────────────────────────────────────────────────────────────────���────

    fun onTextChanged(newText: String) {
        _rawText.value = newText
        if (_uiState.value is InventoryUiState.Error) _uiState.value = InventoryUiState.Ready
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ADK agent execution
    //
    // Design:
    //  1. runner.runAsync() drives the LiteRT ↔ ADK loop
    //  2. The flow is collected with takeWhile { !resolved } to auto-cancel
    //     once resolveWithItems() or resolveWithError() is called
    //  3. resolveWithItems() is called from the InventoryTools callback
    //     (inside the ADK runner, BEFORE turnComplete)
    //  4. If the runner finishes without the tool being called (turnComplete
    //     fires but resolved is still false), we show an informative error
    // ─────────────────────────────────────────────────────────────────────────

    fun processText(rawText: String) {
        if (rawText.isBlank()) return

        resolved = false
        _uiState.value  = InventoryUiState.Processing
        _debugState.value = DebugState(isRunning = true)

        val startMs = SystemClock.elapsedRealtime()

        viewModelScope.launch {
            try {
                runner.runAsync(
                    userId     = "store-manager",
                    sessionId  = "b2b-session-${System.currentTimeMillis()}",   // fresh session avoids stale history
                    newMessage = Content(
                        role  = Role.USER,
                        parts = listOf(Part(text = rawText))
                    )
                )
                .catch { e ->
                    Log.e(TAG, "ADK runner error: ${e.message}", e)
                    resolveWithError(e.message ?: "ADK runner error.")
                }
                .takeWhile { !resolved }          // stop collecting once we have a result
                .collect { event ->
                    // Track progress in debug panel
                    val elapsed = SystemClock.elapsedRealtime() - startMs
                    _debugState.value = (_debugState.value ?: DebugState()).copy(
                        tokenCount = (_debugState.value?.tokenCount ?: 0) + 1,
                        elapsedMs  = elapsed
                    )

                    Log.d(TAG, "ADK event — turnComplete=${event.turnComplete} " +
                               "functionCalls=${event.functionCalls().size} " +
                               "functionResponses=${event.functionResponses().size}")

                    // If the runner signals the turn is done but no tool was called
                    if (event.turnComplete && !resolved) {
                        val text = event.content?.parts
                            ?.mapNotNull { it.text }?.joinToString(" ")?.trim().orEmpty()
                        resolveWithError(
                            text.ifBlank {
                                "The agent did not register any products.\nTry: \"I received 12 Cokes and 5 bags of chips\""
                            }
                        )
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error: ${e.message}", e)
                resolveWithError(e.message ?: "Unexpected error.")
            } finally {
                val totalMs = SystemClock.elapsedRealtime() - startMs
                _debugState.value = _debugState.value?.copy(
                    isRunning   = false,
                    isCompleted = true,
                    elapsedMs   = totalMs
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Resolution helpers — both are idempotent (safe to call from any thread)
    // ─────────────────────────────────────────────────────────────────────────

    private fun resolveWithItems(items: List<InventoryItem>) {
        if (resolved) return
        resolved = true
        Log.d(TAG, "resolveWithItems: ${items.size} items")
        _uiState.value = InventoryUiState.Success(items)
    }

    private fun resolveWithError(message: String) {
        if (resolved) return
        resolved = true
        Log.d(TAG, "resolveWithError: $message")
        _uiState.value = InventoryUiState.Error(message)
    }

    // ─────────────────────────────────────────────────────────────────────────

    fun reset() {
        resolved = false
        _rawText.value    = ""
        _uiState.value    = InventoryUiState.Ready
        _debugState.value = null
    }

    override fun onCleared() {
        super.onCleared()
        repository.closeModel()
    }

    class Factory(
        private val repository: LocalInventoryRepository,
        private val modelPath: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            InventoryViewModel(repository, modelPath) as T
    }
}
