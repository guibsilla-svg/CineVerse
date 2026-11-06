package br.com.minhamidia

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import br.com.minhamidia.data.TmdbClient
import br.com.minhamidia.data.TmdbResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.max

private fun downloadShareImage(url: String): Bitmap? = runCatching {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 7000
    connection.readTimeout = 9000
    connection.instanceFollowRedirects = true
    try { connection.inputStream.use { BitmapFactory.decodeStream(it) } }
    finally { connection.disconnect() }
}.getOrNull()

private fun wrapShareText(text: String, paint: Paint, maxWidth: Float): List<String> {
    val result = mutableListOf<String>()
    var line = ""
    text.split(Regex("\\s+")).forEach { word ->
        val candidate = if (line.isEmpty()) word else "$line $word"
        if (paint.measureText(candidate) > maxWidth && line.isNotEmpty()) {
            result.add(line)
            line = word
        } else line = candidate
    }
    if (line.isNotBlank()) result.add(line)
    return result
}

private fun drawCrop(canvas: Canvas, image: Bitmap, target: RectF, paint: Paint) {
    val scale = max(target.width() / image.width, target.height() / image.height)
    val w = image.width * scale
    val h = image.height * scale
    canvas.drawBitmap(image, null, RectF(target.centerX() - w / 2f, target.centerY() - h / 2f,
        target.centerX() + w / 2f, target.centerY() + h / 2f), paint)
}

private fun drawRoundedImage(canvas: Canvas, image: Bitmap?, rect: RectF, radius: Float, paint: Paint) {
    paint.color = 0xFF21152C.toInt()
    canvas.drawRoundRect(rect, radius, radius, paint)
    if (image != null) {
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) })
        drawCrop(canvas, image, rect, paint)
        canvas.restore()
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = 0xFFB94DFF.toInt()
        }
        canvas.drawRoundRect(rect, radius, radius, border)
        image.recycle()
    } else {
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = 0xFF7135A5.toInt()
        }
        canvas.drawRoundRect(rect, radius, radius, border)
    }
}

private fun drawCentered(canvas: Canvas, text: String, y: Float, paint: Paint, centerX: Float = 540f) {
    // Paint.Align.CENTER expects the supplied X to be the center, not the left edge.
    val x = if (paint.textAlign == Paint.Align.CENTER) centerX
        else centerX - paint.measureText(text) / 2f
    canvas.drawText(text, x, y, paint)
}

private fun drawStar(canvas: Canvas, cx: Float, cy: Float, outer: Float, inner: Float, paint: Paint) {
    val path = Path()
    for (i in 0 until 10) {
        val angle = Math.PI * i / 5.0 - Math.PI / 2.0
        val radius = if (i % 2 == 0) outer else inner
        val x = cx + (kotlin.math.cos(angle) * radius).toFloat()
        val y = cy + (kotlin.math.sin(angle) * radius).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    canvas.drawPath(path, paint)
}

private fun drawFilmStrip(canvas: Canvas, left: Float, top: Float, width: Float, height: Float, paint: Paint) {
    // Continuous curved celluloid ribbon: outer rails, dark image channel and
    // paired sprocket perforations rotated to follow the ribbon's direction.
    val path = Path().apply {
        moveTo(left, top + height * .52f)
        cubicTo(left + width * .18f, top - height * .12f,
            left + width * .30f, top + height * 1.12f,
            left + width * .50f, top + height * .52f)
        cubicTo(left + width * .70f, top - height * .08f,
            left + width * .82f, top + height * 1.12f,
            left + width, top + height * .48f)
    }
    paint.shader = null
    paint.style = Paint.Style.STROKE
    paint.strokeCap = Paint.Cap.ROUND
    paint.strokeJoin = Paint.Join.ROUND
    paint.color = 0xFFB45AE5.toInt()
    paint.strokeWidth = 66f
    canvas.drawPath(path, paint)
    paint.color = 0xFF21102D.toInt()
    paint.strokeWidth = 58f
    canvas.drawPath(path, paint)
    paint.color = 0xFF09060D.toInt()
    paint.strokeWidth = 34f
    canvas.drawPath(path, paint)

    val measure = PathMeasure(path, false)
    val length = measure.length
    val position = FloatArray(2)
    val tangent = FloatArray(2)
    val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD9A0F4.toInt() }
    var distance = 22f
    while (distance < length - 12f) {
        if (measure.getPosTan(distance, position, tangent)) {
            val angle = Math.toDegrees(kotlin.math.atan2(tangent[1], tangent[0]).toDouble()).toFloat()
            val normalX = -tangent[1]
            val normalY = tangent[0]
            for (side in listOf(-1f, 1f)) {
                val cx = position[0] + normalX * 22f * side
                val cy = position[1] + normalY * 22f * side
                canvas.save()
                canvas.rotate(angle, cx, cy)
                canvas.drawRoundRect(RectF(cx - 8f, cy - 4.5f, cx + 8f, cy + 4.5f), 2.5f, 2.5f, holePaint)
                canvas.restore()
            }
        }
        distance += 39f
    }
    paint.style = Paint.Style.FILL
    paint.strokeCap = Paint.Cap.BUTT
}

suspend fun createCineVersoShareCard(context: Context, item: TmdbResult, credential: String): Uri =
    withContext(Dispatchers.IO) {
        val info = runCatching { TmdbClient.shareCardDetails(item.id, item.type, credential) }.getOrNull()
        val heroUrl = info?.fanartBackgroundUrl ?: info?.fanartBannerUrl
        val heroPath = info?.backdropPath ?: item.posterPath
        // The three thumbnails deliberately use TMDB image backdrops only (scene frames),
        // never Fanart posters, banners, logos or promotional key art.
        // Only dedicated scene-thumbnail sources are eligible here.
        // Fanart.tv movie thumbs are actual film-frame thumbnails; never use backdrops/key art.
        val sceneUrls = info?.fanartStillUrls.orEmpty().take(3)
        val (hero, stills) = coroutineScope {
            val heroTask = async {
                heroUrl?.let { downloadShareImage(it) }
                    ?: heroPath?.let { downloadShareImage("https://image.tmdb.org/t/p/w1280$it") }
            }
            val stillTasks = sceneUrls.map { url -> async { downloadShareImage(url) } }
            heroTask.await() to stillTasks.awaitAll().filterNotNull()
        }

        val width = 1080
        val margin = 46f
        val contentWidth = width - margin * 2
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val synopsis = item.overview.ifBlank { "Uma história que merece entrar na sua lista." }
        val synopsisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF0EAF4.toInt()
            textSize = 29f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        // Keep the complete synopsis: the canvas grows vertically when more lines are needed.
        val synopsisLines = wrapShareText(synopsis, synopsisPaint, contentWidth - 42f)
        val synopsisTop = 850f
        val synopsisLineHeight = 39f
        val synopsisBottom = synopsisTop + synopsisLines.size * synopsisLineHeight
        val stillTop = maxOf(1000f, synopsisBottom + 45f)
        val stillHeight = 166f
        val buttonTop = stillTop + stillHeight + 48f
        val buttonBottom = buttonTop + 106f
        val stripTop = buttonBottom + 10f
        val height = (buttonBottom + 190f).toInt()

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        paint.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(0xFF050309.toInt(), 0xFF170B25.toInt(), 0xFF050309.toInt()),
            null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        val heroBottom = 720f
        if (hero != null) {
            drawCrop(canvas, hero, RectF(0f, 0f, width.toFloat(), heroBottom), paint)
            hero.recycle()
        }
        paint.shader = LinearGradient(0f, 0f, 0f, heroBottom,
            intArrayOf(0xC9000000.toInt(), 0x16000000, 0xF5050309.toInt()),
            floatArrayOf(0f, .42f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), heroBottom, paint)
        paint.shader = null
        paint.shader = LinearGradient(0f, 0f, width.toFloat(), 0f,
            intArrayOf(0xA9000000.toInt(), 0x00000000, 0x55200042),
            null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), heroBottom, paint)
        paint.shader = null

        // Brand only: the old slogan line has been removed.
        val brand = BitmapFactory.decodeResource(context.resources, R.drawable.cineverse_logo_horizontal)
        if (brand != null) {
            val scale = minOf(390f / brand.width, 92f / brand.height)
            val w = brand.width * scale
            val h = brand.height * scale
            canvas.drawBitmap(brand, null, RectF(54f, 28f, 54f + w, 28f + h), paint)
            brand.recycle()
        } else {
            paint.color = Color.WHITE
            paint.textSize = 56f
            paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            canvas.drawText("CineVerso", 58f, 92f, paint)
        }
        // No decorative purple underline beneath the CineVerso logo.

        // Brazilian Portuguese title is rendered as text instead of relying on
        // language-dependent Fanart logos, which are often supplied only in English.
        val title = (info?.localizedTitle?.takeIf { it.isNotBlank() } ?: item.title)
            .uppercase(Locale("pt", "BR"))
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFF302B.toInt()
            textSize = if (title.length > 20) 88f else 104f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC)
            textAlign = Paint.Align.CENTER
            setShadowLayer(6f, 2f, 4f, 0xD0000000.toInt())
        }
        // Fit the complete Brazilian Portuguese title into the hero area.
        var titleLines = wrapShareText(title, titlePaint, 990f)
        while (titleLines.size > 2 && titlePaint.textSize > 48f) {
            titlePaint.textSize -= 4f
            titleLines = wrapShareText(title, titlePaint, 990f)
        }
        val titleLineHeight = titlePaint.textSize * 1.02f
        var titleY = if (titleLines.size == 1) 610f else 535f
        titleLines.forEach { line ->
            drawCentered(canvas, line, titleY, titlePaint)
            titleY += titleLineHeight
        }
        titlePaint.clearShadowLayer()

        // Metadata row with individually aligned genre, TMDB score and year.
        val genre = info?.genres.orEmpty().take(2).joinToString("  •  ").ifBlank { "Cinema" }
        val rating = if (item.tmdbVoteAverage > 0.0)
            String.format(Locale("pt", "BR"), "%.1f/10", item.tmdbVoteAverage) else "—/10"
        val year = item.year.ifBlank { "—" }
        val metaY = 775f
        val separatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFB85BEB.toInt()
            strokeWidth = 2f
        }
        val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val metaWidth = metaPaint.measureText(genre) + metaPaint.measureText(rating) +
            metaPaint.measureText(year) + 150f
        metaPaint.textSize = if (metaWidth > 950f) 23f else 28f
        val totalWidth = metaPaint.measureText(genre) + metaPaint.measureText(rating) +
            metaPaint.measureText(year) + 150f
        var x = (width - totalWidth) / 2f
        paint.color = 0xFFD58BFF.toInt()
        canvas.drawRoundRect(RectF(x, metaY - 27f, x + 32f, metaY + 5f), 5f, 5f, paint)
        canvas.drawRect(x + 1f, metaY - 37f, x + 32f, metaY - 28f, paint)
        x += 43f
        canvas.drawText(genre, x, metaY, metaPaint)
        x += metaPaint.measureText(genre) + 20f
        canvas.drawLine(x, metaY - 30f, x, metaY + 12f, separatorPaint)
        x += 20f
        drawStar(canvas, x + 13f, metaY - 10f, 17f, 8f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC928.toInt() })
        x += 36f
        canvas.drawText(rating, x, metaY, metaPaint)
        x += metaPaint.measureText(rating) + 20f
        canvas.drawLine(x, metaY - 30f, x, metaY + 12f, separatorPaint)
        x += 20f
        paint.color = 0xFFD58BFF.toInt()
        canvas.drawRoundRect(RectF(x, metaY - 29f, x + 31f, metaY + 3f), 5f, 5f, paint)
        paint.color = 0xFF38164D.toInt()
        canvas.drawRect(x + 4f, metaY - 17f, x + 27f, metaY - 13f, paint)
        x += 42f
        canvas.drawText(year, x, metaY, metaPaint)

        // Fully centered, dynamically sized synopsis; no arbitrary four-line truncation.
        synopsisLines.forEachIndexed { index, line ->
            drawCentered(canvas, line, synopsisTop + index * synopsisLineHeight, synopsisPaint)
        }

        // Only TMDB backdrops are used as scene thumbnails. Missing frames remain
        // neutral placeholders rather than being replaced by promotional artwork.
        val gap = 16f
        val stillWidth = (contentWidth - gap * 2f) / 3f
        for (index in 0..2) {
            val left = margin + index * (stillWidth + gap)
            drawRoundedImage(canvas, stills.getOrNull(index),
                RectF(left, stillTop, left + stillWidth, stillTop + stillHeight), 18f, paint)
        }

        // Film-strip decoration is drawn behind and below the CTA, not as dots/dashes.
        drawFilmStrip(canvas, -12f, stripTop, 330f, 105f, paint)
        drawFilmStrip(canvas, 760f, stripTop, 330f, 105f, paint)

        // Precisely centered play icon + label as one measured group.
        val button = RectF(190f, buttonTop, 890f, buttonBottom)
        paint.setShadowLayer(25f, 0f, 0f, 0xFF9E24FF.toInt())
        paint.shader = LinearGradient(button.left, button.top, button.left, button.bottom,
            intArrayOf(0xFFB82CFF.toInt(), 0xFF6500C9.toInt(), 0xFF35006E.toInt()),
            null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(button, 54f, 54f, paint)
        paint.shader = null
        paint.clearShadowLayer()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = 0xFFE5A8FF.toInt()
        canvas.drawRoundRect(button, 54f, 54f, paint)
        paint.style = Paint.Style.FILL
        val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 43f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val label = "Minha Indicação"
        val playWidth = 34f
        val gapToLabel = 22f
        val groupWidth = playWidth + gapToLabel + buttonTextPaint.measureText(label)
        val groupLeft = (width - groupWidth) / 2f
        val play = Path().apply {
            moveTo(groupLeft, buttonTop + 34f)
            lineTo(groupLeft, buttonTop + 72f)
            lineTo(groupLeft + playWidth, buttonTop + 53f)
            close()
        }
        buttonTextPaint.color = Color.WHITE
        canvas.drawPath(play, buttonTextPaint)
        canvas.drawText(label, groupLeft + playWidth + gapToLabel, buttonTop + 67f, buttonTextPaint)

        paint.color = 0xFF9C35E8.toInt()
        paint.strokeWidth = 2f
        val footerY = buttonBottom + 145f
        canvas.drawLine(54f, footerY - 8f, 215f, footerY - 8f, paint)
        canvas.drawLine(865f, footerY - 8f, 1026f, footerY - 8f, paint)
        paint.color = 0xFFF6F0FA.toInt()
        paint.textSize = 25f
        paint.typeface = Typeface.create("sans-serif", Typeface.ITALIC)
        drawCentered(canvas, "Compartilhe essa dica incrível do CineVerso!", footerY, paint)

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "cineverso_${item.type}_${item.id}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CineVerso")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val imageUri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: run { bitmap.recycle(); throw IllegalStateException("Não foi possível criar a imagem para compartilhamento") }
        try {
            val stream = context.contentResolver.openOutputStream(imageUri)
                ?: throw IllegalStateException("Não foi possível abrir a imagem gerada")
            stream.use {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    throw IllegalStateException("Não foi possível salvar a imagem gerada")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.update(imageUri, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null)
            }
            bitmap.recycle()
            imageUri
        } catch (error: Exception) {
            context.contentResolver.delete(imageUri, null, null)
            bitmap.recycle()
            throw error
        }
    }
