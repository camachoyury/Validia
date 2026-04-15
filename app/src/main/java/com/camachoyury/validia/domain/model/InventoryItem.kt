package com.camachoyury.validia.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class InventoryItem(
    val quantity: Int,
    val productName: String,
    val category: String // "Bebidas", "Alimentos", "Limpieza"
)
