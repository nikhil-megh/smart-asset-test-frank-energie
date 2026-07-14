package com.frankenergie.smartasset.domain

import com.frankenergie.smartasset.model.DeliveryPeriod
import com.frankenergie.smartasset.model.OrderSide
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class OrderBookTest {

    private lateinit var orderBook: OrderBook
    private lateinit var period: DeliveryPeriod

    @BeforeEach
    fun setUp() {
        orderBook = OrderBook()
        period = DeliveryPeriod(
            LocalDateTime.of(2024, 1, 15, 10, 0),
            LocalDateTime.of(2024, 1, 15, 10, 15)
        )
    }

    @Test
    fun `buy order added to empty book`() {
        val matched = orderBook.processMarketOrder(period, OrderSide.BUY, BigDecimal("10"), BigDecimal("50.00"))

        assertEquals(BigDecimal.ZERO, matched)
        assertEquals(BigDecimal("10"), orderBook.getBids(period)[BigDecimal("50.00")])
        assertTrue(orderBook.getAsks(period).isEmpty())
    }

    @Test
    fun `sell order added to empty book`() {
        val matched = orderBook.processMarketOrder(period, OrderSide.SELL, BigDecimal("10"), BigDecimal("55.00"))

        assertEquals(BigDecimal.ZERO, matched)
        assertEquals(BigDecimal("10"), orderBook.getAsks(period)[BigDecimal("55.00")])
        assertTrue(orderBook.getBids(period).isEmpty())
    }

    @Test
    fun `buy order does not match higher priced sell order`() {
        orderBook.processMarketOrder(period, OrderSide.SELL, BigDecimal("10"), BigDecimal("55.00"))
        val matched = orderBook.processMarketOrder(period, OrderSide.BUY, BigDecimal("5"), BigDecimal("50.00"))

        assertEquals(BigDecimal.ZERO, matched)
        assertEquals(BigDecimal("10"), orderBook.getAsks(period)[BigDecimal("55.00")])
        assertEquals(BigDecimal("5"), orderBook.getBids(period)[BigDecimal("50.00")])
    }

    @Test
    fun `buy order fully matches equal priced sell order`() {
        orderBook.processMarketOrder(period, OrderSide.SELL, BigDecimal("10"), BigDecimal("50.00"))
        val matched = orderBook.processMarketOrder(period, OrderSide.BUY, BigDecimal("10"), BigDecimal("50.00"))

        assertEquals(BigDecimal("10"), matched)
        assertTrue(orderBook.getAsks(period).isEmpty())
        assertTrue(orderBook.getBids(period).isEmpty())
    }

    @Test
    fun `buy order fully matches lower priced sell order`() {
        orderBook.processMarketOrder(period, OrderSide.SELL, BigDecimal("10"), BigDecimal("48.00"))
        val matched = orderBook.processMarketOrder(period, OrderSide.BUY, BigDecimal("10"), BigDecimal("50.00"))

        assertEquals(BigDecimal("10"), matched)
        assertTrue(orderBook.getAsks(period).isEmpty())
        assertTrue(orderBook.getBids(period).isEmpty())
    }

    @Test
    fun `buy order partially matches sell order - remaining added to bids`() {
        orderBook.processMarketOrder(period, OrderSide.SELL, BigDecimal("10"), BigDecimal("50.00"))
        val matched = orderBook.processMarketOrder(period, OrderSide.BUY, BigDecimal("15"), BigDecimal("50.00"))

        assertEquals(BigDecimal("10"), matched)
        assertTrue(orderBook.getAsks(period).isEmpty())
        assertEquals(BigDecimal("5"), orderBook.getBids(period)[BigDecimal("50.00")])
    }

    @Test
    fun `buy order partially fills sell order - remaining stays in asks`() {
        orderBook.processMarketOrder(period, OrderSide.SELL, BigDecimal("15"), BigDecimal("50.00"))
        val matched = orderBook.processMarketOrder(period, OrderSide.BUY, BigDecimal("10"), BigDecimal("50.00"))

        assertEquals(BigDecimal("10"), matched)
        assertEquals(BigDecimal("5"), orderBook.getAsks(period)[BigDecimal("50.00")])
        assertTrue(orderBook.getBids(period).isEmpty())
    }

    @Test
    fun `sell order fully matches higher priced buy order`() {
        orderBook.processMarketOrder(period, OrderSide.BUY, BigDecimal("10"), BigDecimal("55.00"))
        val matched = orderBook.processMarketOrder(period, OrderSide.SELL, BigDecimal("10"), BigDecimal("50.00"))

        assertEquals(BigDecimal("10"), matched)
        assertTrue(orderBook.getBids(period).isEmpty())
        assertTrue(orderBook.getAsks(period).isEmpty())
    }

    @Test
    fun `sell order does not match lower priced buy order`() {
        orderBook.processMarketOrder(period, OrderSide.BUY, BigDecimal("10"), BigDecimal("50.00"))
        val matched = orderBook.processMarketOrder(period, OrderSide.SELL, BigDecimal("5"), BigDecimal("55.00"))

        assertEquals(BigDecimal.ZERO, matched)
        assertEquals(BigDecimal("10"), orderBook.getBids(period)[BigDecimal("50.00")])
        assertEquals(BigDecimal("5"), orderBook.getAsks(period)[BigDecimal("55.00")])
    }

    @Test
    fun `matchOrder with non-executed transaction does not add remaining to book`() {
        orderBook.processMarketOrder(period, OrderSide.SELL, BigDecimal("5"), BigDecimal("50.00"))

        val result = orderBook.matchOrder(
            period, OrderSide.BUY, BigDecimal("10"), BigDecimal("50.00"), isExecutedTransaction = false
        )

        assertEquals(BigDecimal("5"), result.matchedQuantity)
        assertEquals(BigDecimal("5"), result.remainingQuantity)
        assertEquals(1, result.matchedQuantityPrices.size)
        // Ensure remaining was NOT added to bids because isExecutedTransaction is false
        assertTrue(orderBook.getBids(period).isEmpty())
    }

    @Test
    fun `getBestBid and getBestAsk return correct levels`() {
        orderBook.processMarketOrder(period, OrderSide.BUY, BigDecimal("5"), BigDecimal("50.00"))
        orderBook.processMarketOrder(period, OrderSide.BUY, BigDecimal("3"), BigDecimal("52.00"))
        orderBook.processMarketOrder(period, OrderSide.SELL, BigDecimal("5"), BigDecimal("55.00"))
        orderBook.processMarketOrder(period, OrderSide.SELL, BigDecimal("3"), BigDecimal("53.00"))

        val bestBid = orderBook.getBestBid(period)!!
        val bestAsk = orderBook.getBestAsk(period)!!

        assertEquals(BigDecimal("52.00"), bestBid.price)
        assertEquals(BigDecimal("3"), bestBid.quantity)
        assertEquals(BigDecimal("53.00"), bestAsk.price)
        assertEquals(BigDecimal("3"), bestAsk.quantity)
    }
}