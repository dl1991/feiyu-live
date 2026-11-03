package com.dl1991.feiyu.dafeiyu

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * WorkManager scheduling for the life loop. Periodic (user interval, ≥ 15 min), network required,
 * battery-not-low, optionally unmetered only; the worker itself also skips while Battery Saver is
 * on and outside the active hours. Call [sync] after settings change and once at app start.
 */
object DfyWorker {
    const val WORK = "mibot_dafeiyu_life"
    const val WORK_NOW = "mibot_dafeiyu_life_now"

    private fun constraints(s: DfySettings) = Constraints.Builder()
        .setRequiredNetworkType(if (s.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    fun sync(ctx: Context) {
        val s = DfyStore.snapshot(ctx).settings
        val wm = WorkManager.getInstance(ctx.applicationContext)
        if (!s.lifeEnabled) { wm.cancelUniqueWork(WORK); return }
        val req = PeriodicWorkRequestBuilder<Worker>(s.intervalMinutes.toLong().coerceAtLeast(15), TimeUnit.MINUTES)
            .setInitialDelay(5, TimeUnit.MINUTES)
            .setConstraints(constraints(s))
            .addTag(WORK)
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** One run right now (user tapped 现在去逛逛 / agent run_now). */
    fun runNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<Worker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(androidx.work.workDataOf("manual" to true))
            .build()
        WorkManager.getInstance(ctx.applicationContext).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.KEEP, req)
    }

    class Worker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val manual = inputData.getBoolean("manual", false)
            DfyLife.runOnce(applicationContext, manual)
            DfyOverlay.refresh(applicationContext)
            return Result.success() // failures are recorded in meta; WorkManager retries would double-spend quotas
        }
    }
}

object DfyNotify {
    private const val CHANNEL = "mibot_dafeiyu"
    private const val CHANNEL_ACT = "mibot_dafeiyu_actions"

    private fun canPost(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun channels(nm: NotificationManager, ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "大肥鱼的日常", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ACT, "大肥鱼 · 待确认操作", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun open(ctx: Context, deep: String, code: Int): PendingIntent? {
        val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
            action = Intent.ACTION_VIEW; data = Uri.parse(deep); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return null
        return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun digest(ctx: Context, text: String) {
        if (!canPost(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        channels(nm, ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("大肥鱼刚逛完 B站")
            .setContentText(text)
            .setAutoCancel(true)
            .apply { open(ctx, DfyRoutes.DEEP_HOME, 0x0DF1)?.let { setContentIntent(it) } }
            .build()
        runCatching { nm.notify(0x0DF1, n) }
    }

    fun pendingAction(ctx: Context, a: BiliAction) {
        if (!canPost(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        channels(nm, ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ACT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("大肥鱼想${BiliActions.label(a.type)}：${a.targetLabel}")
            .setContentText(a.content.ifBlank { a.reason }.ifBlank { "点开确认" })
            .setAutoCancel(true)
            .apply { open(ctx, DfyRoutes.DEEP_PENDING, 0x0DF2)?.let { setContentIntent(it) } }
            .build()
        runCatching { nm.notify(0x0DF2, n) }
    }
}
