package fr.notedefrais.app.export

import fr.notedefrais.app.data.ExpenseType
import fr.notedefrais.app.data.Receipt
import fr.notedefrais.app.data.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.time.LocalDate
import java.util.zip.ZipFile

class TripEmailExporterTest {
    @Test
    fun receiptsAreSortedByDayThenAtosCode() {
        val receipts = listOf(
            receipt("taxi", LocalDate.of(2026, 7, 23), ExpenseType.TAXI),
            receipt("lunch", LocalDate.of(2026, 7, 22), ExpenseType.LUNCH),
            receipt(
                "transport",
                LocalDate.of(2026, 7, 23),
                ExpenseType.TRANSPORT_OCCASIONAL
            )
        )

        val sorted = sortReceiptsForExport(receipts)

        assertEquals(listOf("lunch", "transport", "taxi"), sorted.map { it.id })
    }

    @Test
    fun lunchIsAlwaysExportedBeforeDinnerOnTheSameDay() {
        val date = LocalDate.of(2026, 7, 22)
        val sorted = sortReceiptsForExport(
            listOf(
                receipt("dinner", date, ExpenseType.DINNER_PARIS),
                receipt("lunch", date, ExpenseType.LUNCH)
            )
        )

        assertEquals(listOf("lunch", "dinner"), sorted.map { it.id })
    }

    @Test
    fun duplicateAttachmentsReceiveAnIncrementalSuffix() {
        val date = LocalDate.of(2026, 7, 22)
        val receipts = listOf(
            receipt("first", date, ExpenseType.LUNCH),
            receipt("second", date, ExpenseType.LUNCH)
        )

        val names = buildAttachmentNames(receipts)

        assertEquals("06_Lunch_2026-07-22.pdf", names.getValue("first"))
        assertEquals("06_Lunch_2026-07-22_2.pdf", names.getValue("second"))
    }

    @Test
    fun existingPdfKeepsPdfExtensionInExport() {
        val date = LocalDate.of(2026, 7, 22)
        val pdfReceipt = receipt(
            id = "invoice",
            date = date,
            type = ExpenseType.LUNCH,
            mimeType = "application/pdf",
            storedFileName = "invoice.pdf"
        )

        val names = buildAttachmentNames(listOf(pdfReceipt))

        assertEquals("06_Lunch_2026-07-22.pdf", names.getValue("invoice"))
    }

    @Test
    fun csvSeparatesDaysAndIncludesExplicitAttachmentNames() {
        val trip = Trip(
            name = "Les Clayes",
            startDate = LocalDate.of(2026, 7, 22),
            endDate = LocalDate.of(2026, 7, 23)
        )
        val receipts = sortReceiptsForExport(
            listOf(
                receipt("taxi", LocalDate.of(2026, 7, 23), ExpenseType.TAXI),
                receipt("lunch", LocalDate.of(2026, 7, 22), ExpenseType.LUNCH)
            )
        )
        val names = buildAttachmentNames(receipts)
        val lines = buildExportLines(receipts, names)

        val csv = buildCsv(trip, lines)

        assertTrue(csv.contains("Date;Code;Libellé;Montant TTC;Montant remboursable;Pièce jointe"))
        assertTrue(csv.contains("06_Lunch_2026-07-22.pdf"))
        assertTrue(Regex("2026-07-22.*\\R\\R2026-07-23", RegexOption.DOT_MATCHES_ALL).containsMatchIn(csv))
    }

    @Test
    fun twoCumulatedMealsOnTheSameDayBecomeOneExportLine() {
        val date = LocalDate.of(2026, 7, 22)
        val receipts = listOf(
            receipt(
                "combined-first",
                date,
                ExpenseType.LUNCH_DINNER_PARIS,
                amount = BigDecimal("18.50")
            ),
            receipt(
                "combined-second",
                date,
                ExpenseType.LUNCH_DINNER_PARIS,
                amount = BigDecimal("21.50")
            )
        )
        val names = buildAttachmentNames(receipts)

        val lines = buildExportLines(receipts, names)

        assertEquals(1, lines.size)
        assertEquals(BigDecimal("40.00"), lines.single().amount)
        assertEquals(
            listOf(
                "06_Lnch_Dnnr_Paris_cumulated_2026-07-22.pdf",
                "06_Lnch_Dnnr_Paris_cumulated_2026-07-22_2.pdf"
            ),
            lines.single().attachmentNames
        )
        val trip = Trip(
            name = "Paris",
            startDate = date,
            endDate = date
        )
        val csv = buildCsv(trip, lines)
        assertEquals(1, csv.lineSequence().count { it.contains("Lnch+Dnnr Paris") })
        assertTrue(csv.contains("40,00"))
        assertTrue(csv.contains(lines.single().attachmentNames.joinToString(" | ")))
    }

    @Test
    fun archiveNameContainsTripNameAndExportDate() {
        assertEquals(
            "Deplacement_Paris_2026-07-27.zip",
            buildArchiveName("Déplacement Paris", LocalDate.of(2026, 7, 27))
        )
    }

    @Test
    fun zipContainsCsvAndAllRenamedReceipts() {
        val directory = Files.createTempDirectory("fo-notes-export").toFile()
        try {
            val csv = File(directory, "recapitulatif.csv").apply { writeText("Date;Montant") }
            val receipt = File(directory, "06_Lunch_2026-07-22.jpg").apply {
                writeBytes(byteArrayOf(1, 2, 3))
            }
            val archive = File(directory, "Les_Clayes_2026-07-27.zip")

            writeZipArchive(archive, listOf(csv, receipt))

            ZipFile(archive).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toList()
                assertEquals(
                    listOf("recapitulatif.csv", "06_Lunch_2026-07-22.jpg"),
                    names
                )
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun receipt(
        id: String,
        date: LocalDate,
        type: ExpenseType,
        amount: BigDecimal = BigDecimal("12.50"),
        mimeType: String = "image/jpeg",
        storedFileName: String = "$id.jpg"
    ) = Receipt(
        id = id,
        tripId = "trip",
        date = date,
        amount = amount,
        category = type.category,
        expenseType = type,
        storedFileName = storedFileName,
        mimeType = mimeType
    )
}
