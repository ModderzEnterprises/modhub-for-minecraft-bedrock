package us.fishfarts.modhub

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.FileObserver
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Progress/result channel between [ImportService] and the UI. */
object ImportBus {
    val status = MutableStateFlow<String?>(null)
    /** null while running; otherwise "ok" / "ok-manual" (needs the user's tap to restart Minecraft) / "error: ...". */
    val result = MutableStateFlow<String?>(null)
}

/** Helpers for restarting Minecraft. (An app can't close another app on current Android: killBackgroundProcesses is silently ignored.) */
object MinecraftRestarter {
    /** Opens Minecraft's App info page in Settings (where Force stop lives). */
    fun openAppInfo(ctx: Context) {
        val pkg = minecraftPackage(ctx) ?: return
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun relaunch(ctx: Context) {
        val pkg = minecraftPackage(ctx) ?: return
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    fun label(ctx: Context): String {
        val pkg = minecraftPackage(ctx) ?: return "Minecraft"
        return runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault("Minecraft")
    }
}

/**
 * Hands the downloaded files to Minecraft one at a time, works out when Minecraft has finished reading each (inotify on
 * our own cache file: OPEN / ACCESS / CLOSE events caused by Minecraft reading it through our FileProvider), waits a
 * short settle time, then brings ModHub to the front (needs "display over other apps") so Minecraft is in the
 * background, kills its process and relaunches it. Without that permission it posts a notification to tap instead.
 * Runs as a foreground service: Android freezes a background app that has nothing marking it as active.
 */
class ImportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("work", "ModHub working", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "work").setContentTitle("ModHub is importing")
            .setContentText("Handing the addon to Minecraft…").setSmallIcon(android.R.drawable.stat_sys_download).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(77, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(77, n)

        val paths = intent?.getStringArrayExtra("paths") ?: emptyArray()
        val isWorld = intent?.getBooleanExtra("isWorld", false) ?: false
        scope.launch {
            val res = try { run(paths.map { File(it) }, isWorld) } catch (e: Exception) { "error: ${e.message}" }
            ImportBus.result.value = res
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun say(t: String) {
        ImportBus.status.value = t
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(77, Notification.Builder(this, "work").setContentTitle("ModHub is importing").setContentText(t)
                .setSmallIcon(android.R.drawable.stat_sys_download).setOngoing(true).setOnlyAlertOnce(true).build())
        }
    }

    private suspend fun run(files: List<File>, isWorld: Boolean): String {
        for ((i, f) in files.withIndex()) {
            say("Opening ${i + 1}/${files.size} in Minecraft…")
            val w = ReadWatcher(f)
            w.start()
            try {
                Installer.openInMinecraft(this, f)
                if (!w.waitStarted(90_000)) return "error: Minecraft never opened the file. Is it installed and signed in?"
                say("Minecraft is importing ${i + 1}/${files.size}…")
                w.waitFinished(settleMs = 4000L + (f.length() / 1_000_000L * 400L).coerceAtMost(12_000L), maxMs = 150_000)
            } finally { w.stop() }
        }
        if (isWorld) { say("Imported - the world is in Minecraft's world list."); return "ok-world" }

        // After Minecraft has finished READING the file it still unpacks and registers the pack. Restarting too early
        // interrupts that, so wait a while longer (or let the user decide when). 0 = fast (old), 1 = safe (default), 2 = ask.
        val mode = getSharedPreferences("modhub", Context.MODE_PRIVATE).getInt("restart_mode", 1)
        if (mode == 2) {
            postTapNotification()
            say("Imported. When Minecraft says it's done, tap the notification to restart it.")
            return "ok-manual"
        }
        if (mode == 1) {
            val mb = files.sumOf { it.length() } / 1_000_000L
            val waitS = (15L + mb).coerceAtMost(45L)
            for (left in waitS downTo 1) { say("Letting Minecraft finish unpacking… ${left}s"); delay(1000) }
        }
        say("Restarting Minecraft so the new pack shows up…")
        val helper = RestartAccessibilityService.instance
        if (helper == null || !Settings.canDrawOverlays(this)) {
            postTapNotification()
            say("Imported. Tap the notification, then Force stop Minecraft, so the pack shows up.")
            return "ok-manual"
        }
        val overlay = showOverlay()   // a visible overlay window lets us open Settings from the background
        try {
            RestartAccessibilityService.arm(MinecraftRestarter.label(this))
            MinecraftRestarter.openAppInfo(this)
            val end = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < end && !RestartAccessibilityService.isFinished) {
                RestartAccessibilityService.instance?.tick()
                delay(350)
            }
            val ok = RestartAccessibilityService.isFinished
            RestartAccessibilityService.disarm()
            if (ok) delay(1200)   // let Android finish stopping the app
            MinecraftRestarter.relaunch(this)
            if (!ok) {
                postTapNotification()
                say("Imported, but ModHub couldn't press Force stop. Force stop Minecraft yourself so the pack shows up.")
                return "ok-manual"
            }
        } finally { hideOverlay(overlay) }
        say("Imported and Minecraft restarted - ready for you to activate it.")
        return "ok"
    }

    private fun postTapNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("result", "ModHub results", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).putExtra("restart_mc", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(78, Notification.Builder(this, "result").setContentTitle("Imported - tap to finish")
            .setContentText("Opens Minecraft's App info: tap Force stop, then OK, then open Minecraft.").setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pi).setAutoCancel(true).build())
    }

    private suspend fun showOverlay(): View? = withContext(Dispatchers.Main) {
        runCatching {
            val v = View(this@ImportService)
            val lp = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT)
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(v, lp)
            v
        }.getOrNull()
    }

    private suspend fun hideOverlay(v: View?) = withContext(Dispatchers.Main) {
        if (v != null) runCatching { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) }
    }

    override fun onDestroy() { super.onDestroy() }
}

/** Watches one cache file for Minecraft reading it (inotify), so we know when the import has taken the data. */
class ReadWatcher(private val file: File) {
    @Volatile private var opened = 0
    @Volatile private var closed = 0
    @Volatile private var last = 0L
    @Suppress("DEPRECATION")
    private val obs = object : FileObserver(file.path, OPEN or ACCESS or CLOSE_NOWRITE) {
        override fun onEvent(event: Int, path: String?) {
            last = System.currentTimeMillis()
            when (event and ALL_EVENTS) { OPEN -> opened++; CLOSE_NOWRITE -> closed++ }
        }
    }
    fun start() { obs.startWatching() }
    fun stop() { obs.stopWatching() }

    suspend fun waitStarted(maxMs: Long): Boolean {
        val end = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < end) { if (opened > 0) return true; delay(200) }
        return false
    }

    /** Finished = every open was closed (or reads stopped for 20 s) and nothing happened for [settleMs]. */
    suspend fun waitFinished(settleMs: Long, maxMs: Long) {
        val end = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < end) {
            val quiet = System.currentTimeMillis() - last
            if (quiet >= settleMs && (closed >= opened || quiet >= 20_000)) return
            delay(300)
        }
    }
}
