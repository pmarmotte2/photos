package fr.notedefrais.app.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
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

private const val PDF_SHORT_SIDE = 595
private const val PDF_LONG_SIDE = 842
private const val PDF_MARGIN = 24
private const val MAX_IMAGE_DIMENSION = 2400

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
                if (receipt.isImageDocument()) {
                    convertImageToPdf(source, destination)
                } else {
                    source.copyTo(destination, overwrite = true)
                }
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
            val extension = receipt.exportExtension()
            put(receipt.id, "$baseName$suffix.$extension")
        }
    }
}

private fun Receipt.isImageDocument(): Boolean =
    mimeType.lowercase(Locale.ROOT).startsWith("image/")

private fun Receipt.exportExtension(): String = when {
    isImageDocument() -> "pdf"
    mimeType.equals("application/pdf", ignoreCase = true) -> "pdf"
    else -> storedFileName
        .substringAfterLast('.', "bin")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]"), "")
        .ifBlank { "bin" }
}

internal fun convertImageToPdf(source: File, destination: File) {
    require(source.isFile) { "L’image ${source.name} est introuvable." }
    val bitmap = decodeExportBitmap(source)
    val document = PdfDocument()
    try {
        val landscape = bitmap.width > bitmap.height
        val pageWidth = if (landscape) PDF_LONG_SIDE else PDF_SHORT_SIDE
        val pageHeight = if (landscape) PDF_SHORT_SIDE else PDF_LONG_SIDE
        val page = document.startPage(
            PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        )
        page.canvas.drawColor(Color.WHITE)
        val availableWidth = pageWidth - (PDF_MARGIN * 2f)
        val availableHeight = pageHeight - (PDF_MARGIN * 2f)
        val scale = minOf(
            availableWidth / bitmap.width.toFloat(),
            availableHeight / bitmap.height.toFloat()
        )
        val renderedWidth = bitmap.width * scale
        val renderedHeight = bitmap.height * scale
        val left = (pageWidth - renderedWidth) / 2f
        val top = (pageHeight - renderedHeight) / 2f
        page.canvas.drawBitmap(
            bitmap,
            null,
            RectF(left, top, left + renderedWidth, top + renderedHeight),
            null
        )
        document.finishPage(page)
        destination.outputStream().buffered().use(document::writeTo)
    } finally {
        document.close()
        bitmap.recycle()
    }
}

private fun decodeExportBitmap(source: File): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(source.absolutePath, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) {
        "L’image ${source.name} ne peut pas être lue."
    }
    var sampleSize = 1
    while (
        bounds.outWidth / sampleSize > MAX_IMAGE_DIMENSION ||
        bounds.outHeight / sampleSize > MAX_IMAGE_DIMENSION
    ) {
        sampleSize *= 2
    }
    val decoded = requireNotNull(
        BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    ) {
        "L’image ${source.name} ne peut pas être décodée."
    }
    val rotation = runCatching {
        when (
            ExifInterface(source).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    }.getOrDefault(0f)
    if (rotation == 0f) return decoded

    return Bitmap.createBitmap(
        decoded,
        0,
        0,
        decoded.width,
        decoded.height,
        Matrix().apply { postRotate(rotation) },
        true
    ).also { rotated ->
        if (rotated !== decoded) decoded.recycle()
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
