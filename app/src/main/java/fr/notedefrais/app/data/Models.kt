package fr.notedefrais.app.data

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class Trip(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val dailyMealAllowance: BigDecimal = BigDecimal("40.00")
) {
    init {
        require(name.isNotBlank())
        require(!endDate.isBefore(startDate))
        require(dailyMealAllowance >= BigDecimal.ZERO)
    }
}

enum class ExpenseCategory(val label: String) {
    TRANSPORT("Transport"),
    MEAL("Repas"),
    HOTEL("Hôtel"),
    HOUSING("Logement"),
    TELECOM("Télécom"),
    PROFESSIONAL("Frais professionnels"),
    MOBILITY("Mobilité"),
    OTHER("Autre")
}

enum class ExpenseType(
    val code: String,
    val label: String,
    val category: ExpenseCategory
) {
    TRANSPORT_OCCASIONAL("01", "Transports (occasional)", ExpenseCategory.TRANSPORT),
    TRANSPORT_SUBSCRIPTION("01", "Transports (Subscription)", ExpenseCategory.TRANSPORT),
    TRANSPORT_ZONE_EXTENSION("01", "Transports (Zone extension)", ExpenseCategory.TRANSPORT),
    TAXI("02", "Taxi", ExpenseCategory.TRANSPORT),
    TRAIN("03", "Train", ExpenseCategory.TRANSPORT),
    PLANE("04", "Plane", ExpenseCategory.TRANSPORT),
    CAR_RENTAL("05", "Car rental", ExpenseCategory.TRANSPORT),
    DIESEL("05", "Diesel", ExpenseCategory.TRANSPORT),
    GAS("05", "Gas", ExpenseCategory.TRANSPORT),
    MILEAGE_ALLOWANCES_BULL("05", "Mileage allowances BULL", ExpenseCategory.TRANSPORT),
    PARKING("05", "Parking", ExpenseCategory.TRANSPORT),
    TOLL("05", "Toll", ExpenseCategory.TRANSPORT),

    BREAKFAST("06", "Breakfast", ExpenseCategory.MEAL),
    DINNER_DRINK("06", "Dinner - Drink (20% VAT)", ExpenseCategory.MEAL),
    DINNER_COUNTRY("06", "Dinner country", ExpenseCategory.MEAL),
    DINNER_PARIS("06", "Dinner Paris", ExpenseCategory.MEAL),
    HOTEL_MEAL_PART("06", "Hotel - Meal part", ExpenseCategory.MEAL),
    LUNCH_DINNER_COUNTRY("06", "Lnch+Dnnr Cntry (cumulated)", ExpenseCategory.MEAL),
    LUNCH_DINNER_PARIS("06", "Lnch+Dnnr Paris (cumulated)", ExpenseCategory.MEAL),
    LUNCH("06", "Lunch", ExpenseCategory.MEAL),
    LUNCH_DRINK("06", "Lunch - Drink (20% VAT)", ExpenseCategory.MEAL),
    MEAL_ABROAD("06", "Meal abroad", ExpenseCategory.MEAL),
    MEAL_ALLOWANCE_COUNTRY("06", "Meal allowance country", ExpenseCategory.MEAL),
    MEAL_ALLOWANCE_PARIS("06", "Meal allowance Paris", ExpenseCategory.MEAL),
    RECEPTION("06", "Reception", ExpenseCategory.MEAL),

    HOTEL_ABROAD("07", "Hotel abroad", ExpenseCategory.HOTEL),
    HOTEL_EXCEPT_PARIS_SOPHIA("07", "Hotel except Paris / Sophia", ExpenseCategory.HOTEL),
    HOTEL_PARIS_SOPHIA("07", "Hotel Paris / Sophia", ExpenseCategory.HOTEL),

    HOUSING_PARIS_SOPHIA("08", "Housing allow. Paris/Sophia", ExpenseCategory.HOUSING),
    HOUSING_ABROAD("08", "Housing allowance abroad", ExpenseCategory.HOUSING),
    HOUSING_COUNTRY("08", "Housing allowance country", ExpenseCategory.HOUSING),
    ELECTRICAL_DIAGNOSIS("09", "Electrical diagnosis", ExpenseCategory.HOUSING),
    FURNITURE("09", "Furniture", ExpenseCategory.HOUSING),
    INTERNET_HOME_WORK("09", "Pers. Internet - Home work", ExpenseCategory.HOUSING),

    INTERNET_ON_CALL("10", "Pers. Internet - On call", ExpenseCategory.TELECOM),
    PHONE("10", "Phone", ExpenseCategory.TELECOM),
    PHONE_PACKAGE("10", "Phone package", ExpenseCategory.TELECOM),

    BANK_FEES("10", "Bank fees", ExpenseCategory.PROFESSIONAL),
    BOOKS("10", "Books", ExpenseCategory.PROFESSIONAL),
    CUSTOMER_PRESENT("10", "Customer present", ExpenseCategory.PROFESSIONAL),
    POSTAGE("10", "Postage", ExpenseCategory.PROFESSIONAL),
    SEMINAR("10", "Seminar", ExpenseCategory.PROFESSIONAL),
    SMALL_EQUIPMENT("10", "Small equipment", ExpenseCategory.PROFESSIONAL),
    VISA_PASSPORT("10", "Visa - Passport", ExpenseCategory.PROFESSIONAL),

    RELOCATION("10", "Relocation", ExpenseCategory.MOBILITY),
    MOVE_ACCOMPANYING_MEASURES("11", "Move accompanying measures", ExpenseCategory.MOBILITY),

    OTHER("99", "Autre type de frais", ExpenseCategory.OTHER);

    val displayLabel: String get() = "$code $label"

    companion object {
        fun forCategory(category: ExpenseCategory): List<ExpenseType> =
            entries.filter { it.category == category }

        fun defaultFor(category: ExpenseCategory): ExpenseType =
            forCategory(category).firstOrNull() ?: OTHER
    }
}

data class Receipt(
    val id: String = UUID.randomUUID().toString(),
    val tripId: String,
    val date: LocalDate,
    val amount: BigDecimal,
    val category: ExpenseCategory,
    val expenseType: ExpenseType = ExpenseType.defaultFor(category),
    val storedFileName: String,
    val mimeType: String,
    val reimbursableAmount: BigDecimal = amount
) {
    init {
        require(amount >= BigDecimal.ZERO)
        require(expenseType.category == category)
        require(storedFileName.isNotBlank())
        require(reimbursableAmount >= BigDecimal.ZERO)
        require(reimbursableAmount <= amount)
    }

    val isCapped: Boolean get() = reimbursableAmount < amount
}

data class ExpenseState(
    val trips: List<Trip> = emptyList(),
    val receipts: List<Receipt> = emptyList(),
    val customExpenseLimits: Map<ExpenseType, BigDecimal> = emptyMap()
)

data class DailySummary(
    val date: LocalDate,
    val mealSpent: BigDecimal,
    val allowance: BigDecimal
) {
    val remaining: BigDecimal get() = allowance - mealSpent
    val ratio: Float
        get() = if (allowance.signum() == 0) {
            if (mealSpent.signum() == 0) 0f else 1f
        } else {
            (mealSpent.toFloat() / allowance.toFloat()).coerceIn(0f, 1f)
        }
}

fun calculateReimbursableAmount(
    receiptAmount: BigDecimal,
    dailyAllowance: BigDecimal,
    alreadySpent: BigDecimal,
    typeLimit: BigDecimal? = null
): BigDecimal {
    val remaining = (dailyAllowance - alreadySpent).coerceAtLeast(BigDecimal.ZERO)
    return applyExpenseLimit(receiptAmount, typeLimit)
        .coerceAtMost(remaining)
        .coerceAtLeast(BigDecimal.ZERO)
}

fun applyExpenseLimit(
    receiptAmount: BigDecimal,
    typeLimit: BigDecimal?
): BigDecimal = typeLimit
    ?.coerceAtLeast(BigDecimal.ZERO)
    ?.let(receiptAmount::coerceAtMost)
    ?: receiptAmount
