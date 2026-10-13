package com.okamejiiya.petcare.notify

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.okamejiiya.petcare.MainActivity
import com.okamejiiya.petcare.PetCareApp
import com.okamejiiya.petcare.R
import com.okamejiiya.petcare.model.AppData
import com.okamejiiya.petcare.model.Reminder
import java.time.LocalDateTime
import java.time.ZoneId

/** 毎日決まった時刻の通知。AlarmManager に 1 回分ずつ登録し、鳴ったら翌日分を登録し直す */
object Reminders {
    private const val CHANNEL = "reminders"
    private const val ACTION_FIRE = "com.okamejiiya.petcare.REMINDER"
    private const val EXTRA_ID = "id"
    private const val PREFS = "reminders"
    private const val KEY_IDS = "scheduled"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "ごはん・お薬の通知", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** 有効な通知をすべて登録し直し、消えたものは取り消す */
    fun scheduleAll(context: Context, data: AppData) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val petIds = data.pets.map { it.id }.toSet()
        val active = data.reminders.filter { it.enabled && it.petId in petIds }
        val activeIds = active.map { it.id.toString() }.toSet()
        for (old in prefs.getStringSet(KEY_IDS, emptySet()).orEmpty() - activeIds) {
            old.toLongOrNull()?.let { cancel(context, it) }
        }
        for (r in active) schedule(context, r)
        prefs.edit().putStringSet(KEY_IDS, activeIds).apply()
    }

    fun nextTrigger(hour: Int, minute: Int, now: LocalDateTime = LocalDateTime.now()): LocalDateTime {
        val today = now.toLocalDate().atTime(hour, minute)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    private fun schedule(context: Context, r: Reminder) {
        val am = context.getSystemService(AlarmManager::class.java)
        val at = nextTrigger(r.hour, r.minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pi = pendingIntent(context, r.id)
        try {
            if (canScheduleExact(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60 * 1000L, pi)
            }
        } catch (_: SecurityException) {
            am.setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60 * 1000L, pi)
        }
    }

    private fun cancel(context: Context, id: Long) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context, id))
    }

    private fun pendingIntent(context: Context, id: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        id.hashCode(),
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    internal fun handle(context: Context, intent: Intent) {
        val store = (context.applicationContext as PetCareApp).store
        val data = store.state.value
        when (intent.action) {
            ACTION_FIRE -> {
                val id = intent.getLongExtra(EXTRA_ID, -1)
                val r = data.reminders.firstOrNull { it.id == id && it.enabled } ?: return
                val pet = data.pets.firstOrNull { it.id == r.petId } ?: return
                show(context, r, pet.name)
                schedule(context, r)
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> scheduleAll(context, data)
        }
    }

    private fun show(context: Context, r: Reminder, petName: String) {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${r.kind.emoji} ${petName}の${r.kind.label}の時間です")
            .setContentText(r.memo.ifBlank { "記録はアプリからワンタップでできます" })
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            nm.notify(r.id.hashCode(), n)
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.handle(context, intent)
    }
}
