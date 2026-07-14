package com.frankenergie.smartasset.service

import com.frankenergie.smartasset.model.ClientPurchaseSummary
import com.frankenergie.smartasset.model.ExecutedClientPurchase
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentLinkedQueue

@Service
class ClientPurchaseTrackerService {

    private val purchases: ConcurrentLinkedQueue<ExecutedClientPurchase> = ConcurrentLinkedQueue()

    fun recordPurchase(order: ExecutedClientPurchase) {
        purchases.add(order)
    }

    fun getAllPurchases(): List<ExecutedClientPurchase> = purchases.toList()

    fun getSummary(): ClientPurchaseSummary { // gets the net cost of buying energy for clients
        val allPurchases = purchases.toList()
        val totalMWh = allPurchases.sumOf { it.quantity }
        val totalCost = allPurchases.sumOf { it.totalCost }
        val averagePrice = if (totalMWh > BigDecimal.ZERO) {
            totalCost.divide(totalMWh, 2, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ZERO
        }

        return ClientPurchaseSummary(totalMWh, totalCost, averagePrice)
    }

    fun clear() {
        purchases.clear()
    }
}