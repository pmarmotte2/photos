package fr.notedefrais.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import fr.notedefrais.app.data.DailySummary
import fr.notedefrais.app.data.ExpenseCategory
import fr.notedefrais.app.data.ExpenseRepository
import fr.notedefrais.app.data.ExpenseState
import fr.notedefrais.app.data.ExpenseType
import fr.notedefrais.app.data.Receipt
import fr.notedefrais.app.data.Trip
import fr.notedefrais.app.data.TripStatus
import fr.notedefrais.app.data.calculateDailyMealAllowance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigDecimal
import java.time.LocalDate

class ExpenseViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ExpenseRepository(application)
    private val _state = MutableStateFlow(repository.load())
    val state: StateFlow<ExpenseState> = _state.asStateFlow()

    fun createTrip(
        name: String,
        startDate: LocalDate,
        endDate: LocalDate,
        dailyMealAllowance: BigDecimal
    ): Result<Unit> = runCatching {
        _state.value = repository.saveTrip(
            Trip(
                name = name.trim(),
                startDate = startDate,
                endDate = endDate,
                dailyMealAllowance = dailyMealAllowance
            ),
            _state.value
        )
    }

    fun addReceipt(
        source: Uri,
        trip: Trip,
        date: LocalDate,
        amount: BigDecimal,
        expenseType: ExpenseType,
        mimeType: String
    ): Result<Unit> = runCatching {
        _state.value = repository.importReceipt(
            source, trip, date, amount, expenseType, mimeType, _state.value
        )
    }

    fun deleteReceipt(receipt: Receipt) {
        _state.value = repository.deleteReceipt(receipt, _state.value)
    }

    fun updateReceipt(
        receipt: Receipt,
        trip: Trip,
        date: LocalDate,
        amount: BigDecimal,
        expenseType: ExpenseType
    ): Result<Unit> = runCatching {
        _state.value = repository.updateReceipt(
            receipt = receipt,
            trip = trip,
            date = date,
            amount = amount,
            expenseType = expenseType,
            state = _state.value
        )
    }

    fun saveExpenseLimits(limits: Map<ExpenseType, BigDecimal>): Result<Unit> = runCatching {
        _state.value = repository.saveExpenseLimits(limits, _state.value)
    }

    fun updateTripTracking(
        tripId: String,
        status: TripStatus,
        submittedDate: LocalDate?
    ): Result<Unit> = runCatching {
        _state.value = repository.updateTripTracking(
            tripId = tripId,
            status = status,
            submittedDate = submittedDate,
            state = _state.value
        )
    }

    fun receiptsFor(tripId: String): List<Receipt> =
        _state.value.receipts.filter { it.tripId == tripId }

    fun dailySummaries(trip: Trip): List<DailySummary> {
        val receipts = receiptsFor(trip.id)
        return generateSequence(trip.startDate) { previous ->
            previous.plusDays(1).takeUnless { it.isAfter(trip.endDate) }
        }.map { date ->
            val dailyReceipts = receipts.filter { it.date == date }
            DailySummary(
                date = date,
                mealSpent = dailyReceipts
                    .filter { it.category == ExpenseCategory.MEAL }
                    .fold(BigDecimal.ZERO) { total, receipt -> total + receipt.amount },
                allowance = calculateDailyMealAllowance(
                    baseAllowance = trip.dailyMealAllowance,
                    mealTypes = dailyReceipts.map { it.expenseType },
                    customLimits = _state.value.customExpenseLimits
                )
            )
        }.toList()
    }

    fun fileFor(receipt: Receipt) = repository.fileFor(receipt)
}
