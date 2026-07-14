package com.frankenergie.smartasset.service

import com.frankenergie.smartasset.model.ExecutedClientPurchase
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.math.BigDecimal
import java.time.LocalDateTime

@SpringBootTest
class ClientPurchaseTrackerServiceTest {

    @Autowired
    private lateinit var service: ClientPurchaseTrackerService

    @BeforeEach
    fun setUp() {
        service.clear()
    }

    @Test
    fun `recordPurchase adds purchase and getSummary calculates weighted average`() {
        val now = LocalDateTime.now()
        val p1 = ExecutedClientPurchase(
            "A", now, now.plusMinutes(15),
            BigDecimal("10"), BigDecimal("50.00"), now
        )
        val p2 = ExecutedClientPurchase(
            "A", now.plusMinutes(15), now.plusMinutes(30),
            BigDecimal("20"), BigDecimal("40.00"), now
        )

        service.recordPurchase(p1)
        service.recordPurchase(p2)

        val summary = service.getSummary()
        assertEquals(BigDecimal("30"), summary.totalMWh)
        // (10 * 50) + (20 * 40) = 500 + 800 = 1300.00
        assertEquals(0, BigDecimal("1300.00").compareTo(summary.totalCost))
        // 1300 / 30 = 43.33
        assertEquals(BigDecimal("43.33"), summary.averagePricePerMWh)
    }

    @Test
    fun `getSummary returns zero when no purchases recorded`() {
        val summary = service.getSummary()
        assertEquals(BigDecimal.ZERO, summary.totalMWh)
        assertEquals(BigDecimal.ZERO, summary.totalCost)
        assertEquals(BigDecimal.ZERO, summary.averagePricePerMWh)
    }

    @Test
    fun `clear empties the tracker`() {
        val now = LocalDateTime.now()
        service.recordPurchase(
            ExecutedClientPurchase("A", now, now.plusMinutes(15), BigDecimal("10"), BigDecimal("50.00"), now)
        )
        service.clear()
        assertTrue(service.getAllPurchases().isEmpty())
    }
}