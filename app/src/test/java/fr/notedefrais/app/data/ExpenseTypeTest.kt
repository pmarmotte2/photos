package fr.notedefrais.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class ExpenseTypeTest {
    @Test
    fun everyCategoryHasAtLeastOneType() {
        ExpenseCategory.entries.forEach { category ->
            assertTrue(
                "${category.name} doit proposer au moins un type",
                ExpenseType.forCategory(category).isNotEmpty()
            )
        }
    }

    @Test
    fun typesAreGroupedUnderTheirOwnCategory() {
        ExpenseCategory.entries.forEach { category ->
            ExpenseType.forCategory(category).forEach { type ->
                assertEquals(category, type.category)
            }
        }
    }

    @Test
    fun atosCatalogContainsAllVisibleExpenseTypes() {
        assertEquals(47, ExpenseType.entries.size)
        assertEquals(46, ExpenseType.entries.count { it != ExpenseType.OTHER })
        assertEquals("01 Transports (occasional)", ExpenseType.TRANSPORT_OCCASIONAL.displayLabel)
        assertEquals("11 Move accompanying measures", ExpenseType.MOVE_ACCOMPANYING_MEASURES.displayLabel)
    }

    @Test
    fun policyDefaultsMatchMealAndHotelRules() {
        assertEquals(BigDecimal("20.00"), ExpenseType.LUNCH.defaultLimit)
        assertEquals(BigDecimal("20.00"), ExpenseType.DINNER_COUNTRY.defaultLimit)
        assertEquals(BigDecimal("25.00"), ExpenseType.DINNER_PARIS.defaultLimit)
        assertEquals(BigDecimal("40.00"), ExpenseType.LUNCH_DINNER_COUNTRY.defaultLimit)
        assertEquals(BigDecimal("45.00"), ExpenseType.LUNCH_DINNER_PARIS.defaultLimit)
        assertEquals(BigDecimal("130.00"), ExpenseType.HOTEL_EXCEPT_PARIS_SOPHIA.defaultLimit)
        assertEquals(BigDecimal("168.00"), ExpenseType.HOTEL_PARIS_SOPHIA.defaultLimit)
    }

    @Test
    fun customCumulatedParisLimitOverridesGenericDailyMealAllowance() {
        val reimbursable = calculateReceiptReimbursableAmount(
            receiptAmount = BigDecimal("50.00"),
            expenseType = ExpenseType.LUNCH_DINNER_PARIS,
            dailyMealAllowance = BigDecimal("40.00"),
            customTypeLimit = BigDecimal("45.00")
        )

        assertEquals(BigDecimal("45.00"), reimbursable)
    }

    @Test
    fun defaultLunchAndParisDinnerCanReachFortyFiveTogether() {
        val lunch = calculateReceiptReimbursableAmount(
            receiptAmount = BigDecimal("30.00"),
            expenseType = ExpenseType.LUNCH,
            dailyMealAllowance = BigDecimal("40.00")
        )
        val dinner = calculateReceiptReimbursableAmount(
            receiptAmount = BigDecimal("30.00"),
            expenseType = ExpenseType.DINNER_PARIS,
            dailyMealAllowance = BigDecimal("40.00"),
            alreadySpentForMeals = lunch
        )

        assertEquals(BigDecimal("20.00"), lunch)
        assertEquals(BigDecimal("25.00"), dinner)
        assertEquals(BigDecimal("45.00"), lunch + dinner)
    }

    @Test
    fun parisLunchAndDinnerRaiseDailyAllowanceToFortyFive() {
        val allowance = calculateDailyMealAllowance(
            baseAllowance = BigDecimal("40.00"),
            mealTypes = listOf(ExpenseType.LUNCH, ExpenseType.DINNER_PARIS),
            customLimits = emptyMap()
        )

        assertEquals(BigDecimal("45.00"), allowance)
    }
}
