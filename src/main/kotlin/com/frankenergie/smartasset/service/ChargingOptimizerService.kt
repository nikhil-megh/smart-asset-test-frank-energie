package com.frankenergie.smartasset.service

import com.frankenergie.smartasset.client.MarketOrderClient
import com.frankenergie.smartasset.client.SteeringSignalClient
import com.frankenergie.smartasset.domain.OrderBook
import com.frankenergie.smartasset.domain.PriceLevel
import com.frankenergie.smartasset.model.ChargingGroup
import com.frankenergie.smartasset.model.ChargingPlan
import com.frankenergie.smartasset.model.DeliveryPeriod
import com.frankenergie.smartasset.model.ExecutedClientPurchase
import com.frankenergie.smartasset.model.GroupAllocation
import com.frankenergie.smartasset.model.MarketOrder
import com.frankenergie.smartasset.model.OrderSide
import com.frankenergie.smartasset.model.OrderStatus
import com.frankenergie.smartasset.model.OrderUpdateRequest
import com.frankenergie.smartasset.model.QuarterAllocation
import com.frankenergie.smartasset.model.SteeringSignal
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
class ChargingOptimizerService(
    private val chargingGroups: List<ChargingGroup>,
    private val marketOrderClient: MarketOrderClient,
    private val clientPurchaseTrackerService: ClientPurchaseTrackerService,
    private val steeringSignalClient: SteeringSignalClient
) {
    private val quarterDurationHours = BigDecimal("0.25")

    // groupAllocations = single source of truth for where each group is at right now
    // Using ConcurrentHashMap for atomic read-modify-write per group
    private val groupAllocations = ConcurrentHashMap<String, GroupAllocation>()

    // gives current allocation for a given group
    private fun currentAllocation(groupId: String): GroupAllocation =
        groupAllocations[groupId] ?: GroupAllocation(groupId, emptyList(), BigDecimal.ZERO, BigDecimal.ZERO)

    // gives what is yet to be fulfilled for a given group
    private fun remainingNeed(group: ChargingGroup): BigDecimal =
        (group.neededChargeMWh - currentAllocation(group.id).totalMWh).max(BigDecimal.ZERO)

    // checks if a given group was supposed to be charged for a given quarter period
    private fun isWithinWindow(group: ChargingGroup, period: DeliveryPeriod): Boolean =
        !period.startTime.isBefore(group.startTime) && !period.endTime.isAfter(group.endTime)

    // checks if a given group still needs charge for a given quarter period
    private fun hasAllocationForQuarter(groupId: String, period: DeliveryPeriod): Boolean =
        currentAllocation(groupId).allocations.any { it.startTime == period.startTime && it.endTime == period.endTime }

     // This function is to be executed at the beginning of the day when the client provides us with the charging plan
     // It goes through every group, every quarter in its window, and greedily fills it with cheapest asks first
    fun optimize(orderBook: OrderBook, orderTime: LocalDateTime): ChargingPlan {
        chargingGroups.forEach { group -> optimizeGroup(group, orderBook, orderTime) }
        return getCurrentPlan()
    }

    // greedily fills the quarters with cheapest asks first
    private fun optimizeGroup(group: ChargingGroup, orderBook: OrderBook, orderTime: LocalDateTime) {
        val maxPerQuarter = group.maxPowerMW * quarterDurationHours

        // client allocations are permanent once made as per this strategy as we care about client fulfillment
        // so filter only those quarters where allocation is still needed
        val candidateQuarters = getQuartersInWindow(group.startTime, group.endTime)
            .filter { !hasAllocationForQuarter(group.id, it) }

        // filter the quarters that can be filled keeping mind the quarter quantity cap and the max order bid price
        // These quarters are then sorted in increasing order of price to get quarter with cheapest prices first
        val ranked = candidateQuarters.mapNotNull { period ->
            val preview = previewFill(orderBook.getAsks(period), maxPerQuarter, group.maxOrderBidPrice)
            if (preview.quantity > BigDecimal.ZERO) period to preview.averagePrice else null
        }.sortedBy { it.second }

        // iterate through the list and try allocating quarters for the group till its need is fulfilled
        for ((period, _) in ranked) {
            if (remainingNeed(group) <= BigDecimal.ZERO) break
            tryAllocateQuarterForGroup(group, period, orderBook, orderTime)
        }
    }

    fun getCurrentPlan(): ChargingPlan {
        val allocations = chargingGroups.map { currentAllocation(it.id) }
        return ChargingPlan(allocations, allocations.sumOf { it.totalCost })
    }

    private fun getQuartersInWindow(start: LocalDateTime, end: LocalDateTime): List<DeliveryPeriod> {
        val quarters = mutableListOf<DeliveryPeriod>()
        var current = start
        while (current < end) {
            val quarterEnd = current.plusMinutes(15)
            quarters.add(DeliveryPeriod(current, quarterEnd.coerceAtMost(end)))
            current = quarterEnd
        }
        return quarters
    }

    private data class PreviewFill(val quantity: BigDecimal, val averagePrice: BigDecimal)

    // finds best way to fulfill remaining need from asks keeping in mind quarterly quantity cap and max order bid price
    private fun previewFill(asks: Map<BigDecimal, BigDecimal>, cap: BigDecimal, maxPrice: BigDecimal): PreviewFill {
        var remaining = cap
        var qty = BigDecimal.ZERO
        var cost = BigDecimal.ZERO
        for ((price, available) in asks.toSortedMap()) {
            if (remaining <= BigDecimal.ZERO) break
            if (price > maxPrice) break
            val take = remaining.min(available)
            qty += take
            cost += take * price
            remaining -= take
        }

        val avgCost = if (qty > BigDecimal.ZERO) {
            cost.divide(qty, 4, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ZERO
        }

        return PreviewFill(qty, avgCost)
    }

    // EVENT-DRIVEN REACTION: Called once per incoming order update from OrderBookService
    // Only accepter and partially filled SELL orders are relevant to us
    // We check only that one quarter for  only the groups whose window covers it
    // IMPORTANT NOTE: Current algorithm simply focuses on fulfilling client needs as soon as possible
    // IMPROVEMENT: This can be improved further from a greedy permanent allocation to greedy dynamic allocation
    // where we also put SELL orders for expensive allocations and trade them for cheaper BUY options
    fun handleMarketUpdate(request: OrderUpdateRequest, status: OrderStatus, orderBook: OrderBook) {
        if (request.orderSide != OrderSide.SELL || status == OrderStatus.FILLED) return

        val period = DeliveryPeriod(request.deliveryStartTime, request.deliveryEndTime)

        // filter relevant groups
        val candidates = chargingGroups.filter { group ->
            isWithinWindow(group, period) &&
                    !hasAllocationForQuarter(group.id, period) &&
                    remainingNeed(group) > BigDecimal.ZERO
        }
        if (candidates.isEmpty()) return

        candidates.forEach { group -> tryAllocateQuarterForGroup(group, period, orderBook, request.orderTime) }
    }

    // Tries to allocate the best asks for allocating a group's remaining need for a given quarter period
    private fun tryAllocateQuarterForGroup(group: ChargingGroup, period: DeliveryPeriod, orderBook: OrderBook, orderTime: LocalDateTime) {
        val remaining = remainingNeed(group)
        if (remaining <= BigDecimal.ZERO) return

        val maxPerQuarter = group.maxPowerMW * quarterDurationHours
        val cap = maxPerQuarter.min(remaining)

        val result = orderBook.matchOrder(period, OrderSide.BUY, cap, group.maxOrderBidPrice, false)
        if (result.matchedQuantity <= BigDecimal.ZERO) return

        // request could be fulfilled from multiple asks
        result.matchedQuantityPrices.forEach { order -> executeClientOrder(group.id, period, order, orderTime) }

        val allocation = QuarterAllocation(
            startTime = period.startTime,
            endTime = period.endTime,
            mwh = result.matchedQuantity,
            pricePerMWh = weightedAveragePrice(result.matchedQuantityPrices)
        )

        groupAllocations.compute(group.id) { _, existing ->
            val base = existing ?: GroupAllocation(group.id, emptyList(), BigDecimal.ZERO, BigDecimal.ZERO)
            GroupAllocation(
                groupId = group.id,
                allocations = base.allocations + allocation,
                totalMWh = base.totalMWh + allocation.mwh,
                totalCost = base.totalCost + allocation.cost
            )
        }

        steeringSignalClient.sendSignal(
            SteeringSignal(
                groupId = group.id,
                quarterStart = allocation.startTime,
                quarterEnd = allocation.endTime,
                chargePowerMW = allocation.mwh.divide(quarterDurationHours),
                time = orderTime
            )
        )
    }

    // for future: need to execute the actual trade with the trade client
    private fun executeClientOrder(groupId: String, period: DeliveryPeriod, order: PriceLevel, orderTime: LocalDateTime) {
        marketOrderClient.sendOrder(
            MarketOrder(
                orderId = UUID.randomUUID().toString(),
                groupId = groupId,
                deliveryStart = period.startTime,
                deliveryEnd = period.endTime,
                side = OrderSide.BUY,
                quantity = order.quantity,
                price = order.price,
                status = OrderStatus.FILLED,
                orderTime = orderTime
            )
        )
        clientPurchaseTrackerService.recordPurchase(
            ExecutedClientPurchase(
                groupId = groupId,
                deliveryStart = period.startTime,
                deliveryEnd = period.endTime,
                quantity = order.quantity,
                pricePerMWh = order.price,
                orderTime = orderTime
            )
        )
    }

    private fun weightedAveragePrice(orders: List<PriceLevel>): BigDecimal {
        val totalQty = orders.sumOf { it.quantity }
        val totalCost = orders.sumOf { it.price * it.quantity }
        return if (totalQty > BigDecimal.ZERO) totalCost.divide(totalQty, 2, RoundingMode.HALF_UP) else BigDecimal.ZERO
    }

}