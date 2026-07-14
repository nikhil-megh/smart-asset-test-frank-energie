package com.frankenergie.smartasset.model

import java.math.BigDecimal
import java.time.LocalDateTime

data class ExecutedClientPurchase (
    val groupId: String,
    val deliveryStart: LocalDateTime,
    val deliveryEnd: LocalDateTime,
    val quantity: BigDecimal,
    val pricePerMWh: BigDecimal,
    val orderTime: LocalDateTime
) {
    val totalCost: BigDecimal get() = quantity * pricePerMWh
}