package com.frankenergie.smartasset.service

import com.frankenergie.smartasset.client.MarketOrderClient
import com.frankenergie.smartasset.domain.OrderBook
import com.frankenergie.smartasset.model.DeliveryPeriod
import com.frankenergie.smartasset.model.MarketOrder
import com.frankenergie.smartasset.model.MarketOverviewResponse
import com.frankenergie.smartasset.model.OrderStatus
import com.frankenergie.smartasset.model.OrderUpdateRequest
import com.frankenergie.smartasset.model.OrderUpdateResponse
import com.frankenergie.smartasset.model.QuarterOverview
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Service
class OrderBookService(
    private val marketOrderClient: MarketOrderClient,
    private val chargingOptimizerService: ChargingOptimizerService
) {

    private val orderBook = OrderBook()

    fun processOrder(request: OrderUpdateRequest): OrderUpdateResponse {
        val orderId = UUID.randomUUID().toString()

        val period = DeliveryPeriod(request.deliveryStartTime, request.deliveryEndTime)
        val matchedQuantity = orderBook.processMarketOrder(period, request.orderSide, request.quantity, request.price)
        val orderStatus = when {
            matchedQuantity == request.quantity -> OrderStatus.FILLED
            matchedQuantity > BigDecimal.ZERO -> OrderStatus.PARTIALLY_FILLED
            else -> OrderStatus.ACCEPTED
        }

        marketOrderClient.sendOrder( // logging the market order request
            MarketOrder(
                orderId = orderId,
                groupId = "market",
                deliveryStart = request.deliveryStartTime,
                deliveryEnd = request.deliveryEndTime,
                side = request.orderSide,
                quantity = request.quantity,
                price = request.price,
                status = orderStatus, // updated with status of order
                orderTime = request.orderTime, // updated time to be same as order request time to enable simulation // will actually be current timestamp if process order is being called in realtime
            )
        )

        // check if any client need can be fulfilled from this order
        chargingOptimizerService.handleMarketUpdate(request, orderStatus, orderBook)

        return OrderUpdateResponse(orderId = orderId, status = orderStatus.name, timestamp = Instant.now())
    }

    // gives overview of highest buy and lowest sell price available for each quarter at a given point, not historically
    fun getMarketOverview(): MarketOverviewResponse {
        val quarters = orderBook.getAllPeriods()
            .sortedBy { it.startTime }
            .map { period ->
                QuarterOverview(
                    deliveryStartTime = period.startTime,
                    deliveryEndTime = period.endTime,
                    highestBuyPrice = orderBook.getBestBid(period)?.price,
                    lowestSellPrice = orderBook.getBestAsk(period)?.price
                )
            }
        return MarketOverviewResponse(quarters)
    }

    fun getOrderBook(): OrderBook = orderBook
}
