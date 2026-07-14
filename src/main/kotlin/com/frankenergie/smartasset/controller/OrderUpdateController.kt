package com.frankenergie.smartasset.controller

import com.frankenergie.smartasset.model.ClientPurchaseSummary
import com.frankenergie.smartasset.model.OrderUpdateRequest
import com.frankenergie.smartasset.model.OrderUpdateResponse
import com.frankenergie.smartasset.service.ClientPurchaseTrackerService
import com.frankenergie.smartasset.service.OrderBookService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class OrderUpdateController(
    private val orderBookService: OrderBookService,
    private val clientPurchaseTrackerService: ClientPurchaseTrackerService,
) {

    @PostMapping("/orderupdate")
    fun orderUpdate(@RequestBody request: OrderUpdateRequest): ResponseEntity<OrderUpdateResponse> {
        val response = orderBookService.processOrder(request)
        return ResponseEntity.ok(response)
    }

    @GetMapping("/client/orders/average") // instead of giving incorrect purchase summary give summary of all orders executed by us on behalf of client to fulfill their needs
    fun getPurchaseAverage(): ResponseEntity<ClientPurchaseSummary> {
        val summary = clientPurchaseTrackerService.getSummary()
        return ResponseEntity.ok(summary)
    }
}
