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
    fun duplicateAttachmentsReceiveAnIncrementalSuffix() {
        val date = LocalDate.of(2026, 7, 22)
        val receipts = listOf(
            receipt("first", date, ExpenseType.LUNCH),
            receipt("second", date, ExpenseType.LUNCH)
        )

        val names = buildAttachmentNames(receipts)

        assertEquals("06_Lunch_2026-07-22.jpg", names.getValue("first"))
        assertEquals("06_Lunch_2026-07-22_2.jpg", names.getValue("second"))
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

        val csv = buildCsv(trip, receipts, names)

        assertTrue(csv.contains("Date;Code;Libellé;Montant TTC;Montant remboursable;Pièce jointe"))
        assertTrue(csv.contains("06_Lunch_2026-07-22.jpg"))
        assertTrue(Regex("2026-07-22.*\\R\\R2026-07-23", RegexOption.DOT_MATCHES_ALL).containsMatchIn(csv))
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
        type: ExpenseType
    ) = Receipt(
        id = id,
        tripId = "trip",
        date = date,
        amount = BigDecimal("12.50"),
        category = type.category,
        expenseType = type,
        storedFileName = "$id.jpg",
        mimeType = "image/jpeg"
    )
}
