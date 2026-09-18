package fr.notedefrais.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class ReceiptOcrTest {
    @Test
    fun totalTtc_is_ranked_before_tax_and_subtotal() {
        val candidates = extractAmountCandidates(
            """
            TOTAL HT 32,50
            TVA 10 % 3,25
            TOTAL TTC 35,75 €
            """.trimIndent()
        )

        assertEquals(BigDecimal("35.75"), candidates.first().amount)
        assertEquals(
            listOf(BigDecimal("35.75"), BigDecimal("32.50"), BigDecimal("3.25")),
            candidates.map { it.amount }
        )
    }

    @Test
    fun french_thousands_and_split_euros_are_supported() {
        val candidates = extractAmountCandidates(
            """
            NET À PAYER 1 234,56 EUR
            POURBOIRE 12€50
            """.trimIndent()
        )

        assertEquals(BigDecimal("1234.56"), candidates.first().amount)
        assertEquals(BigDecimal("12.50"), candidates.last().amount)
    }

    @Test
    fun dates_discounts_and_duplicate_amounts_are_not_suggested_twice() {
        val candidates = extractAmountCandidates(
            """
            Date 24.07.2026
            Sous-total 20,00
            Remise -5,00
            Total TTC 20,00 €
            """.trimIndent()
        )

        assertEquals(1, candidates.size)
        assertEquals(BigDecimal("20.00"), candidates.single().amount)
        assertFalse(candidates.any { it.amount == BigDecimal("24.07") })
        assertFalse(candidates.any { it.amount == BigDecimal("5.00") })
    }

    @Test
    fun invoice_date_is_extracted_and_ranked_first() {
        val candidates = extractDateCandidates(
            """
            Livraison prévue le 26/07/2026
            Date de facture : 24/07/2026
            """.trimIndent()
        )

        assertEquals(LocalDate.of(2026, 7, 24), candidates.first().date)
        assertEquals(LocalDate.of(2026, 7, 26), candidates.last().date)
    }

    @Test
    fun short_year_and_french_textual_dates_are_supported() {
        val candidates = extractDateCandidates(
            """
            DATE 24-07-26
            Ticket du 23 juillet 2026
            """.trimIndent()
        )

        assertEquals(
            listOf(LocalDate.of(2026, 7, 24), LocalDate.of(2026, 7, 23)),
            candidates.map { it.date }
        )
    }

    @Test
    fun impossible_dates_are_ignored() {
        val candidates = extractDateCandidates(
            """
            Date 31/02/2026
            Date 32/07/2026
            """.trimIndent()
        )

        assertEquals(emptyList<OcrDateCandidate>(), candidates)
    }
}
