package br.com.minhamidia

import android.content.Context
import android.graphics.*
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
import java.util.Locale
import kotlin.math.max

private fun downloadShareImage(url: String): Bitmap? = runCatching {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 12000
    c.readTimeout = 12000
    c.instanceFollowRedirects = true
    try { c.inputStream.use { BitmapFactory.decodeStream(it) } } finally { c.disconnect() }
}.getOrNull()

private fun wrapShareText(text: String, paint: Paint, maxWidth: Float): List<String> {
    val lines = mutableListOf<String>()
    var line = ""
    text.split(Regex("\\s+")).forEach { word ->
        val candidate = if (line.isEmpty()) word else "${line} ${word}"
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

private fun drawClapper(canvas: Canvas, x: Float, y: Float, paint: Paint) {
    paint.color = 0xFFE9DDF5.toInt()
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 3.5f
    canvas.drawRoundRect(RectF(x,y+8,x+30,y+29),3f,3f,paint)
    canvas.drawLine(x+1,y+7,x+29,y+1,paint)
    canvas.drawLine(x+8,y+5,x+12,y+12,paint)
    canvas.drawLine(x+18,y+3,x+22,y+10,paint)
    paint.style = Paint.Style.FILL
}

private fun drawStar(canvas: Canvas, x: Float, y: Float, paint: Paint) {
    val path = Path()
    for (i in 0 until 10) {
        val angle = Math.toRadians(-90.0 + i * 36.0)
        val radius = if (i % 2 == 0) 16f else 7f
        val px = x + 16f + kotlin.math.cos(angle).toFloat() * radius
        val py = y + 16f + kotlin.math.sin(angle).toFloat() * radius
        if (i == 0) path.moveTo(px,py) else path.lineTo(px,py)
    }
    path.close()
    paint.color = 0xFFFFC928.toInt()
    canvas.drawPath(path,paint)
}

private fun drawCalendar(canvas: Canvas, x: Float, y: Float, paint: Paint) {
    paint.color = 0xFFE9DDF5.toInt()
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 3f
    canvas.drawRoundRect(RectF(x,y+4,x+29,y+30),3f,3f,paint)
    canvas.drawLine(x,y+12,x+29,y+12,paint)
    canvas.drawLine(x+8,y,x+8,y+8,paint)
    canvas.drawLine(x+21,y,x+21,y+8,paint)
    paint.style = Paint.Style.FILL
}

suspend fun createCineVersoShareCard(context: Context, item: TmdbResult, credential: String): Uri =
    withContext(Dispatchers.IO) {
        val info = runCatching { TmdbClient.shareCardDetails(item.id, item.type, credential) }.getOrNull()
        val artPath = info?.backdropPath ?: item.posterPath
        val hero = artPath?.let { downloadShareImage("https://image.tmdb.org/t/p/w1280${it}") }
        val stills = info?.stillPaths.orEmpty().take(3).mapNotNull {
            downloadShareImage("https://image.tmdb.org/t/p/w780${it}")
        }

        val width = 1080
        val margin = 64f
        val contentWidth = width - margin * 2
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        p.textSize = 28f
        p.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        val synopsis = wrapShareText(
            item.overview.ifBlank { "Uma história para entrar na sua lista." },
            p, contentWidth
        )
        val synopsisLineHeight = 42f
        val synopsisHeight = max(1, synopsis.size) * synopsisLineHeight
        val heroHeight = 500f
        val metadataY = heroHeight + 74f
        val synopsisTitleY = metadataY + 105f
        val synopsisStartY = synopsisTitleY + 58f
        val stillTitleY = synopsisStartY + synopsisHeight + 54f
        val stillTop = stillTitleY + 30f
        val stillHeight = 190f
        val buttonTop = stillTop + stillHeight + 54f
        val height = (buttonTop + 130f).toInt()

        val bitmap = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        p.shader = LinearGradient(0f,0f,1080f,height.toFloat(),
            intArrayOf(0xFF08060E.toInt(),0xFF21102F.toInt(),0xFF090710.toInt()),null,Shader.TileMode.CLAMP)
        canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),p)
        p.shader = null

        if (hero != null) {
            drawCrop(canvas,hero,RectF(0f,0f,width.toFloat(),heroHeight),p)
            hero.recycle()
        } else {
            p.color = 0xFF29183B.toInt()
            canvas.drawRect(0f,0f,width.toFloat(),heroHeight,p)
        }
        p.shader = LinearGradient(0f,0f,0f,heroHeight,
            intArrayOf(0xB8080610.toInt(),0x00080610,0xFF090710.toInt()),null,Shader.TileMode.CLAMP)
        canvas.drawRect(0f,0f,width.toFloat(),heroHeight,p)
        p.shader = null

        val logo = BitmapFactory.decodeResource(context.resources,R.drawable.cineverse_logo_horizontal)
        if (logo != null) {
            val scale = minOf(250f/logo.width,58f/logo.height)
            val w = logo.width*scale
            val h = logo.height*scale
            canvas.drawBitmap(logo,null,RectF((width-w)/2f,38f,(width+w)/2f,38f+h),p)
            logo.recycle()
        }

        p.color = 0xFFE2C8FF.toInt()
        p.textSize = 19f
        p.typeface = Typeface.create("sans-serif-medium",Typeface.BOLD)
        val eyebrow = "RECOMENDAÇÃO EXCLUSIVA"
        canvas.drawText(eyebrow,(width-p.measureText(eyebrow))/2f,145f,p)

        p.color = Color.WHITE
        p.textSize = 72f
        p.typeface = Typeface.create("sans-serif-condensed",Typeface.BOLD)
        val titleLines = wrapShareText(item.title.uppercase(Locale("pt","BR")),p,960f).take(2)
        var titleY = if (titleLines.size == 1) 385f else 330f
        titleLines.forEach { canvas.drawText(it,(width-p.measureText(it))/2f,titleY,p); titleY += 78f }

        // Informações em uma única linha: claquete | estrela | calendário.
        val genre = info?.genres.orEmpty().take(2).joinToString(", ").ifBlank { "Gênero" }
        val rating = if (item.tmdbVoteAverage > 0.0) String.format(Locale("pt","BR"),"%.1f",item.tmdbVoteAverage) else "—"
        val year = item.year.ifBlank { "—" }
        p.textSize = 25f
        p.typeface = Typeface.create("sans-serif-medium",Typeface.BOLD)
        val iconY = metadataY - 25f
        val genreWidth = p.measureText(genre)
        val ratingWidth = p.measureText(rating)
        val yearWidth = p.measureText(year)
        val separatorWidth = p.measureText("|")
        val metadataWidth = 42f + genreWidth + 28f + separatorWidth + 27f +
            43f + ratingWidth + 28f + separatorWidth + 27f + 42f + yearWidth
        var x = (width - metadataWidth) / 2f
        drawClapper(canvas,x,iconY,p)
        x += 42f
        p.color = 0xFFF0EAF5.toInt()
        canvas.drawText(genre,x,metadataY,p)
        x += genreWidth + 28f
        p.color = 0xFF9B5CFF.toInt()
        canvas.drawText("|",x,metadataY,p)
        x += 27f
        drawStar(canvas,x,iconY,p)
        x += 43f
        p.color = 0xFFF0EAF5.toInt()
        canvas.drawText(rating,x,metadataY,p)
        x += ratingWidth + 28f
        p.color = 0xFF9B5CFF.toInt()
        canvas.drawText("|",x,metadataY,p)
        x += 27f
        drawCalendar(canvas,x,iconY,p)
        x += 42f
        p.color = 0xFFF0EAF5.toInt()
        canvas.drawText(year,x,metadataY,p)

        p.color = 0xFFB98AFF.toInt()
        p.textSize = 21f
        p.typeface = Typeface.create("sans-serif-medium",Typeface.BOLD)
        val synopsisLabel = "SINOPSE"
        canvas.drawText(synopsisLabel,(width-p.measureText(synopsisLabel))/2f,synopsisTitleY,p)

        p.color = 0xFFF0EAF5.toInt()
        p.textSize = 28f
        p.typeface = Typeface.create("sans-serif",Typeface.NORMAL)
        var sy = synopsisStartY
        synopsis.forEach { canvas.drawText(it,(width-p.measureText(it))/2f,sy,p); sy += synopsisLineHeight }

        p.color = 0xFFB98AFF.toInt()
        p.textSize = 21f
        p.typeface = Typeface.create("sans-serif-medium",Typeface.BOLD)
        val stillLabel = "CENAS DO FILME"
        canvas.drawText(stillLabel,(width-p.measureText(stillLabel))/2f,stillTitleY,p)

        val gap = 16f
        val cardWidth = (contentWidth-gap*2)/3f
        for (i in 0 until 3) {
            val left = margin + i*(cardWidth+gap)
            val rect = RectF(left,stillTop,left+cardWidth,stillTop+stillHeight)
            p.color = 0xFF2B1A3A.toInt()
            canvas.drawRoundRect(rect,14f,14f,p)
            val scene = stills.getOrNull(i)
            if (scene != null) {
                canvas.save()
                val clip = Path().apply { addRoundRect(rect,14f,14f,Path.Direction.CW) }
                canvas.clipPath(clip)
                drawCrop(canvas,scene,rect,p)
                canvas.restore()
                scene.recycle()
            } else {
                p.color = 0xFF9B5CFF.toInt()
                p.textSize = 17f
                val placeholder = "CINEVERSO"
                canvas.drawText(placeholder,left+(cardWidth-p.measureText(placeholder))/2f,stillTop+stillHeight/2,p)
            }
        }

        p.color = 0xFF8A42F5.toInt()
        canvas.drawRoundRect(RectF(margin,buttonTop,1080f-margin,buttonTop+82f),20f,20f,p)
        p.color = Color.WHITE
        p.textSize = 29f
        p.typeface = Typeface.create("sans-serif",Typeface.BOLD)
        val cta = "MINHA INDICAÇÃO"
        val ctaWidth = p.measureText(cta)
        canvas.drawText(cta,(width-ctaWidth)/2f,buttonTop+53f,p)

        p.color = 0xFFE8DDF2.toInt()
        p.textSize = 17f
        p.typeface = Typeface.create("sans-serif-medium",Typeface.BOLD)
        val footer = "CINEVERSO"
        canvas.drawText(footer,(width-p.measureText(footer))/2f,buttonTop+112f,p)

        val dir = File(context.cacheDir,"shared_recommendations").apply { mkdirs() }
        val output = File(dir,"cineverso_${item.type}_${item.id}.png")
        FileOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
        FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",output)
    }
