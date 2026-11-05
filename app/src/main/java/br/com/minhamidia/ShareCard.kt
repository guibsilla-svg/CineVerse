package br.com.minhamidia

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.content.ContentValues
import android.provider.MediaStore
import android.os.Build
import br.com.minhamidia.data.TmdbClient
import br.com.minhamidia.data.TmdbResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.max

private fun downloadShareImage(url: String): Bitmap? = runCatching {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 6000
    c.readTimeout = 8000
    c.instanceFollowRedirects = true
    try { c.inputStream.use { BitmapFactory.decodeStream(it) } } finally { c.disconnect() }
}.getOrNull()

private fun wrapShareText(text: String, paint: Paint, maxWidth: Float): List<String> {
    val lines = mutableListOf<String>()
    var line = ""
    text.split(Regex("\\s+")).forEach { word ->
        val candidate = if (line.isEmpty()) word else "$line $word"
        if (paint.measureText(candidate) > maxWidth && line.isNotEmpty()) {
            lines.add(line)
            line = word
        } else line = candidate
    }
    if (line.isNotEmpty()) lines.add(line)
    return lines
}

private fun drawCrop(canvas: Canvas, image: Bitmap, target: RectF, paint: Paint) {
    val scale = max(target.width() / image.width, target.height() / image.height)
    val w = image.width * scale
    val h = image.height * scale
    canvas.drawBitmap(image, null, RectF(target.centerX()-w/2, target.centerY()-h/2,
        target.centerX()+w/2, target.centerY()+h/2), paint)
}

private fun drawRoundedImage(canvas: Canvas, image: Bitmap?, rect: RectF, radius: Float, paint: Paint) {
    paint.color = 0xFF281936.toInt()
    canvas.drawRoundRect(rect, radius, radius, paint)
    if (image != null) {
        canvas.save()
        val clip = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        canvas.clipPath(clip)
        drawCrop(canvas, image, rect, paint)
        canvas.restore()
        image.recycle()
    }
}

private fun drawSectionLabel(canvas: Canvas, label: String, x: Float, y: Float, paint: Paint) {
    paint.color = 0xFFB98AFF.toInt()
    paint.textSize = 20f
    paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
    canvas.drawRoundRect(RectF(x, y-19f, x+5f, y+5f), 2f, 2f, paint)
    canvas.drawText(label, x+20f, y, paint)
}

suspend fun createCineVersoShareCard(context: Context, item: TmdbResult, credential: String): Uri =
    withContext(Dispatchers.IO) {
        val info = runCatching { TmdbClient.shareCardDetails(item.id, item.type, credential) }.getOrNull()
        val heroPath = info?.backdropPath ?: item.posterPath
        val imagePaths = info?.stillPaths.orEmpty().take(3)
        val (hero, stills) = coroutineScope {
            val heroTask = async { heroPath?.let { downloadShareImage("https://image.tmdb.org/t/p/w1280$it") } }
            val stillTasks = imagePaths.map { path ->
                async { downloadShareImage("https://image.tmdb.org/t/p/w780$path") }
            }
            heroTask.await() to stillTasks.awaitAll().filterNotNull()
        }

        val width = 1080
        val margin = 64f
        val contentWidth = width - margin * 2
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val synopsisText = item.overview.ifBlank { "Uma história para entrar na sua lista." }
        paint.textSize = 27f
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        val synopsisLines = wrapShareText(synopsisText, paint, contentWidth - 12f)
        val synopsisLineHeight = 40f

        val heroHeight = 610f
        val metaTop = heroHeight + 24f
        val synopsisTop = metaTop + 112f
        val synopsisTextTop = synopsisTop + 62f
        val synopsisBottom = synopsisTextTop + max(1, synopsisLines.size) * synopsisLineHeight
        val scenesTop = synopsisBottom + 76f
        val scenesHeight = 270f
        val footerTop = scenesTop + scenesHeight + 58f
        val height = (footerTop + 170f).toInt()

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        paint.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(0xFF08060E.toInt(), 0xFF1C1029.toInt(), 0xFF090710.toInt()),
            null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        if (hero != null) {
            drawCrop(canvas, hero, RectF(0f, 0f, width.toFloat(), heroHeight), paint)
            hero.recycle()
        } else {
            paint.color = 0xFF29183B.toInt()
            canvas.drawRect(0f, 0f, width.toFloat(), heroHeight, paint)
        }
        paint.shader = LinearGradient(0f, 0f, 0f, heroHeight,
            intArrayOf(0xD9080610.toInt(), 0x18080610, 0xF5080610.toInt()),
            floatArrayOf(0f, 0.38f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), heroHeight, paint)
        paint.shader = null

        paint.color = 0xFF9B5CFF.toInt()
        canvas.drawRoundRect(RectF(0f, 0f, 12f, heroHeight), 0f, 0f, paint)
        canvas.drawRoundRect(RectF(margin, 172f, margin+54f, 178f), 3f, 3f, paint)

        val logo = BitmapFactory.decodeResource(context.resources, R.drawable.cineverse_logo_horizontal)
        if (logo != null) {
            val scale = minOf(220f / logo.width, 54f / logo.height)
            val w = logo.width * scale
            val h = logo.height * scale
            canvas.drawBitmap(logo, null, RectF(margin, 42f, margin+w, 42f+h), paint)
            logo.recycle()
        }

        paint.color = 0xFFE2C8FF.toInt()
        paint.textSize = 18f
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        canvas.drawText(if (item.type == "tv") "SÉRIE EM DESTAQUE" else "FILME EM DESTAQUE", margin, 155f, paint)

        paint.color = Color.WHITE
        paint.textSize = 76f
        paint.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        val titleLines = wrapShareText(item.title.uppercase(Locale("pt", "BR")), paint, 900f).take(2)
        var titleY = if (titleLines.size == 1) 430f else 370f
        titleLines.forEach {
            canvas.drawText(it, margin, titleY, paint)
            titleY += 82f
        }

        val actor = info?.leadActor.orEmpty()
        if (actor.isNotBlank()) {
            paint.color = 0xFFF0EAF5.toInt()
            paint.textSize = 25f
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            canvas.drawText("COM $actor", margin, 548f, paint)
        }

        paint.color = 0xB8140D20.toInt()
        canvas.drawRoundRect(RectF(margin, metaTop, width-margin, metaTop+82f), 22f, 22f, paint)
        val genre = info?.genres.orEmpty().take(2).joinToString("  /  ").ifBlank { "Gênero" }
        val rating = if (item.tmdbVoteAverage > 0.0)
            String.format(Locale("pt", "BR"), "%.1f", item.tmdbVoteAverage) else "—"
        val year = item.year.ifBlank { "—" }
        paint.textSize = 24f
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        paint.color = 0xFFF0EAF5.toInt()
        val meta = "$genre     •     ★ $rating     •     $year"
        val metaText = if (paint.measureText(meta) > contentWidth-36f) "$genre  •  ★ $rating  •  $year" else meta
        canvas.drawText(metaText, margin+26f, metaTop+51f, paint)

        drawSectionLabel(canvas, "A HISTÓRIA", margin, synopsisTop+25f, paint)
        paint.color = 0xFF9B5CFF.toInt()
        canvas.drawRoundRect(RectF(margin, synopsisTextTop-22f, margin+4f,
            synopsisTextTop + max(1, synopsisLines.size)*synopsisLineHeight-10f), 2f, 2f, paint)
        paint.color = 0xFFE8E0EF.toInt()
        paint.textSize = 27f
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        var lineY = synopsisTextTop
        synopsisLines.forEach {
            canvas.drawText(it, margin+24f, lineY, paint)
            lineY += synopsisLineHeight
        }

        drawSectionLabel(canvas, "DENTRO DA HISTÓRIA", margin, scenesTop+25f, paint)
        val imageTop = scenesTop + 48f
        val largeRect = RectF(margin, imageTop, margin+560f, imageTop+218f)
        val rightTop = RectF(margin+578f, imageTop, width-margin, imageTop+101f)
        val rightBottom = RectF(margin+578f, imageTop+117f, width-margin, imageTop+218f)
        drawRoundedImage(canvas, stills.getOrNull(0), largeRect, 18f, paint)
        drawRoundedImage(canvas, stills.getOrNull(1), rightTop, 16f, paint)
        drawRoundedImage(canvas, stills.getOrNull(2), rightBottom, 16f, paint)

        paint.color = 0xFF8A42F5.toInt()
        canvas.drawRoundRect(RectF(margin, footerTop, width-margin, footerTop+82f), 24f, 24f, paint)
        paint.color = Color.WHITE
        paint.textSize = 29f
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        val cta = "MINHA INDICAÇÃO"
        canvas.drawText(cta, (width-paint.measureText(cta))/2f, footerTop+53f, paint)

        paint.color = 0xFFBFAED0.toInt()
        paint.textSize = 17f
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val footer = "CINEVERSO  •  SUA PRÓXIMA HISTÓRIA COMEÇA AQUI"
        canvas.drawText(footer, (width-paint.measureText(footer))/2f, footerTop+125f, paint)

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "cineverso_${item.type}_${item.id}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CineVerso")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val imageUri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ) ?: run {
            bitmap.recycle()
            throw IllegalStateException("Não foi possível criar a imagem para compartilhamento")
        }

        try {
            val stream = context.contentResolver.openOutputStream(imageUri)
                ?: throw IllegalStateException("Não foi possível abrir a imagem gerada")
            stream.use {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                    throw IllegalStateException("Não foi possível salvar a imagem gerada")
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val ready = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                context.contentResolver.update(imageUri, ready, null, null)
            }
            bitmap.recycle()
            imageUri
        } catch (error: Exception) {
            context.contentResolver.delete(imageUri, null, null)
            bitmap.recycle()
            throw error
        }
    }
