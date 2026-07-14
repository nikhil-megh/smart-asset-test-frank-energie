package com.frankenergie.smartasset.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.frankenergie.smartasset.model.OrderSide
import com.frankenergie.smartasset.model.OrderUpdateRequest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.LocalDateTime

@SpringBootTest
@AutoConfigureMockMvc
class MarketOverviewControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `GET market overview returns empty list when no orders`() {
        mockMvc.get("/api/market/overview")
            .andExpect {
                status { isOk() }
                jsonPath("$.quarters") { isArray() }
            }
    }

    @Test
    fun `GET market overview returns quarters with best prices`() {
        val now = LocalDateTime.now()
        val buyRequest = OrderUpdateRequest(
            orderTime = now,
            deliveryStartTime = LocalDateTime.of(2024, 6, 15, 10, 0),
            deliveryEndTime = LocalDateTime.of(2024, 6, 15, 10, 15),
            orderSide = OrderSide.BUY,
            quantity = BigDecimal("10"),
            price = BigDecimal("50.00")
        )
        mockMvc.post("/api/orderupdate") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(buyRequest)
        }

        val higherBuyRequest = buyRequest.copy(price = BigDecimal("52.00"))
        mockMvc.post("/api/orderupdate") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(higherBuyRequest)
        }

        val sellRequest = buyRequest.copy(orderSide = OrderSide.SELL, price = BigDecimal("55.00"))
        mockMvc.post("/api/orderupdate") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(sellRequest)
        }

        val lowerSellRequest = buyRequest.copy(orderSide = OrderSide.SELL, price = BigDecimal("53.00"))
        mockMvc.post("/api/orderupdate") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(lowerSellRequest)
        }

        mockMvc.get("/api/market/overview")
            .andExpect {
                status { isOk() }
                jsonPath("$.quarters") { isArray() }
            }
    }
}