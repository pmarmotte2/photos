package fr.notedefrais.app.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import fr.notedefrais.app.data.Receipt
import fr.notedefrais.app.data.Trip
import java.io.File
import java.math.RoundingMode
import java.text.Normalizer
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

object TripEmailExporter {
    fun send(
        context: Context,
        trip: Trip,
        receipts: List<Receipt>,
        sourceFile: (Receipt) -> File
    ): Result<Unit> = runCatching {
        require(receipts.isNotEmpty()) {
            "Ce déplacement ne contient aucun justificatif à exporter."
        }

        val sortedReceipts = sortReceiptsForExport(receipts)
        val attachmentNames = buildAttachmentNames(sortedReceipts)
        val exportDirectory = File(context.cacheDir, "exports/${trip.id}").apply {
            deleteRecursively()
            mkdirs()
        }
        val exportedFiles = sortedReceipts.map { receipt ->
            val source = sourceFile(receipt)
            require(source.exists()) {
                "Le justificatif ${receipt.expenseType.displayLabel} du ${receipt.date} est introuvable."
            }
            File(exportDirectory, attachmentNames.getValue(receipt.id)).also { destination ->
                source.copyTo(destination, overwrite = true)
            }
        }
        val csv = File(
            exportDirectory,
            "Fo_Notes_${trip.name.toSafeFilePart()}_${trip.startDate}_${trip.endDate}.csv"
        ).apply {
            writeText(
                "\uFEFF" + buildCsv(trip, sortedReceipts, attachmentNames),
                Charsets.UTF_8
            )
        }
        val files = listOf(csv) + exportedFiles
        val uris = ArrayList(files.map { file ->
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        })

        val sendIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_SUBJECT, "Fo Notes — ${trip.name}")
            putExtra(Intent.EXTRA_TEXT, buildEmailBody(trip, sortedReceipts))
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newUri(context.contentResolver, "Export Fo Notes", uris.first())
                .also { clip ->
                    uris.drop(1).forEach { uri -> clip.addItem(ClipData.Item(uri)) }
                }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(sendIntent, "Envoyer le déplacement par e-mail")
        )
    }
}

internal fun sortReceiptsForExport(receipts: List<Receipt>): List<Receipt> =
    receipts.sortedWith(
        compareBy<Receipt>(
            { it.date },
            { it.expenseType.code.toIntOrNull() ?: Int.MAX_VALUE },
            { it.expenseType.code },
            { it.expenseType.label.lowercase(Locale.FRANCE) },
            { it.id }
        )
    )

internal fun buildAttachmentNames(receipts: List<Receipt>): Map<String, String> {
    val occurrences = mutableMapOf<String, Int>()
    return buildMap {
        receipts.forEach { receipt ->
            val baseName = "${receipt.expenseType.displayLabel.toSafeFilePart()}_${receipt.date}"
            val occurrence = occurrences.getOrDefault(baseName, 0) + 1
            occurrences[baseName] = occurrence
            val suffix = if (occurrence == 1) "" else "_$occurrence"
            val extension = receipt.storedFileName
                .substringAfterLast('.', "bin")
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]"), "")
                .ifBlank { "bin" }
            put(receipt.id, "$baseName$suffix.$extension")
        }
    }
}

internal fun buildEmailBody(trip: Trip, receipts: List<Receipt>): String = buildString {
    val dateFormatter = DateTimeFormatter
        .ofLocalizedDate(FormatStyle.FULL)
        .withLocale(Locale.FRANCE)
    appendLine("Bonjour,")
    appendLine()
    appendLine("Veuillez trouver l’export Fo Notes du déplacement « ${trip.name} ».")
    appendLine("Période : du ${trip.startDate} au ${trip.endDate}")
    appendLine("Statut : ${trip.status.label}")
    trip.submittedDate?.let { appendLine("Soumise le : $it") }
    appendLine()
    receipts.groupBy { it.date }.forEach { (date, dailyReceipts) ->
        appendLine(date.format(dateFormatter).replaceFirstChar { it.titlecase(Locale.FRANCE) })
        dailyReceipts.forEach { receipt ->
            append("• ${receipt.expenseType.displayLabel} — ${receipt.amount.toFrenchMoney()}")
            if (receipt.isCapped) {
                append(" (remboursable : ${receipt.reimbursableAmount.toFrenchMoney()})")
            }
            appendLine()
        }
        appendLine()
    }
    val total = receipts.fold(java.math.BigDecimal.ZERO) { sum, receipt -> sum + receipt.amount }
    val reimbursable = receipts.fold(java.math.BigDecimal.ZERO) { sum, receipt ->
        sum + receipt.reimbursableAmount
    }
    appendLine("Total déclaré : ${total.toFrenchMoney()}")
    appendLine("Total remboursable : ${reimbursable.toFrenchMoney()}")
    appendLine()
    appendLine("Le récapitulatif CSV et les justificatifs sont joints à cet e-mail.")
}

internal fun buildCsv(
    trip: Trip,
    receipts: List<Receipt>,
    attachmentNames: Map<String, String>
): String = buildString {
    appendLine("Déplacement;${trip.name.toCsvCell()}")
    appendLine("Début;${trip.startDate}")
    appendLine("Fin;${trip.endDate}")
    appendLine("Statut;${trip.status.label.toCsvCell()}")
    trip.submittedDate?.let { appendLine("Date de soumission;$it") }
    appendLine()
    appendLine("Date;Code;Libellé;Montant TTC;Montant remboursable;Pièce jointe")
    var previousDate = receipts.firstOrNull()?.date
    receipts.forEach { receipt ->
        if (previousDate != null && receipt.date != previousDate) appendLine()
        appendLine(
            listOf(
                receipt.date.toString(),
                receipt.expenseType.code,
                receipt.expenseType.label.toCsvCell(),
                receipt.amount.toFrenchNumber(),
                receipt.reimbursableAmount.toFrenchNumber(),
                attachmentNames.getValue(receipt.id).toCsvCell()
            ).joinToString(";")
        )
        previousDate = receipt.date
    }
}

private fun String.toSafeFilePart(): String = Normalizer
    .normalize(this, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "")
    .replace(Regex("[^A-Za-z0-9]+"), "_")
    .trim('_')
    .take(80)
    .ifBlank { "justificatif" }

private fun String.toCsvCell(): String =
    if (contains(';') || contains('"') || contains('\n')) {
        "\"${replace("\"", "\"\"")}\""
    } else {
        this
    }

private fun java.math.BigDecimal.toFrenchNumber(): String =
    setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',')

private fun java.math.BigDecimal.toFrenchMoney(): String = "${toFrenchNumber()} €"
