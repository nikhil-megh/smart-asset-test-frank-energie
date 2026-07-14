package com.frankenergie.smartasset.service

import com.frankenergie.smartasset.client.MarketOrderClient
import com.frankenergie.smartasset.model.DeliveryPeriod
import com.frankenergie.smartasset.model.OrderSide
import com.frankenergie.smartasset.model.OrderStatus
import com.frankenergie.smartasset.model.OrderUpdateRequest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.math.BigDecimal
import java.time.LocalDateTime

@SpringBootTest
class OrderBookServiceTest {

    @Autowired
    private lateinit var orderBookService: OrderBookService

    @Autowired
    private lateinit var marketOrderClient: MarketOrderClient

    @BeforeEach
    fun setUp() {
        marketOrderClient.clear()
        // Reset underlying order book by creating fresh orders or clearing via client
    }

    @Test
    fun `buy order completely fills while sell order remains in book`() {
        val now = LocalDateTime.now()
        val start = LocalDateTime.of(2025,1,1,12,0)
        val end = start.plusMinutes(15)

        val sell = OrderUpdateRequest(
            orderTime = now,
            deliveryStartTime = start,
            deliveryEndTime = end,
            orderSide = OrderSide.SELL,
            quantity = BigDecimal("10"),
            price = BigDecimal("50")
        )

        val sellResponse = orderBookService.processOrder(sell)
        assertEquals(OrderStatus.ACCEPTED.name, sellResponse.status)

        val buy = sell.copy(
            orderSide = OrderSide.BUY,
            quantity = BigDecimal("5")
        )

        val buyResponse = orderBookService.processOrder(buy)

        // BUY order was fully executed
        assertEquals(OrderStatus.FILLED.name, buyResponse.status)

        val bestAsk = orderBookService
            .getOrderBook()
            .getBestAsk(DeliveryPeriod(start, end))

        assertNotNull(bestAsk)
        assertEquals(BigDecimal("50"), bestAsk!!.price)
        assertEquals(BigDecimal("5"), bestAsk.quantity)
    }
}