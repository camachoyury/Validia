package com.camachoyury.validia.domain

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import com.camachoyury.validia.data.ai.LiteRtAdkModel
import com.camachoyury.validia.domain.model.InventoryItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * Herramientas del Agente de Inventario.
 *
 * [onItemsParsed] — callback invocado cuando el agente llama exitosamente a
 * [registrarEntradaInventario]. Permite al ViewModel recibir los ítems parseados
 * directamente desde la ejecución de la herramienta, sin necesidad de post-procesar
 * la respuesta textual del modelo.
 */
class InventoryTools(
    private val onItemsParsed: (List<InventoryItem>) -> Unit = {}
) {

    @Tool("Registra la entrada de nuevos productos al inventario a partir de una descripción.")
    fun registrarEntradaInventario(
        @Param("Cadena de texto en formato JSON que representa una lista de productos. Ejemplo: [{\"quantity\": 12, \"productName\": \"Coca-Cola 20oz\", \"category\": \"Beverages\"}]") jsonProductos: String
    ): String {
        return try {
            val arr = JSONArray(jsonProductos)
            val items = mutableListOf<InventoryItem>()
            val resumen = mutableListOf<String>()

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val item = InventoryItem(
                    quantity     = obj.optInt("quantity", 1),
                    productName  = obj.optString("productName", "Unknown"),
                    category     = obj.optString("category", "Food")
                )
                items.add(item)
                resumen.add("${item.quantity}x ${item.productName} (${item.category})")
            }

            // Notify the ViewModel with the typed item list
            onItemsParsed(items)

            JSONObject(mapOf(
                "status"      to "success",
                "message"     to "Inventario actualizado exitosamente offline",
                "registrados" to resumen
            )).toString()
        } catch (e: Exception) {
            JSONObject(mapOf(
                "status"  to "error",
                "message" to "No se pudo procesar el JSON de productos: ${e.message}"
            )).toString()
        }
    }

    @Tool("Consulta la cantidad de stock actual de un producto mediante su SKU.")
    fun consultarStockProducto(
        @Param("El código SKU del producto (ej. SKU-123)") sku: String
    ): String {
        val stock = if (sku.uppercase().contains("123")) 42 else 0
        return JSONObject(mapOf(
            "sku"              to sku,
            "stock_disponible" to stock,
            "estado"           to if (stock > 0) "DISPONIBLE" else "AGOTADO"
        )).toString()
    }
}

object InventoryAgentFactory {
    /**
     * @param adkModel  LiteRT adapter that implements the ADK Model interface.
     * @param tools     InventoryTools instance provided by the caller so the ViewModel
     *                  can share the [InventoryTools.onItemsParsed] callback reference.
     */
    fun createRootAgent(adkModel: LiteRtAdkModel, tools: InventoryTools): LlmAgent {
        return LlmAgent(
            name        = "inventory_agent",
            description = "Agente encargado de la gestión y recepción de inventarios offline B2B.",
            model       = adkModel,
            instruction = Instruction("""
                You are an offline inventory assistant for a store. A supplier arrives and says what products they are delivering.
                
                YOUR ONLY JOB: Extract products from the user's natural language text, build the JSON, and IMMEDIATELY call the tool 'registrarEntradaInventario'.
                
                CRITICAL RULES:
                - NEVER ask the user for more information.
                - NEVER say you cannot process the request.
                - ALWAYS extract the product names, quantities and categories yourself from the text.
                - 'category' MUST be exactly one of: "Beverages", "Food", or "Cleaning".
                - Call the tool on the FIRST response. Do not add any text before or after the tool call JSON.
                
                EXAMPLE:
                User says: "I got 12 Cokes 20oz and 4 bags of chips"
                You MUST call: registrarEntradaInventario with jsonProductos = [{"quantity":12,"productName":"Coca-Cola 20oz","category":"Beverages"},{"quantity":4,"productName":"Chips","category":"Food"}]
                
                When a user says anything about receiving products, immediately extract and call the tool.
            """.trimIndent()),
            tools = tools.generatedTools()
        )
    }
}
