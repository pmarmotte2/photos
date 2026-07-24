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
    MEAL("Repas"),
    TRANSPORT("Transport"),
    HOTEL("Hôtel"),
    OTHER("Autre")
}

data class Receipt(
    val id: String = UUID.randomUUID().toString(),
    val tripId: String,
    val date: LocalDate,
    val amount: BigDecimal,
    val category: ExpenseCategory,
    val storedFileName: String,
    val mimeType: String,
    val reimbursableAmount: BigDecimal = amount
) {
    init {
        require(amount >= BigDecimal.ZERO)
        require(storedFileName.isNotBlank())
        require(reimbursableAmount >= BigDecimal.ZERO)
        require(reimbursableAmount <= amount)
    }

    val isCapped: Boolean get() = reimbursableAmount < amount
}

data class ExpenseState(
    val trips: List<Trip> = emptyList(),
    val receipts: List<Receipt> = emptyList()
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
    alreadySpent: BigDecimal
): BigDecimal {
    val remaining = (dailyAllowance - alreadySpent).coerceAtLeast(BigDecimal.ZERO)
    return receiptAmount.coerceAtMost(remaining).coerceAtLeast(BigDecimal.ZERO)
}
