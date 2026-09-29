package com.agentx.app.termux

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Process-wide handle to the one [TermuxRuntime].
 *
 * A foreground service cannot be handed an object through an `Intent`, and the runtime is
 * created by the Activity's composition root, so the two meet here. It holds exactly one
 * reference for the process lifetime, which is what the runtime is anyway.
 */
object TermuxRuntimeHolder {

    @Volatile
    private var runtime: TermuxRuntime? = null

    fun install(runtime: TermuxRuntime) {
        this.runtime = runtime
    }

    fun current(): TermuxRuntime? = runtime

    fun clear() {
        runtime = null
    }
}

/**
 * Keeps the app process alive while shell sessions are running.
 *
 * This is the answer to Android's background restrictions for the "leave `npm run dev`
 * running while I switch apps" case: a plain background process can be trimmed or killed at any
 * time (Android 12+ also kills non-foreground child processes), while a foreground service with
 * a visible notification may keep running. It is started only when a session exists and stopped
 * as soon as the last one exits, so the notification never becomes noise.
 *
 * The service deliberately owns no session itself: sessions belong to [TermuxRuntime], which
 * outlives both the Activity and this service, so restarting either cannot duplicate a process.
 */
class TermuxSessionService : Service() {

    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runtime = TermuxRuntimeHolder.current()
        if (runtime == null || runtime.sessions.sessions().none { it.isRunning }) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            startForeground(NOTIFICATION_ID, buildNotification(runtime))
            started = true
        } else {
            notify(buildNotification(runtime))
        }
        // Not sticky: sessions live in this process, so a restart would have nothing to keep
        // alive and would only leave an orphan notification behind.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        started = false
        super.onDestroy()
    }

    private fun buildNotification(runtime: TermuxRuntime): Notification {
        val running = runtime.sessions.sessions().count { it.isRunning }
        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            PendingIntent.getActivity(
                this,
                0,
                launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Terminal running")
            .setContentText(
                if (running == 1) "1 shell session is active" else "$running shell sessions are active",
            )
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        contentIntent?.let(builder::setContentIntent)
        return builder.build()
    }

    private fun notify(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Terminal sessions",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while a terminal session keeps running in the background."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID: String = "agentx.termux.sessions"
        const val NOTIFICATION_ID: Int = 0x7E_01

        /** Starts (or re-notifies) the keep-alive service. No-op when nothing is running. */
        fun ensureRunning(context: Context) {
            val intent = Intent(context, TermuxSessionService::class.java)
            runCatching { context.startForegroundService(intent) }
        }

        /** Stops the service once no session is left. */
        fun stopIfIdle(context: Context) {
            val runtime = TermuxRuntimeHolder.current()
            if (runtime != null && runtime.sessions.sessions().any { it.isRunning }) return
            runCatching { context.stopService(Intent(context, TermuxSessionService::class.java)) }
        }
    }
}
