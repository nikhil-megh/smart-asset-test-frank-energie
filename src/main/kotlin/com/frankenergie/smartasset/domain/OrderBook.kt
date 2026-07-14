package com.frankenergie.smartasset.domain

import com.frankenergie.smartasset.model.DeliveryPeriod
import com.frankenergie.smartasset.model.OrderSide
import java.math.BigDecimal
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

data class PriceLevel(
    val price: BigDecimal,
    val quantity: BigDecimal
)

data class OrderResult(
    val matchedQuantity: BigDecimal,
    val matchedQuantityPrices: List<PriceLevel>,
    val remainingQuantity: BigDecimal
)

class OrderBook {

    private val bidsPerPeriod: ConcurrentHashMap<DeliveryPeriod, TreeMap<BigDecimal, BigDecimal>> = ConcurrentHashMap()
    private val asksPerPeriod: ConcurrentHashMap<DeliveryPeriod, TreeMap<BigDecimal, BigDecimal>> = ConcurrentHashMap()

    // add lock on the quarter while it is being updated to keep it thread safe
    private val locksPerPeriod: ConcurrentHashMap<DeliveryPeriod, ReentrantLock> = ConcurrentHashMap()
    private fun lockFor(period: DeliveryPeriod): ReentrantLock =
        locksPerPeriod.computeIfAbsent(period) { ReentrantLock() }

    fun processMarketOrder(period: DeliveryPeriod, side: OrderSide, quantity: BigDecimal, price: BigDecimal): BigDecimal {
        return matchOrder(period, side, quantity, price, true).matchedQuantity
    }

    fun matchOrder(
        period: DeliveryPeriod,
        side: OrderSide,
        quantity: BigDecimal,
        price: BigDecimal,
        isExecutedTransaction: Boolean): OrderResult  // isExecutedTransaction basically indicates is this order is an executed transaction in the market or if it is yet to be executed but we want to on behalf of the client
    {
        val lock = lockFor(period)
        lock.lock()
        try {
            val bids = bidsPerPeriod.computeIfAbsent(period) { TreeMap(Comparator.reverseOrder()) }
            val asks = asksPerPeriod.computeIfAbsent(period) { TreeMap() }
            return when (side) {
                OrderSide.BUY -> matchBuyOrder(bids, asks, quantity, price, isExecutedTransaction)
                OrderSide.SELL -> matchSellOrder(bids, asks, quantity, price, isExecutedTransaction)
            }
        } finally {
            lock.unlock()
        }
    }

    // refactored this to give breakdown of matched quantity-prices instead of just total matched quantity
    private fun matchBuyOrder(
        bids: TreeMap<BigDecimal, BigDecimal>,
        asks: TreeMap<BigDecimal, BigDecimal>,
        quantity: BigDecimal,
        price: BigDecimal,
        isExecutedTransaction: Boolean
    ): OrderResult {
        var remaining = quantity
        val matchedQuantityPrices = mutableListOf<PriceLevel>()

        val iterator = asks.entries.iterator()
        while (iterator.hasNext() && remaining > BigDecimal.ZERO) {
            val (askPrice, askQty) = iterator.next()
            if (askPrice > price) break

            val matched = remaining.min(askQty)
            remaining -= matched
            matchedQuantityPrices.add(PriceLevel(askPrice, matched)) // assumption: buying it at the ask price which is <= order's bid price; assuming bid price is the max price willing to buy at
            val newQty = askQty - matched
            if (newQty <= BigDecimal.ZERO) iterator.remove() else asks[askPrice] = newQty
        }

        if (isExecutedTransaction && remaining > BigDecimal.ZERO) {
            bids.merge(price, remaining) { old, new -> old + new }
        }

        return OrderResult(quantity - remaining, matchedQuantityPrices, remaining) // returning complete breakdown instead of just matched quantity
    }

    // refactored this to give breakdown of matched quantity-prices instead of just total matched quantity
    private fun matchSellOrder(
        bids: TreeMap<BigDecimal, BigDecimal>,
        asks: TreeMap<BigDecimal, BigDecimal>,
        quantity: BigDecimal,
        price: BigDecimal,
        isExecutedTransaction: Boolean
    ): OrderResult {
        var remaining = quantity
        val matchedQuantityPrices = mutableListOf<PriceLevel>()

        val iterator = bids.entries.iterator()
        while (iterator.hasNext() && remaining > BigDecimal.ZERO) {
            val (bidPrice, bidQty) = iterator.next()
            if (bidPrice < price) break

            val matched = remaining.min(bidQty)
            remaining -= matched
            matchedQuantityPrices.add(PriceLevel(bidPrice, matched)) // assumption: selling it to the bid price which is >= order's ask price; assuming ask price is the min price willing to sell at
            val newQty = bidQty - matched
            if (newQty <= BigDecimal.ZERO) iterator.remove() else bids[bidPrice] = newQty
        }

        if (isExecutedTransaction && remaining > BigDecimal.ZERO) {
            asks.merge(price, remaining) { old, new -> old + new }
        }

        return OrderResult(quantity - remaining, matchedQuantityPrices, remaining) // returning complete breakdown instead of just matched quantity
    }

    fun getBids(period: DeliveryPeriod): Map<BigDecimal, BigDecimal> = bidsPerPeriod[period]?.toMap() ?: emptyMap()

    fun getAsks(period: DeliveryPeriod): Map<BigDecimal, BigDecimal> = asksPerPeriod[period]?.toMap() ?: emptyMap()

    fun getBestBid(period: DeliveryPeriod): PriceLevel? =
        bidsPerPeriod[period]?.firstEntry()?.let { PriceLevel(it.key, it.value) }

    fun getBestAsk(period: DeliveryPeriod): PriceLevel? =
        asksPerPeriod[period]?.firstEntry()?.let { PriceLevel(it.key, it.value) }

    fun getAllPeriods(): Set<DeliveryPeriod> = bidsPerPeriod.keys + asksPerPeriod.keys
}
