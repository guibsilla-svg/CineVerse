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
import br.com.minhamidia.data.TmdbClient
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
        val height = 2200
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(0xFF08070D.toInt(), 0xFF241238.toInt(), 0xFF08070D.toInt()),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        val logo = BitmapFactory.decodeResource(context.resources, R.drawable.cineverso_logo_horizontal)
        if (logo != null) {
            val logoRect = RectF(300f, 35f, 780f, 145f)
            val scale = minOf(logoRect.width() / logo.width, logoRect.height() / logo.height)
            val dstW = logo.width * scale
            val dstH = logo.height * scale
            val dst = RectF((width - dstW) / 2f, (145f - dstH) / 2f, (width + dstW) / 2f, (145f + dstH) / 2f)
            canvas.drawBitmap(logo, null, dst, paint)
            logo.recycle()
        }

        val poster = item.posterPath?.let { downloadShareImage("https://image.tmdb.org/t/p/w780$it") }
        val posterRect = RectF(300f, 175f, 780f, 870f)
        paint.color = 0xFF160D22.toInt()
        canvas.drawRoundRect(posterRect, 24f, 24f, paint)
        if (poster != null) {
            val scale = minOf(posterRect.width() / poster.width, posterRect.height() / poster.height)
            val dstW = poster.width * scale
            val dstH = poster.height * scale
            val dst = RectF(posterRect.centerX() - dstW / 2f, posterRect.centerY() - dstH / 2f, posterRect.centerX() + dstW / 2f, posterRect.centerY() + dstH / 2f)
            canvas.drawBitmap(poster, null, dst, paint)
            poster.recycle()
        } else {
            paint.color = android.graphics.Color.WHITE
            paint.textSize = 72f
            paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            canvas.drawText("CineVerso", (width - paint.measureText("CineVerso")) / 2f, 535f, paint)
        }

        // Three cinematic stills are loaded from the TMDB image gallery when available.
        val backdropPaths = runCatching { TmdbClient.imageBackdrops(item.id, item.type, credential) }.getOrDefault(emptyList())
        val backdrops = backdropPaths.mapNotNull { path ->
            downloadShareImage("https://image.tmdb.org/t/p/w780$path")
        }.take(3)
        val imageTop = 925f
        val imageBottom = 1195f
        val gap = 18f
        val side = 72f
        val count = backdrops.size
        if (count > 0) {
            val slotWidth = (width - side * 2f - gap * (count - 1)) / count
            backdrops.forEachIndexed { index, image ->
                val left = side + index * (slotWidth + gap)
                val rect = RectF(left, imageTop, left + slotWidth, imageBottom)
                canvas.save()
                val clip = android.graphics.Path().apply { addRoundRect(rect, 18f, 18f, android.graphics.Path.Direction.CW) }
                canvas.clipPath(clip)
                val scale = maxOf(rect.width() / image.width, rect.height() / image.height)
                val dstW = image.width * scale
                val dstH = image.height * scale
                val dst = RectF(rect.centerX() - dstW / 2f, rect.centerY() - dstH / 2f, rect.centerX() + dstW / 2f, rect.centerY() + dstH / 2f)
                canvas.drawBitmap(image, null, dst, paint)
                canvas.restore()
                image.recycle()
            }
        }

        paint.color = android.graphics.Color.WHITE
        paint.textSize = 62f
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        val titleLines = mutableListOf<String>()
        var titleLine = ""
        for (word in item.title.split(" ")) {
            val candidate = if (titleLine.isEmpty()) word else "$titleLine $word"
            if (paint.measureText(candidate) > 920f && titleLine.isNotEmpty()) {
                titleLines.add(titleLine)
                titleLine = word
            } else titleLine = candidate
        }
        if (titleLine.isNotEmpty()) titleLines.add(titleLine)
        var titleY = 1290f
        titleLines.take(2).forEach {
            canvas.drawText(it, 80f, titleY, paint)
            titleY += 70f
        }

        paint.color = 0xFFD5C9E4.toInt()
        paint.textSize = 34f
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        canvas.drawText(
            "${if (item.type == "tv") "Série" else "Filme"}  •  ${item.year.ifBlank { "Ano indisponível" }}",
            82f, 1435f, paint
        )

        paint.color = 0xFFC29AFF.toInt()
        paint.textSize = 36f
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        canvas.drawText("SINOPSE", 80f, 1515f, paint)

        paint.color = 0xFFE9E2F2.toInt()
        paint.textSize = 32f
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
        val maxLines = 8
        val visibleLines = synopsisLines.take(maxLines).toMutableList()
        if (synopsisLines.size > maxLines && visibleLines.isNotEmpty()) {
            var last = visibleLines.last()
            while (paint.measureText("$last…") > 920f && last.isNotEmpty()) last = last.dropLast(1)
            visibleLines[visibleLines.lastIndex] = "$last…"
        }
        var synopsisY = 1575f
        visibleLines.forEach {
            canvas.drawText(it, 80f, synopsisY, paint)
            synopsisY += 43f
        }

        paint.color = 0xFF9B5CFF.toInt()
        canvas.drawRoundRect(RectF(72f, 1990f, 1008f, 2090f), 24f, 24f, paint)
        paint.color = android.graphics.Color.WHITE
        paint.textSize = 38f
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        val footer = "MINHA INDICAÇÃO"
        canvas.drawText(footer, (width - paint.measureText(footer)) / 2f, 2053f, paint)

        val directory = File(context.cacheDir, "shared_recommendations").apply { mkdirs() }
        val output = File(directory, "cineverso_${item.type}_${item.id}.png")
        FileOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", output)
    }
