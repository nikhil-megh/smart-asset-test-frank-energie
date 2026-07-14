package com.frankenergie.smartasset.model

import java.math.BigDecimal
import java.time.LocalDateTime

data class MarketOrder(
    val orderId: String,
    val groupId: String,
    val deliveryStart: LocalDateTime,
    val deliveryEnd: LocalDateTime,
    val side: OrderSide,
    val quantity: BigDecimal,
    val price: BigDecimal,
    val status: OrderStatus, // adding status to see if the order was accepted, partially filled or fully filled
    val orderTime: LocalDateTime // instead of initializing to current timestamp assigning this to order request's time to help simulate mock trade scenarios in a day
)
