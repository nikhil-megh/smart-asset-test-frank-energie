package com.frankenergie.smartasset.model

import com.fasterxml.jackson.annotation.JsonProperty

data class MarketOverviewResponse(
    @JsonProperty("quarters") val quarters: List<QuarterOverview>
)