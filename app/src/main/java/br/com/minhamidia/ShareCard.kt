package br.com.minhamidia

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
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

suspend fun createCineVersoShareCard(context: Context, item: TmdbResult): Uri =
    withContext(Dispatchers.IO) {
        val width = 1080
        val height = 1350
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

        val poster = item.posterPath?.let { path ->
            runCatching {
                val connection = URL("https://image.tmdb.org/t/p/w780$path")
                    .openConnection() as HttpURLConnection
                connection.connectTimeout = 12000
                connection.readTimeout = 12000
                try {
                    connection.inputStream.use { BitmapFactory.decodeStream(it) }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
        }

        val posterRect = RectF(72f, 64f, 1008f, 880f)
        if (poster != null) {
            val scale = maxOf(posterRect.width() / poster.width, posterRect.height() / poster.height)
            val cropW = posterRect.width() / scale
            val cropH = posterRect.height() / scale
            val src = Rect(
                ((poster.width - cropW) / 2f).toInt(),
                ((poster.height - cropH) / 2f).toInt(),
                ((poster.width + cropW) / 2f).toInt(),
                ((poster.height + cropH) / 2f).toInt()
            )
            canvas.drawBitmap(poster, src, posterRect, paint)
            poster.recycle()
            paint.shader = LinearGradient(
                0f, 570f, 0f, 900f,
                intArrayOf(0x00000000, 0xFF160D22.toInt()),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(posterRect, paint)
            paint.shader = null
        } else {
            paint.color = 0xFF302044.toInt()
            canvas.drawRoundRect(posterRect, 30f, 30f, paint)
            paint.color = android.graphics.Color.WHITE
            paint.textSize = 72f
            paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            canvas.drawText("CineVerso", 120f, 480f, paint)
        }

        paint.color = 0xFFC29AFF.toInt()
        paint.textSize = 34f
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        canvas.drawText("MINHA RECOMENDAÇÃO", 76f, 968f, paint)

        paint.color = android.graphics.Color.WHITE
        paint.textSize = 66f
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        val maxTextWidth = 920f
        val words = item.title.split(" ")
        var line = ""
        var y = 1060f
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) > maxTextWidth && line.isNotEmpty()) {
                canvas.drawText(line, 76f, y, paint)
                y += 76f
                line = word
            } else {
                line = candidate
            }
        }
        if (line.isNotEmpty()) canvas.drawText(line, 76f, y, paint)

        paint.color = 0xFFD5C9E4.toInt()
        paint.textSize = 36f
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        canvas.drawText(
            "${if (item.type == "tv") "Série" else "Filme"}  •  ${item.year.ifBlank { "Ano indisponível" }}",
            78f, 1190f, paint
        )

        paint.color = 0xFF9B5CFF.toInt()
        canvas.drawRoundRect(RectF(72f, 1230f, 1008f, 1310f), 22f, 22f, paint)
        paint.color = android.graphics.Color.WHITE
        paint.textSize = 34f
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        val footer = "DESCUBRA NO CINEVERSO"
        canvas.drawText(footer, (width - paint.measureText(footer)) / 2f, 1283f, paint)

        val directory = File(context.cacheDir, "shared_recommendations").apply { mkdirs() }
        val output = File(directory, "cineverso_${item.type}_${item.id}.png")
        FileOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", output)
    }
