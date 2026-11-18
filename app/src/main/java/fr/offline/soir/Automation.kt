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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Scheduler {
    private val kinds = listOf("start", "bed", "wake", "quota")
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
        val at = when (kind) { "start", "quota" -> c.start; "bed" -> c.bed; else -> c.wake }
        val time = try { LocalTime.parse(at) } catch (_: Exception) { return null }
        for (offset in 0..8) {
            val d = LocalDate.now().plusDays(offset.toLong())
            // The morning event belongs to the previous evening's schedule.
            val enabledDay = if (kind == "wake") d.minusDays(1).dayOfWeek.value else d.dayOfWeek.value
            if (enabledDay !in c.days) continue
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
        kinds.forEach { kind ->
            if ((kind == "quota" && c.packages.isBlank()) || (kind == "wake" && !c.morning && !c.dnd)) return@forEach
            val time = next(c, kind) ?: return@forEach
            // Inexact alarms avoid special 'exact alarm' permission and can be delayed by Android.
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending(ctx, kind))
        }
    }

    fun scheduleQuotaCheck(ctx: Context) {
        val alarm = ctx.getSystemService(AlarmManager::class.java)
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis()+15*60*1000, pending(ctx,"quota"))
    }
}

object Alerts {
    private const val CHANNEL = "offline_evening"
    fun notify(ctx: Context, id: Int, title: String, body: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Routine du soir", NotificationManager.IMPORTANCE_DEFAULT))
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val content = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n: Notification = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title).setContentText(body).setAutoCancel(true).setContentIntent(content).build()
        NotificationManagerCompat.from(ctx).notify(id, n)
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
        if (intent.action != "fr.offline.quota") Scheduler.schedule(ctx, c)
        when(intent.action?.removePrefix("fr.offline.")) {
            "start" -> {
                if (c.reminder) Alerts.notify(ctx, 101, "OFFLINE · Fin de soirée", "Arrête les écrans et appuie sur « Je coupe » pour lancer ta routine.")
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
            "bed" -> {
                val night = Store(ctx).nights().firstOrNull { it.date == LocalDate.now().toString() }
                if (night?.sleptAt.isNullOrBlank()) Alerts.notify(ctx, 102, "OFFLINE · Heure de dormir", "Termine ta routine puis appuie sur « Je vais dormir ».")
            }
            "wake" -> {
                val store = Store(ctx)
                val previous = store.getText("old_ringer_filter").toIntOrNull()
                val nm = ctx.getSystemService(NotificationManager::class.java)
                if (previous != null && nm.isNotificationPolicyAccessGranted) {
                    nm.setInterruptionFilter(previous); store.setText("old_ringer_filter", "")
                }
                if (c.morning) {
                    val priorities = store.getText("tomorrow_${LocalDate.now().minusDays(1)}").trim()
                    if (priorities.isNotBlank()) Alerts.notify(ctx, 103, "OFFLINE · Priorités du jour", priorities.take(120))
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
    override fun onReceive(ctx: Context, intent: Intent) { Scheduler.schedule(ctx) }
}
