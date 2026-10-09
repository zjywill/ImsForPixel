package com.svenuks.imsforpixel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Detects when previously applied overrides have been wiped by the system and asks the user to
 * re-activate. The system deletes persisted overrides when the build fingerprint changes (every
 * OTA), so a boot after an update is the main trigger; carrier config changes are checked too.
 */
class ConfigWatcherReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ConfigWatcher"
        private const val CHANNEL_ID = "config_watch_channel"
        private const val NOTIFICATION_ID = 204
    }

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = CarrierOverrides.prefs(context)
        val activatedSlots = (0..1).filter { CarrierOverrides.isActivated(prefs, it) }
        if (activatedSlots.isEmpty()) return

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                val fingerprint = prefs.getString("activated_fingerprint", null)
                if (fingerprint != null && fingerprint != Build.FINGERPRINT) {
                    Log.d(TAG, "Build fingerprint changed since activation; overrides were cleared by the system")
                    notifyLost(context, "系统已更新，运营商覆盖配置已被系统清除。请打开应用重新一键激活。")
                }
            }
            CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED -> {
                val slot = intent.getIntExtra(CarrierConfigManager.EXTRA_SLOT_INDEX, SubscriptionManager.INVALID_SIM_SLOT_INDEX)
                if (slot !in activatedSlots) return
                if (CarrierOverrides.queryState(context, slot) == CarrierOverrides.State.LOST) {
                    Log.d(TAG, "Overrides no longer present for slot $slot")
                    notifyLost(context, "SIM ${slot + 1} 的运营商覆盖配置已失效。请打开应用重新一键激活。")
                }
            }
        }
    }

    private fun notifyLost(context: Context, text: String) {
        val prefs = CarrierOverrides.prefs(context)
        // Notify once per loss; re-armed by the next successful activation.
        if (prefs.getBoolean("lost_notified", false)) return
        prefs.edit().putBoolean("lost_notified", true).apply()

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "配置失效提醒", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("⚠️ VoLTE 配置已失效")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission not granted", e)
        }
    }
}
