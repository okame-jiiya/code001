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
import java.time.LocalDateTime
import java.time.ZoneId

/** 毎日決まった時刻の通知。AlarmManager に 1 回分ずつ登録し、鳴ったら翌日分を登録し直す */
object Reminders {
    private const val CHANNEL = "reminders"
    private const val ACTION_FIRE = "com.okamejiiya.petcare.REMINDER"
    private const val EXTRA_KEY = "key"
    private const val PREFS = "reminders"
    private const val KEY_IDS = "scheduled"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "ごはん・お薬・予定の通知", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /**
     * 有効な通知をすべて登録し直し、消えたものは取り消す。
     * 毎日の通知は "r<id>"、けんこうの予定の通知は "h<id>:前日" "h<id>:当日" というキーで管理する。
     */
    fun scheduleAll(context: Context, data: AppData) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val petIds = data.pets.map { it.id }.toSet()
        val now = LocalDateTime.now()
        val alarms = mutableMapOf<String, Long>()
        for (r in data.reminders) {
            if (r.enabled && r.petId in petIds) alarms["r${r.id}"] = millis(nextTrigger(r.hour, r.minute, now))
        }
        for (h in data.health) {
            if (h.petId !in petIds) continue
            for ((label, at) in h.notifyTimes()) {
                if (at.isAfter(now)) alarms["h${h.id}:$label"] = millis(at)
            }
        }
        for (old in prefs.getStringSet(KEY_IDS, emptySet()).orEmpty() - alarms.keys) cancel(context, old)
        for ((key, at) in alarms) schedule(context, key, at)
        prefs.edit().putStringSet(KEY_IDS, alarms.keys).apply()
    }

    fun nextTrigger(hour: Int, minute: Int, now: LocalDateTime = LocalDateTime.now()): LocalDateTime {
        val today = now.toLocalDate().atTime(hour, minute)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    private fun millis(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun schedule(context: Context, key: String, at: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pendingIntent(context, key)
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

    private fun cancel(context: Context, key: String) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context, key))
    }

    private fun pendingIntent(context: Context, key: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        // 旧バージョンは数字の id そのものをキーにしていたので、取り消せるよう同じ requestCode にする
        key.toLongOrNull()?.hashCode() ?: key.hashCode(),
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_KEY, key),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    internal fun handle(context: Context, intent: Intent) {
        val store = (context.applicationContext as PetCareApp).store
        val data = store.state.value
        when (intent.action) {
            ACTION_FIRE -> {
                val key = intent.getStringExtra(EXTRA_KEY) ?: return
                notificationFor(data, key)?.let { (title, text) -> show(context, key, title, text) }
                scheduleAll(context, data)
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> scheduleAll(context, data)
        }
    }

    private fun notificationFor(data: AppData, key: String): Pair<String, String>? {
        if (key.startsWith("r")) {
            val r = data.reminders.firstOrNull { "r${it.id}" == key && it.enabled } ?: return null
            val pet = data.pets.firstOrNull { it.id == r.petId } ?: return null
            return "${r.kind.emoji} ${pet.name}の${r.kind.label}の時間です" to
                r.memo.ifBlank { "記録はアプリからワンタップでできます" }
        }
        val id = key.removePrefix("h").substringBefore(":")
        val h = data.health.firstOrNull { it.id.toString() == id } ?: return null
        val pet = data.pets.firstOrNull { it.id == h.petId } ?: return null
        val whenText = (if (key.endsWith("前日")) "明日" else "今日") + (h.time?.let { " ${it.hour}:%02d".format(it.minute) } ?: "")
        return "${h.kind.emoji} ${whenText}は${pet.name}の${h.kind.label}です" to h.title
    }

    private fun show(context: Context, key: String, title: String, text: String) {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            nm.notify(key.hashCode(), n)
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.handle(context, intent)
    }
}
