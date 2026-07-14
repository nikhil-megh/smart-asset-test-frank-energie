package com.frankenergie.smartasset.model

import java.math.BigDecimal
import java.time.LocalDateTime

data class SteeringSignal(
    val groupId: String,
    val quarterStart: LocalDateTime,
    val quarterEnd: LocalDateTime,
    val chargePowerMW: BigDecimal,
    val time: LocalDateTime // instead of initializing to current timestamp assigning this to order request's time to help simulate mock trade scenarios in a day
)
