package fr.notedefrais.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

data class OcrAmountCandidate(
    val amount: BigDecimal,
    val sourceLine: String,
    val score: Int
)

data class OcrDateCandidate(
    val date: LocalDate,
    val sourceLine: String,
    val score: Int
)

data class ReceiptOcrResult(
    val amounts: List<OcrAmountCandidate>,
    val dates: List<OcrDateCandidate>
)

object ReceiptOcr {
    suspend fun detect(
        context: Context,
        uri: Uri,
        mimeType: String
    ): ReceiptOcrResult {
        val inputImage = withContext(Dispatchers.IO) {
            if (mimeType.equals("application/pdf", ignoreCase = true)) {
                InputImage.fromBitmap(renderFirstPdfPage(context, uri), 0)
            } else {
                InputImage.fromFilePath(context, uri)
            }
        }

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val text = suspendCancellableCoroutine { continuation ->
                recognizer.process(inputImage)
                    .addOnSuccessListener { result ->
                        if (continuation.isActive) continuation.resume(result.text)
                    }
                    .addOnFailureListener { error ->
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
            }
            ReceiptOcrResult(
                amounts = extractAmountCandidates(text),
                dates = extractDateCandidates(text)
            )
        } finally {
            recognizer.close()
        }
    }

    private fun renderFirstPdfPage(context: Context, uri: Uri): Bitmap {
        val temporaryPdf = File.createTempFile("ocr_", ".pdf", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Le document PDF est inaccessible." }
                temporaryPdf.outputStream().use { output -> input.copyTo(output) }
            }

            ParcelFileDescriptor.open(
                temporaryPdf,
                ParcelFileDescriptor.MODE_READ_ONLY
            ).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    require(renderer.pageCount > 0) { "Le document PDF est vide." }
                    renderer.openPage(0).use { page ->
                        val scale = (MAX_RENDER_WIDTH.toFloat() / page.width)
                            .coerceAtMost(2.5f)
                            .coerceAtLeast(1f)
                        val width = (page.width * scale).roundToInt()
                        val height = (page.height * scale).roundToInt()
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(
                            bitmap,
                            null,
                            null,
                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                        )
                        return bitmap
                    }
                }
            }
        } finally {
            temporaryPdf.delete()
        }
    }

    private const val MAX_RENDER_WIDTH = 2_000
}

internal fun extractDateCandidates(text: String): List<OcrDateCandidate> {
    val candidates = mutableListOf<OcrDateCandidate>()

    text.lineSequence()
        .map { it.trim().replace(Regex("\\s+"), " ") }
        .filter { it.isNotBlank() }
        .forEach { line ->
            val normalized = line.normalizeForDates()
            val score = scoreDateLine(normalized)

            NUMERIC_DATE.findAll(normalized).forEach { match ->
                val date = createDate(
                    day = match.groupValues[1],
                    month = match.groupValues[2],
                    year = match.groupValues[3]
                ) ?: return@forEach
                candidates += OcrDateCandidate(date, line, score)
            }

            TEXTUAL_DATE.findAll(normalized).forEach { match ->
                val month = FRENCH_MONTHS[match.groupValues[2]] ?: return@forEach
                val date = createDate(
                    day = match.groupValues[1],
                    month = month.toString(),
                    year = match.groupValues[3]
                ) ?: return@forEach
                candidates += OcrDateCandidate(date, line, score + 10)
            }
        }

    return candidates
        .groupBy { it.date }
        .map { (_, matches) -> matches.maxBy { it.score } }
        .sortedWith(
            compareByDescending<OcrDateCandidate> { it.score }
                .thenByDescending { it.date }
        )
        .take(MAX_DATE_CANDIDATES)
}

internal fun extractAmountCandidates(text: String): List<OcrAmountCandidate> {
    val candidates = mutableListOf<OcrAmountCandidate>()

    text.lineSequence()
        .map { it.trim().replace(Regex("\\s+"), " ") }
        .filter { it.isNotBlank() }
        .forEach { line ->
            val normalizedLine = line
                .replace('O', '0')
                .replace('o', '0')
            val keywordScore = scoreLine(line)

            DECIMAL_AMOUNT.findAll(normalizedLine).forEach { match ->
                if (normalizedLine.isDateOrNegativeAt(match.range)) return@forEach
                val amount = match.groupValues[1].toAmountOrNull() ?: return@forEach
                if (amount > BigDecimal.ZERO && amount <= MAX_REASONABLE_AMOUNT) {
                    val currencyBonus = if (line.containsCurrencyNear(match.range)) 25 else 0
                    candidates += OcrAmountCandidate(
                        amount = amount,
                        sourceLine = line,
                        score = keywordScore + currencyBonus
                    )
                }
            }

            EURO_SPLIT_AMOUNT.findAll(normalizedLine).forEach { match ->
                val amount = "${match.groupValues[1]}.${match.groupValues[2]}".toBigDecimalOrNull()
                    ?: return@forEach
                if (amount > BigDecimal.ZERO && amount <= MAX_REASONABLE_AMOUNT) {
                    candidates += OcrAmountCandidate(
                        amount = amount,
                        sourceLine = line,
                        score = keywordScore + 30
                    )
                }
            }
        }

    return candidates
        .groupBy { it.amount.stripTrailingZeros() }
        .map { (_, matches) -> matches.maxBy { it.score } }
        .sortedWith(compareByDescending<OcrAmountCandidate> { it.score }
            .thenByDescending { it.amount })
        .take(MAX_CANDIDATES)
}

private fun createDate(day: String, month: String, year: String): LocalDate? {
    val parsedYear = year.toIntOrNull()?.let {
        if (it < 100) {
            if (it >= 70) 1900 + it else 2000 + it
        } else {
            it
        }
    } ?: return null
    return runCatching {
        LocalDate.of(parsedYear, month.toInt(), day.toInt())
    }.getOrNull()
}

private fun String.normalizeForDates(): String =
    lowercase()
        .replace('à', 'a')
        .replace('â', 'a')
        .replace('é', 'e')
        .replace('è', 'e')
        .replace('ê', 'e')
        .replace('û', 'u')
        .replace('ô', 'o')

private fun scoreDateLine(normalizedLine: String): Int = when {
    "date de facture" in normalizedLine || "date facture" in normalizedLine -> 120
    "date" in normalizedLine -> 100
    "facture" in normalizedLine || "ticket" in normalizedLine -> 50
    else -> 0
}

private fun scoreLine(line: String): Int {
    val normalized = line.lowercase()
        .replace('à', 'a')
        .replace('é', 'e')
        .replace('è', 'e')
        .replace('ê', 'e')

    return when {
        STRONG_TOTAL_KEYWORDS.any(normalized::contains) -> 100
        TOTAL_KEYWORDS.any(normalized::contains) -> 70
        TAX_KEYWORDS.any(normalized::contains) -> 30
        else -> 0
    }
}

private fun String.containsCurrencyNear(amountRange: IntRange): Boolean {
    val from = (amountRange.first - 4).coerceAtLeast(0)
    val to = (amountRange.last + 6).coerceAtMost(lastIndex)
    if (to < from) return false
    val surroundings = substring(from, to + 1).lowercase()
    return surroundings.contains('€') || surroundings.contains("eur")
}

private fun String.isDateOrNegativeAt(amountRange: IntRange): Boolean {
    val before = getOrNull(amountRange.first - 1)
    val after = getOrNull(amountRange.last + 1)
    return before in listOf('-', '−') ||
        before in listOf('/', '.') ||
        after in listOf('/', '.')
}

private fun String.toAmountOrNull(): BigDecimal? {
    val compact = replace(" ", "")
    val decimalSeparator = maxOf(compact.lastIndexOf(','), compact.lastIndexOf('.'))
    if (decimalSeparator < 0) return null

    val integerPart = compact.substring(0, decimalSeparator)
        .replace(",", "")
        .replace(".", "")
    val decimalPart = compact.substring(decimalSeparator + 1)
    return "$integerPart.$decimalPart".toBigDecimalOrNull()
}

private val DECIMAL_AMOUNT =
    Regex("""(?<!\d)((?:\d{1,3}(?:[ .]\d{3})+|\d{1,6})[,.]\d{2})(?!\d)""")
private val EURO_SPLIT_AMOUNT =
    Regex("""(?<!\d)(\d{1,5})\s*[€]\s*(\d{2})(?!\d)""")
private val MAX_REASONABLE_AMOUNT = BigDecimal("100000.00")
private const val MAX_CANDIDATES = 6
private const val MAX_DATE_CANDIDATES = 4

private val NUMERIC_DATE =
    Regex("""(?<!\d)([0-3]?\d)[/.\-]([01]?\d)[/.\-](\d{2}|\d{4})(?!\d)""")
private val TEXTUAL_DATE =
    Regex("""(?<!\d)([0-3]?\d)\s+(janvier|janv|fevrier|fevr|mars|avril|avr|mai|juin|juillet|juil|aout|septembre|sept|octobre|oct|novembre|nov|decembre|dec)\.?\s+(\d{2}|\d{4})(?!\d)""")
private val FRENCH_MONTHS = mapOf(
    "janvier" to 1, "janv" to 1,
    "fevrier" to 2, "fevr" to 2,
    "mars" to 3,
    "avril" to 4, "avr" to 4,
    "mai" to 5,
    "juin" to 6,
    "juillet" to 7, "juil" to 7,
    "aout" to 8,
    "septembre" to 9, "sept" to 9,
    "octobre" to 10, "oct" to 10,
    "novembre" to 11, "nov" to 11,
    "decembre" to 12, "dec" to 12
)

private val STRONG_TOTAL_KEYWORDS = listOf(
    "net a payer",
    "total a payer",
    "montant a payer",
    "total ttc",
    "total general"
)
private val TOTAL_KEYWORDS = listOf("total", "ttc", "montant", "carte bancaire", "cb")
private val TAX_KEYWORDS = listOf("tva", "taxe", "ht")
