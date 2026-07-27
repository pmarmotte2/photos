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

    @Test
    fun tripZoneSelectsTheMatchingDinnerAndCumulatedTypes() {
        assertEquals(ExpenseType.DINNER_PARIS, MealZone.PARIS_SOPHIA.dinnerType())
        assertEquals(
            ExpenseType.LUNCH_DINNER_PARIS,
            MealZone.PARIS_SOPHIA.lunchDinnerType()
        )
        assertEquals(ExpenseType.DINNER_COUNTRY, MealZone.PROVINCE.dinnerType())
        assertEquals(
            ExpenseType.LUNCH_DINNER_COUNTRY,
            MealZone.PROVINCE.lunchDinnerType()
        )
    }

    @Test
    fun parisTripAutomaticallyUsesFortyFiveEuroDailyAllowance() {
        val trip = Trip(
            name = "Paris",
            startDate = java.time.LocalDate.of(2026, 7, 27),
            endDate = java.time.LocalDate.of(2026, 7, 27),
            mealZone = MealZone.PARIS_SOPHIA
        )

        assertEquals(BigDecimal("45.00"), trip.dailyMealAllowance)
    }

    @Test
    fun changingTripZoneAlsoChangesItsDailyMealAllowance() {
        val trip = Trip(
            name = "Province",
            startDate = java.time.LocalDate.of(2026, 7, 27),
            endDate = java.time.LocalDate.of(2026, 7, 27),
            mealZone = MealZone.PROVINCE
        )

        val relocated = trip.withMealZone(MealZone.PARIS_SOPHIA)

        assertEquals(MealZone.PARIS_SOPHIA, relocated.mealZone)
        assertEquals(BigDecimal("45.00"), relocated.dailyMealAllowance)
    }

    @Test
    fun combinedCalculationIsSuggestedWhenSeparateLimitWouldLoseMoney() {
        val beneficial = isCombinedMealCalculationBeneficial(
            entries = listOf(
                MealEntry(BigDecimal("25.00"), ExpenseType.LUNCH),
                MealEntry(BigDecimal("15.00"), ExpenseType.DINNER_PARIS)
            ),
            zone = MealZone.PARIS_SOPHIA,
            customLimits = emptyMap()
        )

        assertTrue(beneficial)
    }

    @Test
    fun existingCumulatedMealBecomesTheOppositeOfTheNewSeparateMeal() {
        assertEquals(
            ExpenseType.DINNER_PARIS,
            ExpenseType.LUNCH.oppositeTypeForExistingLunchDinner(MealZone.PARIS_SOPHIA)
        )
        assertEquals(
            ExpenseType.LUNCH,
            ExpenseType.DINNER_COUNTRY
                .oppositeTypeForExistingLunchDinner(MealZone.PROVINCE)
        )
    }

    @Test
    fun combinedCalculationIsNotSuggestedWhenDailyTotalExceedsCombinedLimit() {
        val beneficial = isCombinedMealCalculationBeneficial(
            entries = listOf(
                MealEntry(BigDecimal("30.00"), ExpenseType.LUNCH),
                MealEntry(BigDecimal("20.00"), ExpenseType.DINNER_PARIS)
            ),
            zone = MealZone.PARIS_SOPHIA,
            customLimits = emptyMap()
        )

        assertEquals(false, beneficial)
    }

    @Test
    fun defaultEmployerContributionIsSixEuros() {
        assertEquals(
            BigDecimal("6.00"),
            DEFAULT_MEAL_VOUCHER_EMPLOYER_CONTRIBUTION
        )
    }

    @Test
    fun employerContributionIsDeductedFromFirstLunch() {
        val reimbursableBase = applyMealVoucherEmployerContribution(
            receiptAmount = BigDecimal("18.00"),
            expenseType = ExpenseType.LUNCH,
            employerContribution = BigDecimal("6.00"),
            contributionAlreadyApplied = false
        )

        assertEquals(BigDecimal("12.00"), reimbursableBase)
    }

    @Test
    fun employerContributionIsOnlyDeductedOncePerDay() {
        val secondCumulatedReceipt = applyMealVoucherEmployerContribution(
            receiptAmount = BigDecimal("20.00"),
            expenseType = ExpenseType.LUNCH_DINNER_PARIS,
            employerContribution = BigDecimal("6.00"),
            contributionAlreadyApplied = true
        )

        assertEquals(BigDecimal("20.00"), secondCumulatedReceipt)
    }

    @Test
    fun employerContributionIsNotDeductedFromDinner() {
        val dinner = applyMealVoucherEmployerContribution(
            receiptAmount = BigDecimal("20.00"),
            expenseType = ExpenseType.DINNER_PARIS,
            employerContribution = BigDecimal("6.00"),
            contributionAlreadyApplied = false
        )

        assertEquals(BigDecimal("20.00"), dinner)
    }

    @Test
    fun annotationExplainsEmployerContributionCalculation() {
        val lines = ReimbursementCalculation(
            declaredAmount = BigDecimal("20.00"),
            reimbursableAmount = BigDecimal("14.00"),
            employerContributionDeducted = BigDecimal("6.00"),
            quota = BigDecimal("20.00")
        ).annotationDetailLines()

        assertEquals(
            listOf("Participation employeur : 20,00 € - 6,00 € = 14,00 €"),
            lines
        )
    }

    @Test
    fun annotationExplainsReachedQuotaAndRetainedAmount() {
        val lines = ReimbursementCalculation(
            declaredAmount = BigDecimal("30.00"),
            reimbursableAmount = BigDecimal("8.00"),
            quota = BigDecimal("20.00"),
            alreadyReimbursedAgainstQuota = BigDecimal("12.00")
        ).annotationDetailLines()

        assertEquals(
            listOf(
                "Quota de 20,00 € atteint",
                "Déjà remboursé sur ce quota : 12,00 €",
                "Remboursement à hauteur de 8,00 €"
            ),
            lines
        )
    }

    @Test
    fun annotationCanExplainContributionAndQuotaTogether() {
        val lines = ReimbursementCalculation(
            declaredAmount = BigDecimal("30.00"),
            reimbursableAmount = BigDecimal("10.00"),
            employerContributionDeducted = BigDecimal("6.00"),
            quota = BigDecimal("20.00"),
            alreadyReimbursedAgainstQuota = BigDecimal("10.00")
        ).annotationDetailLines()

        assertEquals(4, lines.size)
        assertEquals(
            "Participation employeur : 30,00 € - 6,00 € = 24,00 €",
            lines.first()
        )
        assertEquals("Remboursement à hauteur de 10,00 €", lines.last())
    }

    @Test
    fun combinedSuggestionAccountsForEmployerContribution() {
        val beneficial = isCombinedMealCalculationBeneficial(
            entries = listOf(
                MealEntry(BigDecimal("30.00"), ExpenseType.LUNCH),
                MealEntry(BigDecimal("10.00"), ExpenseType.DINNER_PARIS)
            ),
            zone = MealZone.PARIS_SOPHIA,
            customLimits = emptyMap(),
            employerContribution = BigDecimal("6.00")
        )

        assertTrue(beneficial)
    }
}
