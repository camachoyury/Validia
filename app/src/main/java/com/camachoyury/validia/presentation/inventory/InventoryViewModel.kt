package com.camachoyury.validia.presentation.inventory

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.camachoyury.validia.data.litert.LocalInventoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class InventoryViewModel(
    private val repository: LocalInventoryRepository,
    private val modelPath: String
) : ViewModel() {

    private val _uiState = MutableStateFlow<InventoryUiState>(InventoryUiState.Idle)
    val uiState: StateFlow<InventoryUiState> = _uiState.asStateFlow()

    private val _rawText = MutableStateFlow("")
    val rawText: StateFlow<String> = _rawText.asStateFlow()

    private val _debugState = MutableStateFlow<DebugState?>(null)
    val debugState: StateFlow<DebugState?> = _debugState.asStateFlow()

    init {
        // Load the model into memory as soon as the ViewModel is created.
        initializeModel()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LiteRT engine initialization
    // ─────────────────────────────────────────────────────────────────────────

    private fun initializeModel() {
        viewModelScope.launch {
            _uiState.value = InventoryUiState.InitializingModel
            try {
                repository.initializeModel(modelPath)
                _uiState.value = InventoryUiState.Ready
            } catch (e: Exception) {
                _uiState.value = InventoryUiState.Error(
                    "Failed to load the model. Verify the file exists at: $modelPath\n(${e.message})"
                )
            }
        }
    }

    fun retryInitialization() = initializeModel()

    // ─────────────────────────────────────────────────────────────────────────
    // On-device inference
    // ─────────────────────────────────────────────────────────────────────────

    fun onTextChanged(newText: String) {
        _rawText.value = newText
        if (_uiState.value is InventoryUiState.Error) {
            _uiState.value = InventoryUiState.Ready
        }
    }

    fun processText(rawText: String) {
        viewModelScope.launch {
            _uiState.value = InventoryUiState.Processing

            val startMs = SystemClock.elapsedRealtime()
            _debugState.value = DebugState(isRunning = true)

            try {
                val items = repository.parseInventoryText(rawText) { _ ->
                    val current = _debugState.value ?: DebugState()
                    _debugState.value = current.copy(
                        tokenCount = current.tokenCount + 1,
                        elapsedMs = SystemClock.elapsedRealtime() - startMs
                    )
                }

                val totalMs = SystemClock.elapsedRealtime() - startMs
                _debugState.value = _debugState.value?.copy(
                    isRunning = false,
                    isCompleted = true,
                    elapsedMs = totalMs
                )
                _uiState.value = InventoryUiState.Success(items)

            } catch (e: Exception) {
                _debugState.value = _debugState.value?.copy(isRunning = false)
                _uiState.value = InventoryUiState.Error(
                    e.message ?: "Error during local inference."
                )
            }
        }
    }

    fun reset() {
        _rawText.value = ""
        _uiState.value = InventoryUiState.Ready
        _debugState.value = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Resource release — critical to avoid memory leaks
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        // Releases native Engine memory when the ViewModel is destroyed.
        repository.closeModel()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Factory — manual wiring without Hilt
    // ─────────────────────────────────────────────────────────────────────────

    class Factory(
        private val repository: LocalInventoryRepository,
        private val modelPath: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            InventoryViewModel(repository, modelPath) as T
    }
}
