package com.frankenergie.smartasset.model

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal
import java.time.LocalDateTime

data class OrderUpdateRequest(
    @JsonProperty("order_time") val orderTime: LocalDateTime, // added field to simulate orders coming in through the day, using time.now() restricts that
    @JsonProperty("delivery_start_time") val deliveryStartTime: LocalDateTime,
    @JsonProperty("delivery_end_time") val deliveryEndTime: LocalDateTime,
    @JsonProperty("order_side") val orderSide: OrderSide,
    @JsonProperty("quantity") val quantity: BigDecimal,
    @JsonProperty("price") val price: BigDecimal
)
