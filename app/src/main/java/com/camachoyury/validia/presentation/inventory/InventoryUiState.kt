package com.camachoyury.validia.presentation.inventory

import com.camachoyury.validia.domain.model.InventoryItem

sealed interface InventoryUiState {
    /** Initial state before the model is loaded. */
    data object Idle : InventoryUiState

    /**
     * The LiteRT engine is loading Gemma 4 E2B from local storage.
     * The UI shows a blocking loader — without the model there is no functionality.
     */
    data object InitializingModel : InventoryUiState

    /**
     * Model loaded and ready in memory.
     * The UI enables the TextField and the process button.
     */
    data object Ready : InventoryUiState

    /** LlmInference is running on-device inference. */
    data object Processing : InventoryUiState

    /** Successful inference with the list of extracted and classified items. */
    data class Success(val items: List<InventoryItem>) : InventoryUiState

    /** Error at any point in the flow (initialization, inference or JSON parsing). */
    data class Error(val message: String) : InventoryUiState
}
