package fr.notedefrais.app.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

class ExpenseRepository(private val context: Context) {
    private val preferences = context.getSharedPreferences("expense_data", Context.MODE_PRIVATE)
    private val receiptsDirectory = File(context.filesDir, "receipts").apply { mkdirs() }
    private val originalsDirectory = File(context.filesDir, "receipt_originals").apply { mkdirs() }

    fun load(): ExpenseState {
        val trips = runCatching {
            val array = JSONArray(preferences.getString(KEY_TRIPS, "[]"))
            buildList {
                for (index in 0 until array.length()) add(array.getJSONObject(index).toTrip())
            }
        }.getOrDefault(emptyList())

        val receipts = runCatching {
            val array = JSONArray(preferences.getString(KEY_RECEIPTS, "[]"))
            buildList {
                for (index in 0 until array.length()) {
                    val receipt = array.getJSONObject(index).toReceipt()
                    if (File(receiptsDirectory, receipt.storedFileName).exists()) add(receipt)
                }
            }
        }.getOrDefault(emptyList())

        return ExpenseState(
            trips = trips.sortedByDescending { it.startDate },
            receipts = receipts.sortedWith(compareByDescending<Receipt> { it.date }.thenBy { it.category })
        )
    }

    fun saveTrip(trip: Trip, state: ExpenseState): ExpenseState {
        val updated = state.copy(trips = (state.trips + trip).sortedByDescending { it.startDate })
        persist(updated)
        return updated
    }

    fun importReceipt(
        source: Uri,
        trip: Trip,
        date: LocalDate,
        amount: BigDecimal,
        category: ExpenseCategory,
        mimeType: String,
        state: ExpenseState
    ): ExpenseState {
        require(!date.isBefore(trip.startDate) && !date.isAfter(trip.endDate))
        val extension = extensionFor(mimeType)
        val genericName = buildString {
            append(date.format(FILE_DATE))
            append("_")
            append(category.name.lowercase())
            append("_")
            append(UUID.randomUUID().toString().take(8))
            append(".")
            append(extension)
        }
        val destination = File(receiptsDirectory, genericName)
        val original = File(originalsDirectory, genericName)

        try {
            context.contentResolver.openInputStream(source).use { input ->
                requireNotNull(input) { "Le fichier sélectionné est inaccessible." }
                original.outputStream().use { output -> input.copyTo(output) }
            }
            val alreadySpent = state.receipts
                .filter {
                    it.tripId == trip.id &&
                        it.date == date &&
                        it.category == ExpenseCategory.MEAL
                }
                .fold(BigDecimal.ZERO) { total, receipt -> total + receipt.amount }
            val reimbursableAmount = if (category == ExpenseCategory.MEAL) {
                calculateReimbursableAmount(amount, trip.dailyMealAllowance, alreadySpent)
            } else {
                amount
            }

            if (reimbursableAmount < amount) {
                ReceiptAnnotator.annotate(
                    source = original,
                    destination = destination,
                    mimeType = mimeType,
                    reimbursableAmount = reimbursableAmount
                )
            } else {
                original.copyTo(destination, overwrite = true)
            }
            val receipt = Receipt(
                tripId = trip.id,
                date = date,
                amount = amount,
                category = category,
                storedFileName = genericName,
                mimeType = mimeType.ifBlank { mimeTypeFor(extension) },
                reimbursableAmount = reimbursableAmount
            )
            val updated = state.copy(receipts = (state.receipts + receipt)
                .sortedWith(compareByDescending<Receipt> { it.date }.thenBy { it.category }))
            persist(updated)
            return updated
        } catch (error: Throwable) {
            destination.delete()
            original.delete()
            throw error
        }
    }

    fun fileFor(receipt: Receipt): File = File(receiptsDirectory, receipt.storedFileName)

    fun deleteReceipt(receipt: Receipt, state: ExpenseState): ExpenseState {
        fileFor(receipt).delete()
        File(originalsDirectory, receipt.storedFileName).delete()
        val updated = state.copy(receipts = state.receipts.filterNot { it.id == receipt.id })
        persist(updated)
        return updated
    }

    private fun persist(state: ExpenseState) {
        val tripsJson = JSONArray().apply {
            state.trips.forEach { put(it.toJson()) }
        }
        val receiptsJson = JSONArray().apply {
            state.receipts.forEach { put(it.toJson()) }
        }
        preferences.edit()
            .putString(KEY_TRIPS, tripsJson.toString())
            .putString(KEY_RECEIPTS, receiptsJson.toString())
            .apply()
    }

    private fun Trip.toJson() = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("startDate", startDate.toString())
        .put("endDate", endDate.toString())
        .put("dailyMealAllowance", dailyMealAllowance.toPlainString())

    private fun Receipt.toJson() = JSONObject()
        .put("id", id)
        .put("tripId", tripId)
        .put("date", date.toString())
        .put("amount", amount.toPlainString())
        .put("category", category.name)
        .put("storedFileName", storedFileName)
        .put("mimeType", mimeType)
        .put("reimbursableAmount", reimbursableAmount.toPlainString())

    private fun JSONObject.toTrip() = Trip(
        id = getString("id"),
        name = getString("name"),
        startDate = LocalDate.parse(getString("startDate")),
        endDate = LocalDate.parse(getString("endDate")),
        dailyMealAllowance = BigDecimal(getString("dailyMealAllowance"))
    )

    private fun JSONObject.toReceipt() = Receipt(
        id = getString("id"),
        tripId = getString("tripId"),
        date = LocalDate.parse(getString("date")),
        amount = BigDecimal(getString("amount")),
        category = ExpenseCategory.valueOf(getString("category")),
        storedFileName = getString("storedFileName"),
        mimeType = getString("mimeType"),
        reimbursableAmount = BigDecimal(optString("reimbursableAmount", getString("amount")))
    )

    private fun extensionFor(mimeType: String) = when (mimeType.lowercase()) {
        "application/pdf" -> "pdf"
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
    }

    private fun mimeTypeFor(extension: String) = when (extension) {
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    companion object {
        private const val KEY_TRIPS = "trips"
        private const val KEY_RECEIPTS = "receipts"
        private val FILE_DATE = DateTimeFormatter.ISO_LOCAL_DATE
    }
}
