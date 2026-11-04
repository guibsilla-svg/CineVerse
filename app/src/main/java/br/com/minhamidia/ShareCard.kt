package br.com.minhamidia

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.FileProvider
import br.com.minhamidia.data.TmdbResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

private fun downloadShareImage(url: String): Bitmap? = runCatching {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 12000
        readTimeout = 12000
        instanceFollowRedirects = true
    }
    try {
        connection.inputStream.use { BitmapFactory.decodeStream(it) }
    } finally {
        connection.disconnect()
    }
}.getOrNull()

private fun drawCentered(canvas: Canvas, paint: Paint, text: String, y: Float, centerX: Float = 540f) {
    canvas.drawText(text, centerX - paint.measureText(text) / 2f, y, paint)
}

private fun wrapShareText(text: String, paint: Paint, maxWidth: Float, maxLines: Int): List<String> {
    val result = mutableListOf<String>()
    var line = ""
    for (word in text.split(Regex("\\s+"))) {
        val candidate = if (line.isEmpty()) word else "$line $word"
        if (paint.measureText(candidate) > maxWidth && line.isNotEmpty()) {
            result.add(line)
            line = word
            if (result.size == maxLines) break
        } else line = candidate
    }
    if (result.size < maxLines && line.isNotEmpty()) result.add(line)
    if (result.size == maxLines && text.isNotBlank()) {
        var last = result.last()
        while (paint.measureText("$last…") > maxWidth && last.isNotEmpty()) last = last.dropLast(1)
        result[result.lastIndex] = "$last…"
    }
    return result
}

suspend fun createCineVersoShareCard(context: Context, item: TmdbResult, credential: String): Uri =
    withContext(Dispatchers.IO) {
        val width = 1080
        val height = 1500
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        paint.shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(0xFF08060E.toInt(), 0xFF21102F.toInt(), 0xFF0A0711.toInt()),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        paint.color = 0x229B5CFF
        canvas.drawCircle(540f, 520f, 520f, paint)

        val logo = BitmapFactory.decodeResource(context.resources, R.drawable.cineverse_logo_horizontal)
        if (logo != null) {
            val maxW = 330f
            val maxH = 76f
            val scale = min(maxW / logo.width, maxH / logo.height)
            val w = logo.width * scale
            val h = logo.height * scale
            canvas.drawBitmap(logo, null, RectF(540f - w / 2f, 34f + (maxH - h) / 2f, 540f + w / 2f, 34f + (maxH + h) / 2f), paint)
            logo.recycle()
        }

        paint.color = 0xFFB98AFF.toInt()
        paint.textSize = 22f
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        drawCentered(canvas, paint, "U M A   R E C O M E N D A Ç Ã O   P A R A   V O C Ê", 142f)

        val posterRect = RectF(205f, 174f, 875f, 1080f)
        paint.color = 0xFF130D1D.toInt()
        paint.setShadowLayer(28f, 0f, 14f, 0x99000000.toInt())
        canvas.drawRoundRect(posterRect, 26f, 26f, paint)
        paint.clearShadowLayer()
        val poster = item.posterPath?.let { downloadShareImage("https://image.tmdb.org/t/p/w780$it") }
        if (poster != null) {
            canvas.save()
            val clip = Path().apply { addRoundRect(posterRect, 26f, 26f, Path.Direction.CW) }
            canvas.clipPath(clip)
            val scale = max(posterRect.width() / poster.width, posterRect.height() / poster.height)
            val dstW = poster.width * scale
            val dstH = poster.height * scale
            canvas.drawBitmap(poster, null, RectF(
                posterRect.centerX() - dstW / 2f, posterRect.centerY() - dstH / 2f,
                posterRect.centerX() + dstW / 2f, posterRect.centerY() + dstH / 2f
            ), paint)
            canvas.restore()
            poster.recycle()
        } else {
            paint.color = 0xFF2B1B3B.toInt()
            canvas.drawRoundRect(posterRect, 26f, 26f, paint)
            paint.color = 0xFFB98AFF.toInt()
            paint.textSize = 34f
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            drawCentered(canvas, paint, "PÔSTER INDISPONÍVEL", 635f)
        }

        paint.shader = LinearGradient(0f, 850f, 0f, 1080f,
            intArrayOf(0x00000000, 0xCC08060E.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRect(posterRect.left, 850f, posterRect.right, posterRect.bottom, paint)
        paint.shader = null

        paint.color = android.graphics.Color.WHITE
        paint.textSize = 66f
        paint.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        val titleLines = wrapShareText(item.title, paint, 980f, 2)
        var titleY = 1160f - (titleLines.size - 1) * 68f
        titleLines.forEach { line ->
            drawCentered(canvas, paint, line, titleY)
            titleY += 72f
        }

        val metadata = buildList {
            if (item.year.isNotBlank()) add(item.year)
            add(if (item.type.equals("tv", true)) "SÉRIE" else "FILME")
            if (item.tmdbVoteAverage > 0.0) add("★ %.1f/10 TMDB".format(Locale.US, item.tmdbVoteAverage))
        }.joinToString("   •   ")
        paint.color = 0xFFD7C9E8.toInt()
        paint.textSize = 27f
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        drawCentered(canvas, paint, metadata, 1210f)

        paint.color = 0xFFE8E1EF.toInt()
        paint.textSize = 25f
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        val synopsisLines = wrapShareText(item.overview.ifBlank { "Uma história para entrar na sua lista." }, paint, 900f, 3)
        var synopsisY = 1260f
        synopsisLines.forEach { line ->
            drawCentered(canvas, paint, line, synopsisY)
            synopsisY += 34f
        }

        val footer = RectF(300f, 1370f, 780f, 1448f)
        paint.color = 0xFF9B5CFF.toInt()
        paint.setShadowLayer(18f, 0f, 5f, 0x669B5CFF)
        canvas.drawRoundRect(footer, 22f, 22f, paint)
        paint.clearShadowLayer()
        paint.color = android.graphics.Color.WHITE
        paint.textSize = 30f
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        drawCentered(canvas, paint, "MINHA INDICAÇÃO", 1420f)

        val directory = File(context.cacheDir, "shared_recommendations").apply { mkdirs() }
        val output = File(directory, "cineverso_${item.type}_${item.id}.png")
        FileOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", output)
    }
