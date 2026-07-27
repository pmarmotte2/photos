package fr.notedefrais.app.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import fr.notedefrais.app.data.ExpenseType
import fr.notedefrais.app.data.Receipt
import fr.notedefrais.app.data.Trip
import fr.notedefrais.app.data.isDinner
import fr.notedefrais.app.data.isLunch
import fr.notedefrais.app.data.isLunchDinner
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
        val exportLines = buildExportLines(sortedReceipts, attachmentNames)
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
                "\uFEFF" + buildCsv(trip, exportLines),
                Charsets.UTF_8
            )
        }
        val files = listOf(csv) + exportedFiles
        val archive = File(
            exportDirectory,
            buildArchiveName(trip.name, LocalDate.now())
        )
        writeZipArchive(archive, files)
        val archiveUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            archive
        )

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_SUBJECT, "Fo Notes — ${trip.name}")
            putExtra(Intent.EXTRA_TEXT, buildEmailBody(trip, exportLines))
            putExtra(Intent.EXTRA_STREAM, archiveUri)
            clipData = ClipData.newUri(
                context.contentResolver,
                "Archive Fo Notes",
                archiveUri
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(sendIntent, "Envoyer le déplacement par e-mail")
        )
    }
}

internal fun buildArchiveName(tripName: String, exportDate: LocalDate): String =
    "${tripName.toSafeFilePart()}_${exportDate}.zip"

internal fun writeZipArchive(destination: File, files: List<File>) {
    require(files.isNotEmpty()) { "L’archive ne peut pas être vide." }
    ZipOutputStream(destination.outputStream().buffered()).use { zip ->
        files.forEach { file ->
            require(file.isFile) { "Le fichier ${file.name} est introuvable." }
            zip.putNextEntry(ZipEntry(file.name))
            file.inputStream().buffered().use { input -> input.copyTo(zip) }
            zip.closeEntry()
        }
    }
}

internal fun sortReceiptsForExport(receipts: List<Receipt>): List<Receipt> =
    receipts.sortedWith(
        compareBy<Receipt>(
            { it.date },
            { it.expenseType.code.toIntOrNull() ?: Int.MAX_VALUE },
            { it.expenseType.code },
            { it.expenseType.exportMealOrder() },
            { it.expenseType.label.lowercase(Locale.FRANCE) },
            { it.id }
        )
    )

private fun ExpenseType.exportMealOrder(): Int = when {
    this == ExpenseType.LUNCH || this == ExpenseType.LUNCH_DRINK -> 10
    isLunchDinner -> 20
    isDinner || this == ExpenseType.DINNER_DRINK -> 30
    else -> 0
}

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

internal data class ExportLine(
    val date: LocalDate,
    val expenseType: ExpenseType,
    val amount: BigDecimal,
    val reimbursableAmount: BigDecimal,
    val attachmentNames: List<String>
) {
    val isCapped: Boolean get() = reimbursableAmount < amount
}

internal fun buildExportLines(
    receipts: List<Receipt>,
    attachmentNames: Map<String, String>
): List<ExportLine> {
    val sorted = sortReceiptsForExport(receipts)
    val emittedCumulatedDates = mutableSetOf<LocalDate>()
    return buildList {
        sorted.forEach { receipt ->
            if (receipt.expenseType.isLunchDinner) {
                if (!emittedCumulatedDates.add(receipt.date)) return@forEach
                val cumulated = sorted.filter {
                    it.date == receipt.date && it.expenseType.isLunchDinner
                }
                add(
                    ExportLine(
                        date = receipt.date,
                        expenseType = receipt.expenseType,
                        amount = cumulated.sumOf { it.amount },
                        reimbursableAmount = cumulated.sumOf { it.reimbursableAmount },
                        attachmentNames = cumulated.map { attachmentNames.getValue(it.id) }
                    )
                )
            } else {
                add(
                    ExportLine(
                        date = receipt.date,
                        expenseType = receipt.expenseType,
                        amount = receipt.amount,
                        reimbursableAmount = receipt.reimbursableAmount,
                        attachmentNames = listOf(attachmentNames.getValue(receipt.id))
                    )
                )
            }
        }
    }
}

internal fun buildEmailBody(trip: Trip, lines: List<ExportLine>): String = buildString {
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
    lines.groupBy { it.date }.forEach { (date, dailyLines) ->
        appendLine(date.format(dateFormatter).replaceFirstChar { it.titlecase(Locale.FRANCE) })
        dailyLines.forEach { line ->
            append("• ${line.expenseType.displayLabel} — ${line.amount.toFrenchMoney()}")
            if (line.isCapped) {
                append(" (remboursable : ${line.reimbursableAmount.toFrenchMoney()})")
            }
            appendLine()
        }
        appendLine()
    }
    val total = lines.fold(BigDecimal.ZERO) { sum, line -> sum + line.amount }
    val reimbursable = lines.fold(BigDecimal.ZERO) { sum, line ->
        sum + line.reimbursableAmount
    }
    appendLine("Total déclaré : ${total.toFrenchMoney()}")
    appendLine("Total remboursable : ${reimbursable.toFrenchMoney()}")
    appendLine()
    appendLine("Le récapitulatif CSV et les justificatifs sont regroupés dans l’archive ZIP jointe.")
}

internal fun buildCsv(
    trip: Trip,
    lines: List<ExportLine>
): String = buildString {
    appendLine("Déplacement;${trip.name.toCsvCell()}")
    appendLine("Début;${trip.startDate}")
    appendLine("Fin;${trip.endDate}")
    appendLine("Statut;${trip.status.label.toCsvCell()}")
    trip.submittedDate?.let { appendLine("Date de soumission;$it") }
    appendLine()
    appendLine("Date;Code;Libellé;Montant TTC;Montant remboursable;Pièce jointe")
    var previousDate = lines.firstOrNull()?.date
    lines.forEach { line ->
        if (previousDate != null && line.date != previousDate) appendLine()
        appendLine(
            listOf(
                line.date.toString(),
                line.expenseType.code,
                line.expenseType.label.toCsvCell(),
                line.amount.toFrenchNumber(),
                line.reimbursableAmount.toFrenchNumber(),
                line.attachmentNames.joinToString(" | ").toCsvCell()
            ).joinToString(";")
        )
        previousDate = line.date
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
