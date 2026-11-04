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

private fun wrapShareText(text: String, paint: Paint, maxWidth: Float, maxLines: Int): List<String> {
    val lines = mutableListOf<String>()
    var line = ""
    for (word in text.split(Regex("\\s+"))) {
        val candidate = if (line.isEmpty()) word else "${line} ${word}"
        if (paint.measureText(candidate) > maxWidth && line.isNotEmpty()) {
            lines.add(line); line = word
            if (lines.size == maxLines) break
        } else line = candidate
    }
    if (lines.size < maxLines && line.isNotEmpty()) lines.add(line)
    if (lines.size == maxLines && text.isNotBlank()) {
        var last = lines.last()
        while (paint.measureText("${last}…") > maxWidth && last.isNotEmpty()) last = last.dropLast(1)
        lines[lines.lastIndex] = "${last}…"
    }
    return lines
}

private fun drawCrop(canvas: Canvas, image: Bitmap, target: RectF, paint: Paint) {
    val scale = max(target.width() / image.width, target.height() / image.height)
    val w = image.width * scale; val h = image.height * scale
    canvas.drawBitmap(image, null, RectF(target.centerX()-w/2, target.centerY()-h/2,
        target.centerX()+w/2, target.centerY()+h/2), paint)
}

suspend fun createCineVersoShareCard(context: Context, item: TmdbResult, credential: String): Uri =
    withContext(Dispatchers.IO) {
        val info = runCatching { TmdbClient.shareCardDetails(item.id, item.type, credential) }.getOrNull()
        val bitmap = Bitmap.createBitmap(1080, 1080, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        p.shader = LinearGradient(0f,0f,1080f,1080f,
            intArrayOf(0xFF08060E.toInt(),0xFF21102F.toInt(),0xFF090710.toInt()),null,Shader.TileMode.CLAMP)
        canvas.drawRect(0f,0f,1080f,1080f,p); p.shader=null

        val artPath = info?.backdropPath ?: item.posterPath
        val art = artPath?.let { downloadShareImage("https://image.tmdb.org/t/p/w1280${it}") }
        if (art != null) { drawCrop(canvas,art,RectF(0f,0f,1080f,690f),p); art.recycle() }
        else { p.color=0xFF29183B.toInt(); canvas.drawRect(0f,0f,1080f,690f,p) }

        p.shader=LinearGradient(0f,0f,0f,730f,
            intArrayOf(0xB8080610.toInt(),0x00080610,0xFF090710.toInt()),null,Shader.TileMode.CLAMP)
        canvas.drawRect(0f,0f,1080f,730f,p); p.shader=null
        p.shader=LinearGradient(0f,430f,0f,760f,
            intArrayOf(0x00090710,0xFF090710.toInt()),null,Shader.TileMode.CLAMP)
        canvas.drawRect(0f,430f,1080f,760f,p); p.shader=null

        val logo=BitmapFactory.decodeResource(context.resources,R.drawable.cineverse_logo_horizontal)
        if (logo!=null) {
            val scale=minOf(250f/logo.width,58f/logo.height); val w=logo.width*scale; val h=logo.height*scale
            canvas.drawBitmap(logo,null,RectF(58f,42f,58f+w,42f+h),p); logo.recycle()
        }

        p.color=0xFFE2C8FF.toInt(); p.textSize=19f
        p.typeface=Typeface.create("sans-serif-medium",Typeface.BOLD)
        canvas.drawText("RECOMENDAÇÃO EXCLUSIVA",60f,150f,p)

        p.color=Color.WHITE; p.textSize=76f
        p.typeface=Typeface.create("sans-serif-condensed",Typeface.BOLD)
        val titles=wrapShareText(item.title.uppercase(Locale("pt","BR")),p,960f,2)
        var ty=if(titles.size==1) 515f else 455f
        titles.forEach { canvas.drawText(it,60f,ty,p); ty+=82f }

        p.color=0xFFE8DDF2.toInt(); p.textSize=25f
        p.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        val actor=info?.leadActor?.takeIf { it.isNotBlank() } ?: "Elenco em destaque"
        canvas.drawText("COM ${actor}",64f,600f,p)

        val genres=info?.genres.orEmpty().take(2).joinToString("  /  ")
        val metadata=buildList {
            if(item.year.isNotBlank()) add(item.year)
            if(genres.isNotBlank()) add(genres.uppercase(Locale("pt","BR")))
            add(if(item.type.equals("tv",true)) "SÉRIE" else "FILME")
            if(item.tmdbVoteAverage>0.0) add("★ %.1f".format(Locale.US,item.tmdbVoteAverage))
        }.joinToString("     •     ")
        p.color=0xFFB98AFF.toInt(); p.textSize=22f
        p.typeface=Typeface.create("sans-serif-medium",Typeface.BOLD)
        canvas.drawText(metadata,64f,660f,p)

        p.color=0xFF9B5CFF.toInt(); canvas.drawRoundRect(RectF(64f,720f,1016f,724f),2f,2f,p)
        p.color=0xFFB98AFF.toInt(); p.textSize=19f
        canvas.drawText("SOBRE A HISTÓRIA",64f,775f,p)

        p.color=0xFFF0EAF5.toInt(); p.textSize=27f
        p.typeface=Typeface.create("sans-serif",Typeface.NORMAL)
        val synopsis=wrapShareText(item.overview.ifBlank { "Uma história para entrar na sua lista." },p,950f,5)
        var sy=825f
        synopsis.forEach { canvas.drawText(it,64f,sy,p); sy+=38f }

        p.color=0xFF9B5CFF.toInt(); canvas.drawRoundRect(RectF(64f,1000f,1016f,1004f),2f,2f,p)
        p.color=0xFFE8DDF2.toInt(); p.textSize=18f
        p.typeface=Typeface.create("sans-serif-medium",Typeface.BOLD)
        canvas.drawText("CINEVERSO  •  MINHA INDICAÇÃO",64f,1045f,p)

        val dir=File(context.cacheDir,"shared_recommendations").apply { mkdirs() }
        val output=File(dir,"cineverso_${item.type}_${item.id}.png")
        FileOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
        FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",output)
    }
