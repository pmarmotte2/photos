package fr.notedefrais.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode

object ReceiptAnnotator {
    fun annotate(
        source: File,
        destination: File,
        mimeType: String,
        reimbursableAmount: BigDecimal
    ) {
        if (mimeType.equals("application/pdf", ignoreCase = true) ||
            source.extension.equals("pdf", ignoreCase = true)
        ) {
            annotatePdf(source, destination, reimbursableAmount)
        } else {
            annotateImage(source, destination, mimeType, reimbursableAmount)
        }
    }

    private fun annotateImage(
        source: File,
        destination: File,
        mimeType: String,
        amount: BigDecimal
    ) {
        val decoded = requireNotNull(BitmapFactory.decodeFile(source.absolutePath)) {
            "Cette image ne peut pas être annotée."
        }
        val rotation = runCatching { ExifInterface(source).rotationDegrees }.getOrDefault(0)
        val oriented = if (rotation == 0) {
            decoded
        } else {
            Bitmap.createBitmap(
                decoded,
                0,
                0,
                decoded.width,
                decoded.height,
                Matrix().apply { postRotate(rotation.toFloat()) },
                true
            ).also { decoded.recycle() }
        }
        val mutable = oriented.copy(Bitmap.Config.ARGB_8888, true)
        if (mutable !== oriented) oriented.recycle()

        drawReimbursementMark(Canvas(mutable), amount)
        destination.outputStream().use { output ->
            val format = when (mimeType.lowercase()) {
                "image/png" -> Bitmap.CompressFormat.PNG
                "image/webp" -> Bitmap.CompressFormat.WEBP
                else -> Bitmap.CompressFormat.JPEG
            }
            check(mutable.compress(format, 95, output)) {
                "L’image annotée n’a pas pu être enregistrée."
            }
        }
        mutable.recycle()
    }

    private fun annotatePdf(source: File, destination: File, amount: BigDecimal) {
        val document = PdfDocument()
        try {
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    require(renderer.pageCount > 0) { "Le document PDF est vide." }
                    for (index in 0 until renderer.pageCount) {
                        renderer.openPage(index).use { sourcePage ->
                            val outputPage = document.startPage(
                                PdfDocument.PageInfo.Builder(
                                    sourcePage.width,
                                    sourcePage.height,
                                    index + 1
                                ).create()
                            )
                            val scale = 2
                            val bitmap = Bitmap.createBitmap(
                                sourcePage.width * scale,
                                sourcePage.height * scale,
                                Bitmap.Config.ARGB_8888
                            )
                            bitmap.eraseColor(Color.WHITE)
                            sourcePage.render(
                                bitmap,
                                null,
                                null,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                            )
                            outputPage.canvas.drawBitmap(
                                bitmap,
                                null,
                                android.graphics.Rect(
                                    0,
                                    0,
                                    sourcePage.width,
                                    sourcePage.height
                                ),
                                null
                            )
                            bitmap.recycle()
                            if (index == 0) {
                                drawReimbursementMark(outputPage.canvas, amount)
                            }
                            document.finishPage(outputPage)
                        }
                    }
                }
            }
            destination.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
    }

    private fun drawReimbursementMark(canvas: Canvas, amount: BigDecimal) {
        val text = "À REMBOURSER : ${amount.frenchAmount()} €"
        val textSize = (canvas.width * 0.052f).coerceIn(22f, 72f)
        val x = canvas.width * 0.06f
        val y = canvas.height * 0.9f
        val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(205, 28, 38)
            this.textSize = textSize
            typeface = Typeface.create("cursive", Typeface.BOLD)
            style = Paint.Style.FILL
        }
        val outlinePaint = Paint(redPaint).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = (textSize * 0.13f).coerceAtLeast(3f)
        }

        canvas.save()
        canvas.rotate(-4f, x, y)
        canvas.drawText(text, x, y, outlinePaint)
        canvas.drawText(text, x, y, redPaint)
        val textWidth = redPaint.measureText(text)
        canvas.drawLine(
            x,
            y + textSize * 0.16f,
            (x + textWidth).coerceAtMost(canvas.width * 0.96f),
            y + textSize * 0.08f,
            redPaint.apply { strokeWidth = (textSize * 0.07f).coerceAtLeast(2f) }
        )
        canvas.restore()
    }

    private fun BigDecimal.frenchAmount(): String =
        setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',')
}
