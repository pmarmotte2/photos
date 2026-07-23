package fr.notedefrais.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class DailySummaryTest {
    @Test
    fun remaining_is_allowance_minus_meals() {
        val summary = DailySummary(
            date = LocalDate.of(2026, 7, 22),
            mealSpent = BigDecimal("31.50"),
            allowance = BigDecimal("40.00")
        )

        assertEquals(BigDecimal("8.50"), summary.remaining)
        assertEquals(0.7875f, summary.ratio, 0.0001f)
    }

    @Test
    fun ratio_is_capped_when_allowance_is_exceeded() {
        val summary = DailySummary(
            date = LocalDate.of(2026, 7, 23),
            mealSpent = BigDecimal("48.00"),
            allowance = BigDecimal("40.00")
        )

        assertEquals(BigDecimal("-8.00"), summary.remaining)
        assertEquals(1f, summary.ratio)
    }

    @Test(expected = IllegalArgumentException::class)
    fun trip_rejects_an_end_before_its_start() {
        Trip(
            name = "Les Clayes",
            startDate = LocalDate.of(2026, 7, 23),
            endDate = LocalDate.of(2026, 7, 22)
        )
    }
}
