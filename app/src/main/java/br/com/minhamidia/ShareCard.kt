package br.com.minhamidia

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
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

suspend fun createCineVersoShareCard(context: Context, item: TmdbResult, credential: String): Uri =
    withContext(Dispatchers.IO) {
        val width = 1080
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Clean, square recommendation card: poster, title, year, synopsis, logo and action.
        paint.shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(0xFF08070D.toInt(), 0xFF241238.toInt(), 0xFF08070D.toInt()),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        val poster = item.posterPath?.let { downloadShareImage("https://image.tmdb.org/t/p/w780$it") }
        val posterRect = RectF(370f, 28f, 710f, 488f)
        paint.color = 0xFF160D22.toInt()
        canvas.drawRoundRect(posterRect, 22f, 22f, paint)
        if (poster != null) {
            canvas.save()
            val clip = android.graphics.Path().apply {
                addRoundRect(posterRect, 22f, 22f, android.graphics.Path.Direction.CW)
            }
            canvas.clipPath(clip)
            val scale = maxOf(posterRect.width() / poster.width, posterRect.height() / poster.height)
            val dstW = poster.width * scale
            val dstH = poster.height * scale
            val dst = RectF(
                posterRect.centerX() - dstW / 2f, posterRect.centerY() - dstH / 2f,
                posterRect.centerX() + dstW / 2f, posterRect.centerY() + dstH / 2f
            )
            canvas.drawBitmap(poster, null, dst, paint)
            canvas.restore()
            poster.recycle()
        }

        paint.color = android.graphics.Color.WHITE
        paint.textSize = 52f
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        val titleLines = mutableListOf<String>()
        var titleLine = ""
        for (word in item.title.split(" ")) {
            val candidate = if (titleLine.isEmpty()) word else "$titleLine $word"
            if (paint.measureText(candidate) > 940f && titleLine.isNotEmpty()) {
                titleLines.add(titleLine)
                titleLine = word
            } else titleLine = candidate
        }
        if (titleLine.isNotEmpty()) titleLines.add(titleLine)
        var titleY = 555f
        titleLines.take(2).forEach {
            canvas.drawText(it, (width - paint.measureText(it)) / 2f, titleY, paint)
            titleY += 58f
        }

        paint.color = 0xFFD5C9E4.toInt()
        paint.textSize = 30f
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val year = item.year.ifBlank { "Ano indisponível" }
        canvas.drawText(year, (width - paint.measureText(year)) / 2f, 635f, paint)

        paint.color = 0xFFE9E2F2.toInt()
        paint.textSize = 27f
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        val synopsis = item.overview.ifBlank { "Sinopse indisponível." }
        val synopsisLines = mutableListOf<String>()
        var synopsisLine = ""
        for (word in synopsis.split(Regex("\\s+"))) {
            val candidate = if (synopsisLine.isEmpty()) word else "$synopsisLine $word"
            if (paint.measureText(candidate) > 920f && synopsisLine.isNotEmpty()) {
                synopsisLines.add(synopsisLine)
                synopsisLine = word
            } else synopsisLine = candidate
        }
        if (synopsisLine.isNotEmpty()) synopsisLines.add(synopsisLine)
        val ratingText = if (item.tmdbVoteAverage > 0.0) "★ %.1f/10 • TMDB".format(java.util.Locale.US, item.tmdbVoteAverage) else "★ Sem avaliação TMDB"
        val ratingY = 680f
        val synopsisStartY = 730f
        var synopsisDrawY = synopsisStartY
        synopsisLines.forEach {
            canvas.drawText(it, (width - paint.measureText(it)) / 2f, synopsisDrawY, paint)
            synopsisDrawY += 36f
        }

        val contentBottom = synopsisDrawY + 30f
        val baseHeight = maxOf(1080f, contentBottom + 300f)
        val scaleFactor = baseHeight / 1080f
        val height = baseHeight.toInt()
        val bitmap = Bitmap.createBitmap(height, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scaleFactor, scaleFactor)
        paint.shader = LinearGradient(0f, 0f, width.toFloat(), 1080f, intArrayOf(0xFF08070D.toInt(), 0xFF241238.toInt(), 0xFF08070D.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), 1080f, paint)
        paint.shader = null
        paint.color = 0xFFFFD166.toInt()
        paint.textSize = 30f
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        canvas.drawText(ratingText, (width - paint.measureText(ratingText)) / 2f, ratingY, paint)

        val logo = BitmapFactory.decodeResource(context.resources, R.drawable.cineverse_logo_horizontal)
        if (logo != null) {
            val logoRect = RectF(365f, contentBottom + 30f, 715f, contentBottom + 105f)
            val scale = minOf(logoRect.width() / logo.width, logoRect.height() / logo.height)
            val dstW = logo.width * scale
            val dstH = logo.height * scale
            val dst = RectF(
                (width - dstW) / 2f, logoRect.centerY() - dstH / 2f,
                (width + dstW) / 2f, logoRect.centerY() + dstH / 2f
            )
            canvas.drawBitmap(logo, null, dst, paint)
            logo.recycle()
        }

        paint.color = 0xFF9B5CFF.toInt()
        canvas.drawRoundRect(RectF(72f, contentBottom + 125f, 1008f, contentBottom + 210f), 22f, 22f, paint)
        paint.color = android.graphics.Color.WHITE
        paint.textSize = 36f
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        val footer = "MINHA INDICAÇÃO"
        canvas.drawText(footer, (width - paint.measureText(footer)) / 2f, contentBottom + 180f, paint)

        val directory = File(context.cacheDir, "shared_recommendations").apply { mkdirs() }
        val output = File(directory, "cineverso_${item.type}_${item.id}.png")
        FileOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", output)
    }
