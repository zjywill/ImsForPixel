package com.svenuks.imsforpixel
 
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.PersistableBundle
import android.system.Os
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method
import android.os.Build
import android.app.UiAutomation
import android.content.Intent
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager

 
class BrokerInstrumentation : Instrumentation() {
    companion object {
        private const val TAG = "VoLTEBrokerInst"
    }
 
    private fun findMethod(obj: Any, name: String): Method? {
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            try {
                val method = clazz.declaredMethods.firstOrNull { it.name == name }
                if (method != null) {
                    method.isAccessible = true
                    return method
                }
            } catch (e: Exception) {
                // Ignore
            }
            clazz = clazz.superclass
        }
        
        // Search interfaces
        for (iface in obj.javaClass.interfaces) {
            try {
                val method = iface.declaredMethods.firstOrNull { it.name == name }
                if (method != null) {
                    method.isAccessible = true
                    return method
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        return null
    }
 
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        Log.d(TAG, "BrokerInstrumentation starting...")
        
        val queryOnly = arguments?.getString("query_only") == "true" || arguments?.getBoolean("query_only") == true
        Log.d(TAG, "queryOnly mode: $queryOnly")
        
        val clearArg = arguments?.getString("clear") == "true" || arguments?.getBoolean("clear") == true
        Log.d(TAG, "clearArg: $clearArg")

        Thread {
            try {
                HiddenApiBypass.addHiddenApiExemptions("L")
                Log.d(TAG, "Successfully applied HiddenApiBypass exemptions in instrumentation")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply HiddenApiBypass exemptions in instrumentation", e)
            }
            
            try {
                var uiAutomation: UiAutomation? = null
                var retries = 5
                while (retries > 0) {
                    try {
                        // Try to get standard UiAutomation first to avoid flags mismatch and disconnect() call on Android 15/16
                        uiAutomation = getUiAutomation()
                        if (uiAutomation != null) {
                            Log.d(TAG, "Successfully connected UiAutomation")
                            break
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to connect UiAutomation, retries left: ${retries - 1}", e)
                        retries--
                        if (retries > 0) {
                            try { Thread.sleep(500) } catch (ignored: Exception) {}
                        } else {
                            throw e
                        }
                    }
                }

                if (uiAutomation != null) {
                    uiAutomation.adoptShellPermissionIdentity()
                    Log.d(TAG, "Successfully adopted shell permission identity via UiAutomation")
                } else {
                    Log.e(TAG, "UiAutomation is null, cannot adopt shell permission identity")
                }
                
                try {
                    if (queryOnly) {
                        queryStatusOnly()
                    } else {
                        val results = patchAllSimsAndPoll(arguments)
                        showImsStatusNotification(!clearArg, results)
                    }
                } finally {
                    if (uiAutomation != null) {
                        try {
                            uiAutomation.dropShellPermissionIdentity()
                            Log.d(TAG, "Released shell permission identity via UiAutomation")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to drop shell permission identity", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to run instrumentation patch", e)
            } finally {
                // Signal the UI (if it is still alive) that the run has finished.
                try {
                    java.io.File(context.filesDir, "broker_done.txt").writeText(System.currentTimeMillis().toString())
                } catch (ignored: Exception) {}
                finish(0, Bundle())
            }
        }.start()
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        Log.d(TAG, "Instrumentation finish() called")
        try {
            super.finish(resultCode, results)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during Instrumentation.finish() ignored safely", e)
        }
    }

    private fun queryStatusOnly() {
        val subManager = context.getSystemService(SubscriptionManager::class.java) ?: return
        val activeSubscriptions = subManager.activeSubscriptionInfoList ?: emptyList()
        Log.d(TAG, "QueryStatusOnly: Found ${activeSubscriptions.size} active SIMs")

        for (subInfo in activeSubscriptions) {
            val subId = subInfo.subscriptionId
            val slotIndex = subInfo.simSlotIndex
            val isImsRegistered = checkImsRegistered(subId)
            Log.d(TAG, "QueryStatusOnly: SIM slot $slotIndex IMS Registered: $isImsRegistered")
            
            val sharedPrefs = context.getSharedPreferences("volte_settings", Context.MODE_PRIVATE)
            sharedPrefs.edit().putBoolean("ims_registered_slot_$slotIndex", isImsRegistered).commit()
            
            try {
                val statusFile = java.io.File(context.filesDir, "ims_status_$slotIndex.txt")
                statusFile.writeText(isImsRegistered.toString())
                Log.d(TAG, "QueryStatusOnly: Saved status file for slot $slotIndex")
            } catch (e: Exception) {
                Log.e(TAG, "QueryStatusOnly: Failed to save status file", e)
            }
        }
    }

    private fun checkImsRegistered(subId: Int): Boolean {
        return try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, Context.TELEPHONY_SERVICE) as android.os.IBinder
            val stubClass = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
            val asInterfaceMethod = stubClass.getMethod("asInterface", android.os.IBinder::class.java)
            val telephonyService = asInterfaceMethod.invoke(null, binder)
            
            val iTelephonyClass = Class.forName("com.android.internal.telephony.ITelephony")
            val method = iTelephonyClass.getMethod("isImsRegistered", Int::class.javaPrimitiveType)
            method.isAccessible = true
            method.invoke(telephonyService, subId) as Boolean
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check IMS registration for subId=$subId reflectively", e)
            false
        }
    }

    /** Per-slot outcome of one broker run. */
    private data class SlotResult(val slot: Int, val configWritten: Boolean, var imsRegistered: Boolean)

    /**
     * Invokes CarrierConfigManager.overrideConfig, preferring the 3-arg (persistent) overload.
     * The 2-arg overload is non-persistent and would neither survive a reboot nor delete a
     * previously persisted override on restore, so it is only used where the 3-arg one is absent.
     */
    private fun invokeOverrideConfig(ccm: CarrierConfigManager, subId: Int, bundle: PersistableBundle?) {
        val cls = CarrierConfigManager::class.java
        val persistent = try {
            cls.getDeclaredMethod(
                "overrideConfig", Int::class.javaPrimitiveType, PersistableBundle::class.java, Boolean::class.javaPrimitiveType
            )
        } catch (e: NoSuchMethodException) {
            null
        }
        if (persistent != null) {
            persistent.isAccessible = true
            persistent.invoke(ccm, subId, bundle, true)
            Log.d(TAG, "overrideConfig(subId=$subId, persistent=true)")
            return
        }
        val legacy = cls.getDeclaredMethod("overrideConfig", Int::class.javaPrimitiveType, PersistableBundle::class.java)
        legacy.isAccessible = true
        legacy.invoke(ccm, subId, bundle)
        Log.w(TAG, "Persistent overrideConfig unavailable; applied non-persistent override for subId=$subId")
    }

    private fun patchAllSimsAndPoll(arguments: Bundle?): List<SlotResult> {
        val sharedPrefs = CarrierOverrides.prefs(context)
        val subManager = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        val carrierConfigManager = context.getSystemService(CarrierConfigManager::class.java) ?: return emptyList()
        val telephonyManager = context.getSystemService(TelephonyManager::class.java) ?: return emptyList()

        // Under shell permission identity, activeSubscriptionInfoList is accessible directly
        val activeSubscriptions = subManager.activeSubscriptionInfoList ?: emptyList()
        Log.d(TAG, "Found ${activeSubscriptions.size} active SIMs")

        val hasClearArg = arguments?.containsKey("clear") == true
        val clearArg = arguments?.getString("clear") == "true" || arguments?.getBoolean("clear") == true
        val results = mutableListOf<SlotResult>()

        // Phase 1: Apply overrides or Clear overrides, and trigger IMS reset
        for (subInfo in activeSubscriptions) {
            val subId = subInfo.subscriptionId
            val slotIndex = subInfo.simSlotIndex
            Log.d(TAG, "Processing SIM slot $slotIndex (SubID $subId)")

            val clear = if (hasClearArg) clearArg else sharedPrefs.getBoolean("clear_slot_$slotIndex", false)

            var written = false
            if (clear) {
                Log.d(TAG, "Clearing config for slot $slotIndex")
                // Mark first so ConfigWatcherReceiver doesn't treat the resulting config change as a loss.
                CarrierOverrides.markCleared(sharedPrefs, slotIndex)
                try {
                    invokeOverrideConfig(carrierConfigManager, subId, null)
                    written = true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to clear carrier config reflectively", e)
                }
            } else {
                val bundle = CarrierOverrides.buildBundle(sharedPrefs, slotIndex)
                Log.d(TAG, "Applying config for slot $slotIndex: $bundle")
                // Record the snapshot before the call: the CARRIER_CONFIG_CHANGED broadcast it triggers
                // may reach ConfigWatcherReceiver before this thread continues. Roll back on failure.
                val previous = sharedPrefs.all
                CarrierOverrides.markApplied(sharedPrefs, slotIndex, bundle)
                try {
                    invokeOverrideConfig(carrierConfigManager, subId, bundle)
                    written = true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to apply config reflectively", e)
                    CarrierOverrides.restoreActivation(sharedPrefs, slotIndex, previous)
                }
            }
            results += SlotResult(slotIndex, written, false)

            // Reset IMS registration to force reload
            try {
                val resetImsMethod = findMethod(telephonyManager, "resetIms")
                resetImsMethod?.invoke(telephonyManager, slotIndex)
                Log.d(TAG, "IMS reset sent for slot $slotIndex")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reset IMS reflectively", e)
            }
        }

        // Phase 2: Poll and update status for up to 30 seconds
        Log.d(TAG, "Entering status polling loop...")
        val totalPolls = 30
        for (secondsElapsed in 0 until totalPolls) {
            var allRegistered = true
            for (subInfo in activeSubscriptions) {
                val slotIndex = subInfo.simSlotIndex
                val isImsRegistered = checkImsRegistered(subInfo.subscriptionId)
                Log.d(TAG, "Poll $secondsElapsed: SIM slot $slotIndex IMS Registered: $isImsRegistered")
                results.firstOrNull { it.slot == slotIndex }?.imsRegistered = isImsRegistered

                sharedPrefs.edit().putBoolean("ims_registered_slot_$slotIndex", isImsRegistered).commit()
                try {
                    java.io.File(context.filesDir, "ims_status_$slotIndex.txt").writeText(isImsRegistered.toString())
                } catch (e: Exception) {}

                if (!isImsRegistered) allRegistered = false
            }

            if (hasClearArg && clearArg) {
                // When clearing/restoring, wait until it's unregistered
                if (!allRegistered) {
                    Log.d(TAG, "IMS successfully unregistered. Stopping poll.")
                    break
                }
            } else {
                // When applying/activating, wait until it's registered.
                // To avoid stale true values right after reset, only exit early after at least 5 seconds
                if (allRegistered && secondsElapsed >= 5) {
                    Log.d(TAG, "IMS successfully registered. Stopping poll.")
                    break
                }
            }

            try {
                Thread.sleep(1000)
            } catch (ignored: Exception) {}
        }
        return results
    }

    private fun showImsStatusNotification(isActivate: Boolean, results: List<SlotResult>) {
        val channelId = "ims_status_channel"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "IMS 激活状态", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(ch)
        }

        val sorted = results.sortedBy { it.slot }
        val title: String
        val body: String
        when {
            sorted.isEmpty() -> {
                title = "❌ 未检测到可用 SIM 卡"
                body = "没有找到处于活动状态的 SIM 卡，未做任何修改"
            }
            sorted.any { !it.configWritten } -> {
                title = if (isActivate) "❌ 配置写入失败" else "❌ 恢复失败"
                body = sorted.joinToString("  ") {
                    "SIM ${it.slot + 1}: " + if (it.configWritten) "成功" else "失败"
                }
            }
            isActivate -> {
                title = if (sorted.all { it.imsRegistered }) "✅ VoLTE 激活成功" else "⚠️ 配置已写入，IMS 未全部注册"
                body = sorted.joinToString("  ") {
                    "SIM ${it.slot + 1}: IMS " + if (it.imsRegistered) "已注册" else "未注册"
                }
            }
            else -> {
                title = "✅ 配置已恢复默认"
                body = "运营商覆盖配置已清除，请测试通话功能是否正常"
            }
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        val notification = builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(203, notification)
    }
}
