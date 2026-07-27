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

        val loadedState = ExpenseState(
            trips = trips.sortedByDescending { it.startDate },
            receipts = receipts.sortedWith(compareByDescending<Receipt> { it.date }.thenBy { it.category }),
            customExpenseLimits = loadExpenseLimits()
        )
        if (preferences.getInt(KEY_LIMIT_POLICY_VERSION, 0) >= LIMIT_POLICY_VERSION) {
            return loadedState
        }
        return runCatching {
            recalculateDraftReceipts(loadedState).also { migrated ->
                persist(migrated)
                preferences.edit()
                    .putInt(KEY_LIMIT_POLICY_VERSION, LIMIT_POLICY_VERSION)
                    .apply()
            }
        }.getOrDefault(loadedState)
    }

    fun saveTrip(trip: Trip, state: ExpenseState): ExpenseState {
        val updated = state.copy(trips = (state.trips + trip).sortedByDescending { it.startDate })
        persist(updated)
        return updated
    }

    fun updateTripTracking(
        tripId: String,
        status: TripStatus,
        submittedDate: LocalDate?,
        state: ExpenseState
    ): ExpenseState {
        val trip = state.trips.firstOrNull { it.id == tripId }
            ?: error("Le déplacement est introuvable.")
        val updatedTrip = trip.copy(
            status = status,
            submittedDate = if (status == TripStatus.DRAFT) null else submittedDate
        )
        val updated = state.copy(
            trips = state.trips
                .map { if (it.id == tripId) updatedTrip else it }
                .sortedByDescending { it.startDate }
        )
        persist(updated)
        return updated
    }

    fun saveExpenseLimits(
        limits: Map<ExpenseType, BigDecimal>,
        state: ExpenseState
    ): ExpenseState {
        require(limits.values.all { it >= BigDecimal.ZERO })
        val updated = recalculateDraftReceipts(
            state.copy(customExpenseLimits = limits)
        )
        persist(updated)
        return updated
    }

    fun importReceipt(
        source: Uri,
        trip: Trip,
        date: LocalDate,
        amount: BigDecimal,
        expenseType: ExpenseType,
        mimeType: String,
        state: ExpenseState
    ): ExpenseState {
        val category = expenseType.category
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
            val reimbursableAmount = calculateReceiptReimbursement(
                amount = amount,
                expenseType = expenseType,
                trip = trip,
                date = date,
                previousReceipts = state.receipts,
                customLimits = state.customExpenseLimits
            )

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
                expenseType = expenseType,
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

    fun updateReceipt(
        receipt: Receipt,
        trip: Trip,
        date: LocalDate,
        amount: BigDecimal,
        expenseType: ExpenseType,
        state: ExpenseState
    ): ExpenseState {
        require(state.receipts.any { it.id == receipt.id })
        require(receipt.tripId == trip.id)
        require(!date.isBefore(trip.startDate) && !date.isAfter(trip.endDate))
        require(amount > BigDecimal.ZERO)

        val original = File(originalsDirectory, receipt.storedFileName)
        require(original.exists()) { "Le justificatif original est introuvable." }

        val destination = fileFor(receipt)
        val replacement = File(receiptsDirectory, ".${receipt.storedFileName}.editing")
        val category = expenseType.category
        val reimbursableAmount = calculateReceiptReimbursement(
            amount = amount,
            expenseType = expenseType,
            trip = trip,
            date = date,
            previousReceipts = state.receipts.filterNot { it.id == receipt.id },
            customLimits = state.customExpenseLimits
        )

        try {
            if (reimbursableAmount < amount) {
                ReceiptAnnotator.annotate(
                    source = original,
                    destination = replacement,
                    mimeType = receipt.mimeType,
                    reimbursableAmount = reimbursableAmount
                )
            } else {
                original.copyTo(replacement, overwrite = true)
            }
            replacement.copyTo(destination, overwrite = true)
            replacement.delete()

            val updatedReceipt = receipt.copy(
                date = date,
                amount = amount,
                category = category,
                expenseType = expenseType,
                reimbursableAmount = reimbursableAmount
            )
            val updated = state.copy(
                receipts = state.receipts
                    .map { if (it.id == receipt.id) updatedReceipt else it }
                    .sortedWith(compareByDescending<Receipt> { it.date }.thenBy { it.category })
            )
            persist(updated)
            return updated
        } catch (error: Throwable) {
            replacement.delete()
            throw error
        }
    }

    private fun calculateReceiptReimbursement(
        amount: BigDecimal,
        expenseType: ExpenseType,
        trip: Trip,
        date: LocalDate,
        previousReceipts: List<Receipt>,
        customLimits: Map<ExpenseType, BigDecimal>
    ): BigDecimal {
        val alreadySpentForType = previousReceipts
            .filter {
                it.tripId == trip.id &&
                    it.date == date &&
                    it.expenseType == expenseType
            }
            .fold(BigDecimal.ZERO) { total, previous ->
                total + previous.reimbursableAmount
            }
        val alreadySpentForMeals = previousReceipts
            .filter {
                it.tripId == trip.id &&
                    it.date == date &&
                    it.category == ExpenseCategory.MEAL
            }
            .fold(BigDecimal.ZERO) { total, previous ->
                total + previous.reimbursableAmount
            }
        return calculateReceiptReimbursableAmount(
            receiptAmount = amount,
            expenseType = expenseType,
            dailyMealAllowance = trip.dailyMealAllowance,
            alreadySpentForType = alreadySpentForType,
            alreadySpentForMeals = alreadySpentForMeals,
            customTypeLimit = customLimits[expenseType]
        )
    }

    private fun recalculateDraftReceipts(state: ExpenseState): ExpenseState {
        val recalculatedById = mutableMapOf<String, Receipt>()
        val replacements = mutableListOf<Pair<File, File>>()
        try {
            state.trips
                .filter { it.status == TripStatus.DRAFT }
                .forEach { trip ->
                    val previousReceipts = mutableListOf<Receipt>()
                    state.receipts
                        .filter { it.tripId == trip.id }
                        .sortedBy { it.date }
                        .forEach { receipt ->
                            val reimbursableAmount = calculateReceiptReimbursement(
                                amount = receipt.amount,
                                expenseType = receipt.expenseType,
                                trip = trip,
                                date = receipt.date,
                                previousReceipts = previousReceipts,
                                customLimits = state.customExpenseLimits
                            )
                            val recalculated = receipt.copy(
                                reimbursableAmount = reimbursableAmount
                            )
                            val original = File(originalsDirectory, receipt.storedFileName)
                            require(original.exists()) {
                                "Le justificatif original ${receipt.storedFileName} est introuvable."
                            }
                            val replacement = File(
                                receiptsDirectory,
                                ".${receipt.storedFileName}.limits"
                            )
                            if (reimbursableAmount < receipt.amount) {
                                ReceiptAnnotator.annotate(
                                    source = original,
                                    destination = replacement,
                                    mimeType = receipt.mimeType,
                                    reimbursableAmount = reimbursableAmount
                                )
                            } else {
                                original.copyTo(replacement, overwrite = true)
                            }
                            replacements += replacement to fileFor(receipt)
                            recalculatedById[receipt.id] = recalculated
                            previousReceipts += recalculated
                        }
                }

            replacements.forEach { (replacement, destination) ->
                replacement.copyTo(destination, overwrite = true)
                replacement.delete()
            }
            return state.copy(
                receipts = state.receipts
                    .map { recalculatedById[it.id] ?: it }
                    .sortedWith(compareByDescending<Receipt> { it.date }.thenBy { it.category })
            )
        } catch (error: Throwable) {
            replacements.forEach { (replacement, _) -> replacement.delete() }
            throw error
        }
    }

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
            .putString(KEY_EXPENSE_LIMITS, state.customExpenseLimits.toJson().toString())
            .apply()
    }

    private fun loadExpenseLimits(): Map<ExpenseType, BigDecimal> = runCatching {
        val json = JSONObject(preferences.getString(KEY_EXPENSE_LIMITS, "{}").orEmpty())
        buildMap {
            json.keys().forEach { key ->
                val type = runCatching { ExpenseType.valueOf(key) }.getOrNull()
                val value = runCatching { BigDecimal(json.getString(key)) }.getOrNull()
                if (type != null && value != null && value >= BigDecimal.ZERO) put(type, value)
            }
        }
    }.getOrDefault(emptyMap())

    private fun Map<ExpenseType, BigDecimal>.toJson() = JSONObject().apply {
        forEach { (type, limit) -> put(type.name, limit.toPlainString()) }
    }

    private fun Trip.toJson() = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("startDate", startDate.toString())
        .put("endDate", endDate.toString())
        .put("dailyMealAllowance", dailyMealAllowance.toPlainString())
        .put("status", status.name)
        .put("submittedDate", submittedDate?.toString())

    private fun Receipt.toJson() = JSONObject()
        .put("id", id)
        .put("tripId", tripId)
        .put("date", date.toString())
        .put("amount", amount.toPlainString())
        .put("category", category.name)
        .put("expenseType", expenseType.name)
        .put("storedFileName", storedFileName)
        .put("mimeType", mimeType)
        .put("reimbursableAmount", reimbursableAmount.toPlainString())

    private fun JSONObject.toTrip(): Trip {
        val status = runCatching {
            TripStatus.valueOf(optString("status", TripStatus.DRAFT.name))
        }.getOrDefault(TripStatus.DRAFT)
        val submittedDate = optString("submittedDate")
            .takeIf { it.isNotBlank() && it != "null" }
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val safeStatus = if (status != TripStatus.DRAFT && submittedDate == null) {
            TripStatus.DRAFT
        } else {
            status
        }
        return Trip(
            id = getString("id"),
            name = getString("name"),
            startDate = LocalDate.parse(getString("startDate")),
            endDate = LocalDate.parse(getString("endDate")),
            dailyMealAllowance = BigDecimal(getString("dailyMealAllowance")),
            status = safeStatus,
            submittedDate = if (safeStatus == TripStatus.DRAFT) null else submittedDate
        )
    }

    private fun JSONObject.toReceipt(): Receipt {
        val category = ExpenseCategory.valueOf(getString("category"))
        val expenseType = runCatching {
            ExpenseType.valueOf(getString("expenseType"))
        }.getOrElse {
            ExpenseType.defaultFor(category)
        }
        return Receipt(
            id = getString("id"),
            tripId = getString("tripId"),
            date = LocalDate.parse(getString("date")),
            amount = BigDecimal(getString("amount")),
            category = category,
            expenseType = expenseType,
            storedFileName = getString("storedFileName"),
            mimeType = getString("mimeType"),
            reimbursableAmount = BigDecimal(optString("reimbursableAmount", getString("amount")))
        )
    }

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
        private const val KEY_EXPENSE_LIMITS = "expense_limits"
        private const val KEY_LIMIT_POLICY_VERSION = "limit_policy_version"
        private const val LIMIT_POLICY_VERSION = 1
        private val FILE_DATE = DateTimeFormatter.ISO_LOCAL_DATE
    }
}
