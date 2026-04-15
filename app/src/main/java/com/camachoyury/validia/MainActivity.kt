package com.camachoyury.validia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.camachoyury.validia.data.ai.LiteRtInferenceEngine
import com.camachoyury.validia.data.litert.LocalInventoryRepository
import com.camachoyury.validia.presentation.inventory.InventoryViewModel
import com.camachoyury.validia.presentation.inventory.ui.SmartInventoryRoute
import com.camachoyury.validia.ui.theme.ValidiaTheme

class MainActivity : ComponentActivity() {

    /**
     * Model file name in assets/.
     * [LiteRtInferenceEngine] copies it to filesDir on the first launch.
     * File location: app/src/main/assets/gemma-4-E2B-it.litertlm
     */
    private val modelAssetName = "gemma-4-E2B-it.litertlm"

    /**
     * Manual dependency graph (no Hilt).
     * [LiteRtInferenceEngine] uses LiteRT LlmInference with the .litertlm from assets/.
     */
    private val viewModel: InventoryViewModel by viewModels {
        InventoryViewModel.Factory(
            repository = LocalInventoryRepository(
                engine = LiteRtInferenceEngine(applicationContext)
            ),
            modelPath = modelAssetName
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ValidiaTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SmartInventoryRoute(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}
