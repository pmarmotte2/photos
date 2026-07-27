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

object ReceiptAnnotator {
    fun annotate(
        source: File,
        destination: File,
        mimeType: String,
        calculation: ReimbursementCalculation
    ) {
        if (mimeType.equals("application/pdf", ignoreCase = true) ||
            source.extension.equals("pdf", ignoreCase = true)
        ) {
            annotatePdf(source, destination, calculation)
        } else {
            annotateImage(source, destination, mimeType, calculation)
        }
    }

    private fun annotateImage(
        source: File,
        destination: File,
        mimeType: String,
        calculation: ReimbursementCalculation
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

        drawReimbursementMark(Canvas(mutable), calculation)
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

    private fun annotatePdf(
        source: File,
        destination: File,
        calculation: ReimbursementCalculation
    ) {
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
                                drawReimbursementMark(outputPage.canvas, calculation)
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

    private fun drawReimbursementMark(
        canvas: Canvas,
        calculation: ReimbursementCalculation
    ) {
        val heading = "À REMBOURSER"
        val detailLines = calculation.annotationDetailLines()
        val amountText = "${calculation.reimbursableAmount.annotationAmount()} €"
        val x = canvas.width * 0.06f
        val maxTextWidth = canvas.width * 0.88f
        val amountY = canvas.height * 0.94f
        val detailSpacing = canvas.height * 0.052f
        val detailStartY = amountY - canvas.height * 0.16f -
            detailSpacing * detailLines.lastIndex.coerceAtLeast(0)
        val headingY = detailStartY - canvas.height * 0.075f
        val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(205, 28, 38)
            textSize = canvas.width * 0.065f
            typeface = Typeface.create("cursive", Typeface.BOLD)
            style = Paint.Style.FILL
        }
        headingPaint.fitToWidth(heading, maxTextWidth)
        val detailPaint = Paint(headingPaint).apply {
            textSize = canvas.width * 0.036f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val amountPaint = Paint(headingPaint).apply {
            textSize = canvas.width * 0.13f
        }
        amountPaint.fitToWidth(amountText, maxTextWidth)

        canvas.save()
        canvas.rotate(-3f, x, amountY)
        canvas.drawOutlinedText(heading, x, headingY, headingPaint)
        detailLines.forEachIndexed { index, line ->
            detailPaint.textSize = canvas.width * 0.036f
            detailPaint.fitToWidth(line, maxTextWidth)
            canvas.drawOutlinedText(
                line,
                x,
                detailStartY + detailSpacing * index,
                detailPaint
            )
        }
        canvas.drawOutlinedText(amountText, x, amountY, amountPaint)
        val amountWidth = amountPaint.measureText(amountText)
        canvas.drawLine(
            x,
            amountY + amountPaint.textSize * 0.13f,
            (x + amountWidth).coerceAtMost(canvas.width * 0.96f),
            amountY + amountPaint.textSize * 0.05f,
            Paint(amountPaint).apply {
                strokeWidth = (amountPaint.textSize * 0.075f).coerceAtLeast(3f)
                strokeCap = Paint.Cap.ROUND
            }
        )
        canvas.restore()
    }

    private fun Paint.fitToWidth(text: String, maxWidth: Float) {
        val currentWidth = measureText(text)
        if (currentWidth > maxWidth) {
            textSize *= maxWidth / currentWidth
        }
    }

    private fun Canvas.drawOutlinedText(text: String, x: Float, y: Float, paint: Paint) {
        val outline = Paint(paint).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = (paint.textSize * 0.15f).coerceAtLeast(4f)
            strokeJoin = Paint.Join.ROUND
        }
        drawText(text, x, y, outline)
        drawText(text, x, y, paint)
    }

}
