package br.com.minhamidia

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import br.com.minhamidia.data.AppDatabase
import br.com.minhamidia.data.TmdbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

class EpisodeAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences("cineverse_settings", Context.MODE_PRIVATE)
        val credential = prefs.getString("tmdb_key", null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.TMDB_API_KEY
        if (credential.isBlank()) return@withContext Result.success()

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return@withContext Result.success()

        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "cineverse_episodes"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(channelId, "Novos episódios", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Avisos sobre episódios de séries acompanhadas"
            })
        }

        return@withContext try {
            val tracked = AppDatabase.get(applicationContext).mediaDao().getTrackedShows()
            val today = LocalDate.now()
            for (show in tracked) {
                val tmdbId = show.tmdbId ?: continue
                val next = TmdbClient.nextEpisodeInfo(tmdbId, credential).optJSONObject("next_episode_to_air") ?: continue
                val dateText = next.optString("air_date")
                val airDate = runCatching { LocalDate.parse(dateText) }.getOrNull() ?: continue
                if (airDate.isBefore(today) || airDate.isAfter(today.plusDays(2))) continue

                val episodeKey = "episode_alert_${tmdbId}_${dateText}_${next.optInt("episode_number")}"
                if (prefs.getBoolean(episodeKey, false)) continue

                val season = next.optInt("season_number")
                val episode = next.optInt("episode_number")
                val episodeName = next.optString("name").takeIf { it.isNotBlank() }
                val title = "${show.title}: temporada $season, episódio $episode"
                val detail = if (episodeName != null) "$episodeName • ${dateText}" else "Lançamento previsto para $dateText"
                val notification = NotificationCompat.Builder(applicationContext, channelId)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("Novo episódio próximo")
                    .setContentText("$title • $detail")
                    .setStyle(NotificationCompat.BigTextStyle().bigText("$title\n$detail"))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .build()
                NotificationManagerCompat.from(applicationContext).notify(tmdbId, notification)
                prefs.edit().putBoolean(episodeKey, true).apply()
            }
            Result.success()
        } catch (_: SecurityException) {
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
