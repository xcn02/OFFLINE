package fr.offline.soir

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.time.LocalTime

/** Optional user-authorized overlay. The foreground service is needed for reliable unlock listening.
 *  Availability remains subject to Android battery/service restrictions. */
class UnlockOverlayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var window: LinearLayout? = null
    private var receiverRegistered = false
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) handler.postDelayed({ showIfDue() }, 1500)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("offline_overlay", "Surveillance du déverrouillage", NotificationManager.IMPORTANCE_LOW))
        val notification: Notification = NotificationCompat.Builder(this, "offline_overlay")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("OFFLINE · rappel au déverrouillage")
            .setContentText("Actif lorsque les rappels au déverrouillage sont autorisés")
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(201, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(201, notification)
        registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
        receiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val c = Store(this).load()
        if (!c.unlockPopup || !c.popupOverlay || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun showIfDue() {
        val store = Store(this)
        val cfg = store.load()
        val date = eveningDate().toString()
        val current = LocalTime.now()
        val start = runCatching { LocalTime.parse(cfg.start) }.getOrDefault(LocalTime.of(22,45))
        val isEvening = current >= start || current < LocalTime.of(4,0)
        if (!cfg.enabled || !cfg.unlockPopup || !cfg.popupOverlay || !Settings.canDrawOverlays(this) || !isEvening ||
            store.isFinished(date) || store.isStarted(date) ||
            (store.getText("snooze_$date").toLongOrNull() ?: 0L) > System.currentTimeMillis()) return
        if (window != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        val bg = GradientDrawable().apply { setColor(Color.rgb(23,32,51)); cornerRadius = 20*density }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((22*density).toInt(),(24*density).toInt(),(22*density).toInt(),(24*density).toInt())
            background = bg
            elevation = 12*density
        }
        fun label(value: String, size: Float): TextView = TextView(this).apply {
            text = value; textSize = size; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(0, (6*density).toInt(), 0, (12*density).toInt())
        }
        root.addView(label(cfg.reminderTitle, 21f))
        root.addView(label(cfg.reminderMessage, 15f))
        fun btn(text: String, onClick: () -> Unit) {
            root.addView(Button(this).apply {
                this.text = text
                setOnClickListener { removeOverlay(); onClick() }
            }, LinearLayout.LayoutParams(-1, (52*density).toInt()))
        }
        btn("Je coupe") {
            startActivity(Intent(this, MainActivity::class.java).putExtra("start_routine", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
        btn("Encore 10 minutes") {
            store.setText("snooze_$date", (System.currentTimeMillis()+10*60_000L).toString())
            Scheduler.scheduleRepeat(this, 10)
        }
        btn("Ignorer pour ce soir") {
            store.setBoolean("ignored_$date", true)
            Scheduler.stopRepeat(this)
        }
        val params = WindowManager.LayoutParams((resources.displayMetrics.widthPixels*0.88).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.CENTER }
        runCatching { wm.addView(root, params); window = root }
    }

    private fun removeOverlay() {
        val view = window ?: return
        window = null
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view) }
    }

    override fun onDestroy() {
        removeOverlay()
        if (receiverRegistered) unregisterReceiver(unlockReceiver)
        super.onDestroy()
    }
}
