package fr.offline.soir

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.app.usage.UsageEvents
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Scheduler {
    private val kinds = listOf("start", "bed", "wake", "quota", "repeat", "overlay_stop")
    private fun pending(ctx: Context, kind: String): PendingIntent = PendingIntent.getBroadcast(
        ctx, kinds.indexOf(kind) + 100, Intent(ctx, AlarmReceiver::class.java).setAction("fr.offline.$kind"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun cancel(ctx: Context) {
        val alarm = ctx.getSystemService(AlarmManager::class.java)
        kinds.forEach { alarm.cancel(pending(ctx, it)) }
    }

    private fun next(c: Config, kind: String): Long? {
        if (!c.enabled || c.days.isEmpty()) return null
        val now = LocalDateTime.now()
        val at = when (kind) { "start", "quota", "repeat" -> c.start; "bed" -> c.bed; "overlay_stop" -> "04:00"; else -> c.wake }
        val time = try { LocalTime.parse(at) } catch (_: Exception) { return null }
        for (offset in 0..8) {
            val d = LocalDate.now().plusDays(offset.toLong())
            // The morning event belongs to the previous evening's schedule.
            val enabledDay = if (kind == "wake") d.minusDays(1).dayOfWeek.value else d.dayOfWeek.value
            if (kind != "overlay_stop" && enabledDay !in c.days) continue
            var target = LocalDateTime.of(d, time)
            if (kind == "wake") {
                // For early wake times, use the morning after the selected evening.
                val bed = LocalTime.parse(c.bed)
                if (!time.isAfter(bed)) { /* normal morning */ }
            } else if (kind == "bed" && time.isBefore(LocalTime.parse(c.start))) {
                target = target.plusDays(1)
            }
            if (target.isAfter(now.plusSeconds(5))) return target.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        return null
    }

    fun schedule(ctx: Context, c: Config = Store(ctx).load()) {
        cancel(ctx)
        if (!c.enabled) return
        val alarm = ctx.getSystemService(AlarmManager::class.java)
        kinds.filter { it != "repeat" }.forEach { kind ->
            if ((kind == "quota" && c.packages.isBlank()) || (kind == "wake" && !c.morning && !c.dnd && !c.morningRoutine) ||
                (kind == "overlay_stop" && (!c.unlockPopup || !c.popupOverlay))) return@forEach
            val time = next(c, kind) ?: return@forEach
            // Inexact alarms avoid special 'exact alarm' permission and can be delayed by Android.
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending(ctx, kind))
        }
    }

    fun scheduleRepeat(ctx: Context, minutes: Int) {
        val alarm = ctx.getSystemService(AlarmManager::class.java)
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + minutes * 60_000L, pending(ctx, "repeat"))
    }

    fun stopOverlay(ctx: Context) { ctx.stopService(Intent(ctx, UnlockOverlayService::class.java)) }

    fun startOverlayIfDue(ctx: Context, c: Config = Store(ctx).load()) {
        val time = LocalTime.now()
        val start = runCatching { LocalTime.parse(c.start) }.getOrDefault(LocalTime.of(22, 45))
        val validTime = time >= start || time < LocalTime.of(4,0)
        val store = Store(ctx)
        val date = eveningDate().toString()
        if (c.enabled && c.unlockPopup && c.popupOverlay && Settings.canDrawOverlays(ctx) &&
            validTime && !store.isStarted(date) && !store.isFinished(date)) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, UnlockOverlayService::class.java)) }
        } else stopOverlay(ctx)
    }

    fun stopRepeat(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, "repeat"))
        ctx.getSystemService(NotificationManager::class.java).cancel(101)
    }
    fun scheduleQuotaCheck(ctx: Context) {
        val alarm = ctx.getSystemService(AlarmManager::class.java)
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis()+15*60*1000, pending(ctx,"quota"))
    }
}

object Alerts {
    private const val CHANNEL = "offline_evening_v3"
    private const val MORNING_CHANNEL = "offline_morning"
    private const val STRONG_CHANNEL = "offline_evening_strong_v4"
    fun notify(ctx: Context, id: Int, title: String, body: String, persistent: Boolean = false, strong: Boolean = false) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Rappels OFFLINE", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(MORNING_CHANNEL, "Priorités du matin", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(STRONG_CHANNEL, "Rappels renforcés OFFLINE", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 350, 180, 350, 180, 500)
        })
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val content = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(ctx, if (id == 103) MORNING_CHANNEL else if (strong) STRONG_CHANNEL else CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (id == 103) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(!persistent).setOngoing(persistent).setContentIntent(content)
            .setCategory(if (strong) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_STATUS)
        if (id == 101) {
            val start = PendingIntent.getActivity(ctx, 151, Intent(ctx, MainActivity::class.java).putExtra("start_routine", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val postpone = PendingIntent.getBroadcast(ctx, 152, Intent(ctx, ReminderActionReceiver::class.java).setAction("postpone"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val ignore = PendingIntent.getBroadcast(ctx, 153, Intent(ctx, ReminderActionReceiver::class.java).setAction("ignore"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(0, "Je coupe", start).addAction(0, "Encore 10 min", postpone).addAction(0, "Ignorer ce soir", ignore)
        }
        NotificationManagerCompat.from(ctx).notify(id, builder.build())
    }
    fun eveningReminder(ctx: Context) {
        val store = Store(ctx)
        val c = store.load()
        val date = eveningDate().toString()
        if (!c.enabled || !c.reminder || store.isFinished(date) || store.isStarted(date) || store.getBoolean("ignored_$date") || store.getText("snooze_$date").toLongOrNull()?.let { it > System.currentTimeMillis() } == true) return
        val strong = c.reminderLevel == "strict"
        notify(ctx, 101, c.reminderTitle, c.reminderMessage, persistent = c.reminderLevel != "normal", strong = strong)
        if (c.reminderLevel != "normal") Scheduler.scheduleRepeat(ctx, if (strong) 5 else c.repeatMinutes)
    }
}

class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val store = Store(ctx)
        val date = eveningDate().toString()
        when (intent.action) {
            "ignore" -> {
                store.setBoolean("ignored_$date", true)
                Scheduler.stopRepeat(ctx)
                Scheduler.stopOverlay(ctx)
            }
            "postpone" -> {
                store.setText("snooze_$date", (System.currentTimeMillis() + 10*60_000L).toString())
                ctx.getSystemService(NotificationManager::class.java).cancel(101)
                Scheduler.scheduleRepeat(ctx, 10)
            }
        }
    }
}

fun usagePermitted(ctx: Context): Boolean {
    val ops = ctx.getSystemService(AppOpsManager::class.java)
    val result = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
    return result == AppOpsManager.MODE_ALLOWED
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val c = Store(ctx).load()
        if (!c.enabled) return
        if (intent.action != "fr.offline.quota" && intent.action != "fr.offline.repeat") Scheduler.schedule(ctx, c)
        when(intent.action?.removePrefix("fr.offline.")) {
            "start" -> {
                Scheduler.startOverlayIfDue(ctx, c)
                Alerts.eveningReminder(ctx)
                if (c.dnd) {
                    val nm = ctx.getSystemService(NotificationManager::class.java)
                    if (nm.isNotificationPolicyAccessGranted) {
                        val store = Store(ctx)
                        store.setText("old_ringer_filter", nm.currentInterruptionFilter.toString())
                        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                    }
                }
                Scheduler.scheduleQuotaCheck(ctx)
            }
            "repeat" -> { Alerts.eveningReminder(ctx) }
            "overlay_stop" -> Scheduler.stopOverlay(ctx)
            "bed" -> {
                val store = Store(ctx)
                val date = eveningDate().toString()
                if (!store.isFinished(date)) {
                    Alerts.notify(ctx, 102, "OFFLINE · Heure de dormir", "Termine ta routine puis appuie sur « Je vais dormir ».", strong = c.reminderLevel == "strict")
                    if (!store.isStarted(date)) Alerts.eveningReminder(ctx)
                }
            }
            "wake" -> {
                val store = Store(ctx)
                val previous = store.getText("old_ringer_filter").toIntOrNull()
                val nm = ctx.getSystemService(NotificationManager::class.java)
                if (previous != null && nm.isNotificationPolicyAccessGranted) {
                    nm.setInterruptionFilter(previous); store.setText("old_ringer_filter", "")
                }
                if (c.morning || c.morningRoutine) {
                    val steps = if (c.morningRoutine) listOf(c.morningStep1, c.morningStep2).filter { it.isNotBlank() }.joinToString(" · ") else ""
                    val priorities = store.getText("tomorrow_${LocalDate.now().minusDays(1)}").trim()
                    val content = listOf(steps, if (c.morning) priorities else "").filter { it.isNotBlank() }.joinToString(" — ")
                    if (content.isNotBlank()) Alerts.notify(ctx, 103, "OFFLINE · À faire ce matin", content.take(300))
                }
            }
            "quota" -> {
                val now = LocalTime.now()
                val start = runCatching { LocalTime.parse(c.start) }.getOrDefault(LocalTime.of(22,45))
                val bed = runCatching { LocalTime.parse(c.bed) }.getOrDefault(LocalTime.of(23,30))
                val within = if (start <= bed) now >= start && now <= bed else now >= start || now <= bed
                if (within) {
                    val store = Store(ctx)
                    val date = LocalDate.now().toString()
                    if (usagePermitted(ctx) && store.getText("warned_date") != date) {
                        val usage = ctx.getSystemService(UsageStatsManager::class.java)
                        val begin = LocalDateTime.of(LocalDate.now(), start).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        val packages = c.packages.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                        val end = System.currentTimeMillis()
                        val events = usage.queryEvents(begin.coerceAtMost(end), end)
                        val event = UsageEvents.Event()
                        val entered = mutableMapOf<String, Long>()
                        var ms = 0L
                        while (events.hasNextEvent()) {
                            events.getNextEvent(event)
                            val pkg = event.packageName ?: continue
                            if (pkg !in packages) continue
                            when (event.eventType) {
                                UsageEvents.Event.ACTIVITY_RESUMED -> entered[pkg] = event.timeStamp
                                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                                    val from = entered.remove(pkg)
                                    if (from != null) ms += (event.timeStamp - from).coerceAtLeast(0L)
                                }
                            }
                        }
                        entered.values.forEach { ms += (end - it).coerceAtLeast(0L) }
                        if (ms >= c.quota * 60_000L) {
                            Alerts.notify(ctx, 104, "OFFLINE · Quota de distractions", "Tu as dépassé ton quota du soir. Il est temps de décrocher.")
                            store.setText("warned_date", date)
                        }
                    }
                    Scheduler.scheduleQuotaCheck(ctx)
                }
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) { Scheduler.stopOverlay(ctx); Scheduler.schedule(ctx) }
}
