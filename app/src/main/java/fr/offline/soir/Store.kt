package fr.offline.soir

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

fun eveningDate(): LocalDate = LocalDate.now().let { if (LocalTime.now().isBefore(LocalTime.of(4, 0))) it.minusDays(1) else it }

data class Task(val id: Long, val name: String, val minutes: Int, val enabled: Boolean = true, val optional: Boolean = false)
data class Config(
    val start: String = "22:45", val bed: String = "23:30", val wake: String = "07:30",
    val days: Set<Int> = (1..7).toSet(), val enabled: Boolean = true,
    val gray: Boolean = true, val dnd: Boolean = false, val plane: Boolean = false,
    val reminder: Boolean = true, val morning: Boolean = true, val quota: Int = 15,
    val packages: String = "com.google.android.youtube,com.instagram.android,com.twitter.android,com.reddit.frontpage",
    val reminderLevel: String = "persistent", val repeatMinutes: Int = 10,
    val unlockPopup: Boolean = true, val popupOverlay: Boolean = false,
    val reminderTitle: String = "Il est temps de décrocher",
    val reminderMessage: String = "Éteins le PC et commence ta routine du soir.",
    val transition: String = "hybrid", val planeConfirm: Boolean = false, val stepSound: Boolean = false,
    val tasks: List<Task> = listOf(
        Task(2, "Étirements", 5), Task(3, "Préparer le lendemain", 3),
        Task(4, "Journal rapide", 2), Task(1, "Lecture", 15)
    )
)

data class Night(val date: String, val cutAt: String = "", val sleptAt: String = "", val reason: String = "")

class Store(private val ctx: Context) {
    private val p = ctx.getSharedPreferences("offline_v1", Context.MODE_PRIVATE)

    fun load(): Config = try {
        val j = JSONObject(p.getString("config", "{}") ?: "{}")
        val defaults = Config()
        val taskJson = j.optJSONArray("tasks")
        val tasks = if (taskJson == null) defaults.tasks else (0 until taskJson.length()).map { i ->
            val t = taskJson.getJSONObject(i)
            Task(t.getLong("id"), t.getString("name"), t.getInt("minutes"), t.optBoolean("enabled", true), t.optBoolean("optional", false))
        }
        val ds = j.optJSONArray("days")
        val days = if (ds == null) defaults.days else (0 until ds.length()).map { ds.getInt(it) }.toSet()
        Config(j.optString("start", defaults.start), j.optString("bed", defaults.bed),
            j.optString("wake", defaults.wake), days, j.optBoolean("enabled", true),
            j.optBoolean("gray", true), j.optBoolean("dnd", false), j.optBoolean("plane", false),
            j.optBoolean("reminder", true), j.optBoolean("morning", true),
            j.optInt("quota", 15).coerceIn(1, 120),
            j.optString("packages", defaults.packages).let { previous ->
                if (previous == "com.twitter.android,com.reddit.frontpage") defaults.packages else previous
            },
            j.optString("reminderLevel", "persistent"), j.optInt("repeatMinutes", 10).coerceIn(5, 30),
            j.optBoolean("unlockPopup", true), j.optBoolean("popupOverlay", false),
            j.optString("reminderTitle", "Il est temps de décrocher"),
            j.optString("reminderMessage", "Éteins le PC et commence ta routine du soir."),
            j.optString("transition", "hybrid").takeIf { it in setOf("hybrid", "auto", "manual") } ?: "hybrid",
            j.optBoolean("planeConfirm", false), j.optBoolean("stepSound", false), tasks)
    } catch (_: Exception) { Config() }

    fun save(c: Config) {
        val j = JSONObject().apply {
            put("start", c.start); put("bed", c.bed); put("wake", c.wake)
            put("days", JSONArray(c.days.sorted())); put("enabled", c.enabled)
            put("gray", c.gray); put("dnd", c.dnd); put("plane", c.plane)
            put("reminder", c.reminder); put("morning", c.morning)
            put("quota", c.quota); put("packages", c.packages)
            put("reminderLevel", c.reminderLevel); put("repeatMinutes", c.repeatMinutes)
            put("unlockPopup", c.unlockPopup); put("popupOverlay", c.popupOverlay)
            put("reminderTitle", c.reminderTitle); put("reminderMessage", c.reminderMessage)
            put("transition", c.transition); put("planeConfirm", c.planeConfirm); put("stepSound", c.stepSound)
            put("tasks", JSONArray().apply { c.tasks.forEach { t ->
                put(JSONObject().apply {put("id", t.id); put("name", t.name); put("minutes", t.minutes); put("enabled", t.enabled); put("optional", t.optional) })
            } })
        }
        p.edit().putString("config", j.toString()).apply()
        Scheduler.schedule(ctx, c)
    }

    fun nights(): List<Night> = try {
        val a = JSONArray(p.getString("nights", "[]")); (0 until a.length()).map { i ->
            val j = a.getJSONObject(i)
            Night(j.getString("date"), j.optString("cutAt"), j.optString("sleptAt"), j.optString("reason"))
        }
    } catch (_: Exception) { emptyList() }

    fun updateNight(date: String = eveningDate().toString(), transform: (Night) -> Night) {
        val all = nights().toMutableList()
        val i = all.indexOfFirst { it.date == date }
        val old = if (i < 0) Night(date) else all[i]
        val new = transform(old)
        if (i < 0) all.add(0, new) else all[i] = new
        p.edit().putString("nights", JSONArray().apply { all.take(90).forEach { n ->
            put(JSONObject().apply { put("date", n.date); put("cutAt", n.cutAt); put("sleptAt", n.sleptAt); put("reason", n.reason) })
        } }.toString()).apply()
    }
    data class Session(val date: String, val taskId: Long, val startedMs: Long, val pausedMs: Long = 0L, val completed: Boolean = false)
    fun session(): Session? { return try {
        val raw = p.getString("active_session", null) ?: return null
        val j = JSONObject(raw)
        Session(j.getString("date"), j.getLong("taskId"), j.getLong("startedMs"), j.optLong("pausedMs"), j.optBoolean("completed"))
    } catch (_: Exception) { null }
    }
    fun saveSession(s: Session?) {
        if (s == null) { p.edit().remove("active_session").apply(); return }
        val json = JSONObject().apply {
            put("date", s.date); put("taskId", s.taskId); put("startedMs", s.startedMs)
            put("pausedMs", s.pausedMs); put("completed", s.completed)
        }
        p.edit().putString("active_session", json.toString()).apply()
    }
    fun isFinished(date: String): Boolean = nights().any { it.date == date && it.sleptAt.isNotBlank() } || getBoolean("ignored_$date")
    fun isStarted(date: String): Boolean = nights().any { it.date == date && it.cutAt.isNotBlank() }
    fun ensureNight(date: String) { updateNight(date) { it } }
    fun getText(key: String) = p.getString(key, "") ?: ""
    fun setText(key: String, value: String) = p.edit().putString(key, value).apply()
    fun getBoolean(key: String) = p.getBoolean(key, false)
    fun setBoolean(key: String, value: Boolean) = p.edit().putBoolean(key, value).apply()
    fun clear() { p.edit().clear().apply(); Scheduler.cancel(ctx) }
}
