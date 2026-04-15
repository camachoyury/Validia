package com.camachoyury.validia.presentation.inventory.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.camachoyury.validia.presentation.inventory.DebugState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.camachoyury.validia.domain.model.InventoryItem
import com.camachoyury.validia.presentation.inventory.InventoryUiState
import com.camachoyury.validia.presentation.inventory.InventoryViewModel

// ─────────────────────────────────────────────────────────────────────────────
// Route — single contact point with the ViewModel (State Hoisting)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SmartInventoryRoute(
    viewModel: InventoryViewModel,
    modifier: Modifier = Modifier
) {
    val rawText by viewModel.rawText.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val debugState by viewModel.debugState.collectAsState()

    SmartInventoryScreen(
        rawText = rawText,
        uiState = uiState,
        debugState = debugState,
        onRawTextChange = viewModel::onTextChanged,
        onProcessClick = viewModel::processText,
        onResetClick = viewModel::reset,
        onRetryClick = viewModel::retryInitialization,
        modifier = modifier
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Pure Screen — state and lambdas only, no ViewModel
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SmartInventoryScreen(
    rawText: String,
    uiState: InventoryUiState,
    debugState: DebugState?,
    onRawTextChange: (String) -> Unit,
    onProcessClick: (String) -> Unit,
    onResetClick: () -> Unit,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Always-visible header
        Text(
            text = "Smart Inventory Assistant",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Gemma 4 E2B · LiteRT · 100% offline",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(4.dp))

        when (uiState) {

            // ── Loading Gemma into memory — blocks the UI ─────────────────────
            InventoryUiState.InitializingModel, InventoryUiState.Idle -> {
                ModelLoadingSection()
            }

            // ── Model ready: show input ───────────────────────────────────────
            InventoryUiState.Ready -> {
                InputSection(
                    rawText = rawText,
                    isEnabled = true,
                    onRawTextChange = onRawTextChange,
                    onProcessClick = onProcessClick
                )
            }

            // ── Inference running ─────────────────────────────────────────────
            InventoryUiState.Processing -> {
                InputSection(
                    rawText = rawText,
                    isEnabled = false,
                    onRawTextChange = onRawTextChange,
                    onProcessClick = onProcessClick
                )
                InferenceLoadingSection()
            }

            // ── Successful results ────────────────────────────────────────────
            is InventoryUiState.Success -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${uiState.items.size} products detected",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(onClick = onResetClick) { Text("New entry") }
                }

                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(uiState.items) { item -> InventoryItemCard(item) }
                }
            }

            // ── Error at any point in the flow ───────────────────────────────
            is InventoryUiState.Error -> {
                ErrorSection(
                    message = uiState.message,
                    onRetry = onRetryClick
                )
            }
        }

        // ── Debug panel — always visible when data is available ──────────────
        if (debugState != null) {
            Spacer(modifier = Modifier.height(8.dp))
            DebugPanel(debugState = debugState)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Componentes internos
// ─────────────────────────────────────────────────────────────────────────────

/** Loader shown while LiteRT initializes Gemma in memory. */
@Composable
private fun ModelLoadingSection() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(40.dp))
            Text(
                text = "Loading AI model...",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Gemma 4 E2B is loading into memory.\nThis only happens the first time you open the app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Inference indicator while LlmInference.generateResponse() is processing. */
@Composable
private fun InferenceLoadingSection() {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircularProgressIndicator()
            Text(
                text = "Gemma is analyzing your input...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** TextField for pasting the supplier text + process button. */
@Composable
private fun InputSection(
    rawText: String,
    isEnabled: Boolean,
    onRawTextChange: (String) -> Unit,
    onProcessClick: (String) -> Unit
) {
    OutlinedTextField(
        value = rawText,
        onValueChange = onRawTextChange,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp),
        label = { Text("Supplier text") },
        placeholder = { Text("E.g.: 12 Cokes, 4 Taragüi 500g, 2 Ala detergent...") },
        enabled = isEnabled,
        shape = RoundedCornerShape(12.dp)
    )

    Button(
        onClick = { onProcessClick(rawText) },
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        enabled = isEnabled && rawText.isNotBlank(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text("Process Inventory", style = MaterialTheme.typography.labelLarge)
    }
}

/** Error card with retry option. */
@Composable
private fun ErrorSection(message: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Something went wrong",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
                            TextButton(onClick = onRetry) {
                Text("Retry", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Result card for a single inventory item. */
@Composable
private fun InventoryItemCard(item: InventoryItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "x${item.quantity}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = item.productName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
            }
            CategoryChip(category = item.category)
        }
    }
}

/**
 * Debug panel showing Gemma 4 E2B inference activity in real time.
 * Visible at the bottom of the screen during and after each inference.
 */
@Composable
private fun DebugPanel(debugState: DebugState) {
    val bgColor = Color(0xFF0D1117) // console-style background

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "GEMMA 4 E2B — ON-DEVICE INFERENCE",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF58A6FF),
                    fontWeight = FontWeight.Bold
                )
                if (debugState.isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = Color(0xFF58A6FF)
                    )
                }
            }

            HorizontalDivider(color = Color(0xFF30363D))

            // Stats: tokens and time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "tokens: ${debugState.tokenCount}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFF8B949E)
                )
                Text(
                    text = if (debugState.isCompleted)
                        "✅ completed in ${debugState.elapsedMs}ms"
                    else
                        "⏱ ${debugState.elapsedMs}ms",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (debugState.isCompleted) Color(0xFF3FB950) else Color(0xFF8B949E)
                )
            }
        }
    }
}

/** Chip with semantic color per category. */
@Composable
private fun CategoryChip(category: String) {
    val (bg, fg) = when (category) {
        "Beverages" -> Color(0xFFBBDEFB) to Color(0xFF0D47A1)
        "Food"      -> Color(0xFFC8E6C9) to Color(0xFF1B5E20)
        "Cleaning"  -> Color(0xFFFFF9C4) to Color(0xFFF57F17)
        else        -> Color(0xFFE0E0E0) to Color(0xFF424242)
    }
    Surface(color = bg, shape = RoundedCornerShape(50.dp)) {
        Text(
            text = category,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = fg
        )
    }
}
