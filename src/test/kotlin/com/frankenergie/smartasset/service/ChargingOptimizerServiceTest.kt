package com.frankenergie.smartasset.service

import com.frankenergie.smartasset.client.MarketOrderClient
import com.frankenergie.smartasset.client.SteeringSignalClient
import com.frankenergie.smartasset.domain.OrderBook
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
import java.time.LocalDate
import java.time.LocalDateTime

@SpringBootTest
class ChargingOptimizerServiceTest {

    @Autowired
    private lateinit var chargingOptimizerService: ChargingOptimizerService

    @Autowired
    private lateinit var marketOrderClient: MarketOrderClient

    @Autowired
    private lateinit var steeringSignalClient: SteeringSignalClient

    @BeforeEach
    fun setUp() {
        marketOrderClient.clear()
        steeringSignalClient.clear()
    }

    @Test
    fun `optimize allocates cheapest asks within group window`() {
        val orderBook = OrderBook()
        val today = LocalDate.now()
        val start = today.atTime(1, 0)
        val end = start.plusMinutes(15)
        val period = DeliveryPeriod(start, end)

        // Provide a cheap ask in the orderbook for Group A's window
        orderBook.processMarketOrder(period, OrderSide.SELL, BigDecimal("10"), BigDecimal("20.00"))

        val plan = chargingOptimizerService.optimize(orderBook, LocalDateTime.now())
        assertNotNull(plan)
        assertTrue(plan.groupAllocations.isNotEmpty())
    }

    @Test
    fun `handleMarketUpdate triggers allocation when new SELL order arrives`() {
        val orderBook = OrderBook()
        val today = LocalDate.now()
        val start = today.atTime(2, 0)
        val end = start.plusMinutes(15)
        val now = LocalDateTime.now()

        val request = OrderUpdateRequest(
            orderTime = now,
            deliveryStartTime = start,
            deliveryEndTime = end,
            orderSide = OrderSide.SELL,
            quantity = BigDecimal("5"),
            price = BigDecimal("15.00")
        )

        // Put the sell order in the book first as OrderBookService would do
        orderBook.processMarketOrder(DeliveryPeriod(start, end), OrderSide.SELL, BigDecimal("5"), BigDecimal("15.00"))

        chargingOptimizerService.handleMarketUpdate(request, OrderStatus.ACCEPTED, orderBook)

        val signals = steeringSignalClient.getAllSignals()
        assertFalse(signals.isEmpty())
    }
}