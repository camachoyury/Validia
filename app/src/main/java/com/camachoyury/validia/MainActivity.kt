package com.camachoyury.validia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.camachoyury.validia.domain.model.InventoryItem
import com.camachoyury.validia.presentation.inventory.InventoryUiState
import com.camachoyury.validia.presentation.inventory.ui.SmartInventoryScreen
import com.camachoyury.validia.ui.theme.ValidiaTheme

/**
 * ═══════════════════════════════════════════════════════════════
 * PASO 13 — Conecta todo en MainActivity
 * ═══════════════════════════════════════════════════════════════
 *
 * Por ahora, la UI se muestra con datos hardcodeados para que puedas
 * ver el resultado final mientras implementás los pasos anteriores.
 *
 * Cuando hayas completado los Pasos 6 al 12, reemplazá el contenido
 * de setContent por el wiring real con el ViewModel:
 *
 *   // TODO Paso 13.1 — Declarar el nombre del modelo
 *   private val modelAssetName = "gemma-4-E2B-it.litertlm"
 *
 *   // TODO Paso 13.2 — Construir el grafo de dependencias manual
 *   private val viewModel: InventoryViewModel by viewModels {
 *       InventoryViewModel.Factory(
 *           repository = LocalInventoryRepository(
 *               engine = LiteRtInferenceEngine(applicationContext)
 *           ),
 *           modelPath = modelAssetName
 *       )
 *   }
 *
 *   // TODO Paso 13.3 — Reemplazar SmartInventoryScreen hardcodeada por:
 *   SmartInventoryRoute(
 *       viewModel = viewModel,
 *       modifier  = Modifier.padding(innerPadding)
 *   )
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ValidiaTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->

                    // UI hardcodeada para que el proyecto compile y corra
                    // mientras completás los pasos del codelab.
                    // ⬇️ Reemplazá esto en el Paso 13 con SmartInventoryRoute(viewModel)
                    SmartInventoryScreen(
                        rawText    = "",
                        uiState    = InventoryUiState.Success(
                            items = listOf(
                                InventoryItem(12, "Coca-Cola 600ml", "Beverages"),
                                InventoryItem(6,  "Taragüi 500g",   "Food"),
                                InventoryItem(4,  "Ala Detergent",  "Cleaning"),
                            )
                        ),
                        debugState = null,
                        onRawTextChange = {},
                        onProcessClick  = {},
                        onResetClick    = {},
                        onRetryClick    = {},
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}
