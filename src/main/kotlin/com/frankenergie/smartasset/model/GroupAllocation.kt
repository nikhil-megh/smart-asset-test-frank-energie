package com.frankenergie.smartasset.model

import java.math.BigDecimal

data class GroupAllocation(
    val groupId: String,
    val allocations: List<QuarterAllocation>,
    val totalMWh: BigDecimal,
    val totalCost: BigDecimal
)
