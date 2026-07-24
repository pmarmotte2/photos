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

    @Test
    fun second_meal_is_capped_to_the_remaining_daily_allowance() {
        val reimbursable = calculateReimbursableAmount(
            receiptAmount = BigDecimal("30.00"),
            dailyAllowance = BigDecimal("40.00"),
            alreadySpent = BigDecimal("20.00")
        )

        assertEquals(BigDecimal("20.00"), reimbursable)
    }

    @Test
    fun meal_is_fully_reimbursable_when_allowance_is_available() {
        val reimbursable = calculateReimbursableAmount(
            receiptAmount = BigDecimal("18.50"),
            dailyAllowance = BigDecimal("40.00"),
            alreadySpent = BigDecimal("10.00")
        )

        assertEquals(BigDecimal("18.50"), reimbursable)
    }

    @Test
    fun meal_is_not_reimbursable_when_allowance_is_exhausted() {
        val reimbursable = calculateReimbursableAmount(
            receiptAmount = BigDecimal("12.00"),
            dailyAllowance = BigDecimal("40.00"),
            alreadySpent = BigDecimal("44.00")
        )

        assertEquals(BigDecimal.ZERO, reimbursable)
    }

    @Test
    fun custom_type_limit_caps_a_non_meal_expense() {
        val reimbursable = applyExpenseLimit(
            receiptAmount = BigDecimal("180.00"),
            typeLimit = BigDecimal("150.00")
        )

        assertEquals(BigDecimal("150.00"), reimbursable)
    }

    @Test
    fun meal_uses_the_strictest_of_type_and_daily_limits() {
        val reimbursable = calculateReimbursableAmount(
            receiptAmount = BigDecimal("30.00"),
            dailyAllowance = BigDecimal("40.00"),
            alreadySpent = BigDecimal("12.00"),
            typeLimit = BigDecimal("20.00")
        )

        assertEquals(BigDecimal("20.00"), reimbursable)
    }

    @Test
    fun blank_custom_limit_keeps_the_existing_default_rule() {
        val reimbursable = calculateReimbursableAmount(
            receiptAmount = BigDecimal("30.00"),
            dailyAllowance = BigDecimal("40.00"),
            alreadySpent = BigDecimal("20.00"),
            typeLimit = null
        )

        assertEquals(BigDecimal("20.00"), reimbursable)
    }
}
