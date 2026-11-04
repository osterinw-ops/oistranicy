package ru.moistranicy.app.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ru.moistranicy.app.R
import ru.moistranicy.app.ui.MainActivity
import java.util.concurrent.TimeUnit

/** Раз в 12 часов проверяет отслеживаемые циклы на FantLab и уведомляет о новых книгах. */
class CycleCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        CycleRepository.init(applicationContext)
        if (CycleRepository.cycles.value.isEmpty()) return Result.success()
        val news = CycleRepository.checkAll()
        if (news.isNotEmpty()) Notifier.notifyNews(applicationContext, news)
        return Result.success()
    }

    companion object {
        private const val NAME = "cycle-check"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<CycleCheckWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

object Notifier {
    private const val CHANNEL = "new_books"
    const val EXTRA_OPEN = "open"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Новые книги в циклах", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Когда в отслеживаемом цикле выходит или анонсируется новая книга"
                },
            )
        }
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun notifyNews(context: Context, news: List<CycleNews>) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN, "cycles")
        }
        val pi = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val nm = NotificationManagerCompat.from(context)
        news.groupBy { it.cycleId }.values.forEach { group ->
            val first = group.first()
            val titles = group.joinToString(", ") { "«${it.book.title}»" }
            val verb = if (group.all { it.book.published }) "Новая книга" else "Анонс"
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_book)
                .setContentTitle("$verb в цикле «${first.cycleName}»")
                .setContentText(titles)
                .setStyle(NotificationCompat.BigTextStyle().bigText(titles))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            try {
                nm.notify(first.cycleId.hashCode(), n)
            } catch (_: SecurityException) {
            }
        }
    }
}
