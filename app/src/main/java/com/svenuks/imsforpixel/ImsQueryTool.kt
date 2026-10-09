package com.svenuks.imsforpixel

import android.os.IBinder

/**
 * app_process entry point run as the shell user. Prints one line per SIM slot:
 *   RESULT:<slot>:<imsRegistered>   — slot has an active subscription
 *   NOSIM:<slot>                     — slot is empty / inactive
 */
object ImsQueryTool {
    private const val MAX_SLOTS = 2

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val isubService = getService("isub", "com.android.internal.telephony.ISub")
            for (slot in 0 until MAX_SLOTS) {
                val subId = getSubIdForSlot(isubService, slot)
                if (subId >= 0) {
                    println("RESULT:$slot:${checkImsRegistered(subId)}")
                } else {
                    println("NOSIM:$slot")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getService(name: String, aidlClass: String): Any? {
        val serviceManagerClass = Class.forName("android.os.ServiceManager")
        val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
        val binder = getServiceMethod.invoke(null, name) as IBinder
        val stubClass = Class.forName("$aidlClass\$Stub")
        return stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
    }

    /**
     * ISub's slot→subId lookup changed shape across releases: Android 14+ has `int getSubId(int)`,
     * older releases have `int[] getSubId(int)` and/or `int[] getSubIds(int)`. Accept all of them.
     */
    private fun getSubIdForSlot(isubService: Any?, slot: Int): Int {
        val iSubClass = Class.forName("com.android.internal.telephony.ISub")
        for (name in listOf("getSubId", "getSubIds")) {
            val method = try {
                iSubClass.getMethod(name, Int::class.javaPrimitiveType)
            } catch (e: NoSuchMethodException) {
                continue
            }
            val subId = when (val result = method.invoke(isubService, slot)) {
                is Int -> result
                is IntArray -> result.firstOrNull() ?: -1
                else -> -1
            }
            return subId
        }
        return -1
    }

    private fun checkImsRegistered(subId: Int): Boolean {
        return try {
            val telephonyService = getService("phone", "com.android.internal.telephony.ITelephony")
            val iTelephonyClass = Class.forName("com.android.internal.telephony.ITelephony")
            val method = iTelephonyClass.getMethod("isImsRegistered", Int::class.javaPrimitiveType)
            method.invoke(telephonyService, subId) as Boolean
        } catch (e: Exception) {
            false
        }
    }
}
