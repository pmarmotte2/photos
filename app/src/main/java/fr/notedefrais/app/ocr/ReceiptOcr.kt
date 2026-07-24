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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

data class OcrAmountCandidate(
    val amount: BigDecimal,
    val sourceLine: String,
    val score: Int
)

object ReceiptOcr {
    suspend fun detectAmounts(
        context: Context,
        uri: Uri,
        mimeType: String
    ): List<OcrAmountCandidate> {
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
            extractAmountCandidates(text)
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

private val STRONG_TOTAL_KEYWORDS = listOf(
    "net a payer",
    "total a payer",
    "montant a payer",
    "total ttc",
    "total general"
)
private val TOTAL_KEYWORDS = listOf("total", "ttc", "montant", "carte bancaire", "cb")
private val TAX_KEYWORDS = listOf("tva", "taxe", "ht")
