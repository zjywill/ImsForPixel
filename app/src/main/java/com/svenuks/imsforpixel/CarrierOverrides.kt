package com.svenuks.imsforpixel

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.PersistableBundle
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager

/**
 * Shared definition of the carrier config overrides, plus detection of whether they are
 * actually in effect by reading the live carrier config from the system.
 */
object CarrierOverrides {
    const val PREFS = "volte_settings"

    /** Effective state of the overrides for one SIM slot. */
    enum class State {
        /** Overrides were applied and the live carrier config still contains them. */
        APPLIED,
        /** Overrides were applied, but the live carrier config no longer matches (e.g. after an OTA). */
        LOST,
        /** No overrides applied by this app (or they were restored). */
        DEFAULT,
        /** No active SIM in this slot. */
        NO_SIM,
        /** Cannot read carrier config (READ_PHONE_STATE not granted). */
        UNKNOWN
    }

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun buildBundle(prefs: SharedPreferences, slot: Int): PersistableBundle {
        val volte = prefs.getBoolean("volte_slot_$slot", true)
        val vonr = prefs.getBoolean("vonr_slot_$slot", true)
        val vowifi = prefs.getBoolean("vowifi_slot_$slot", true)
        val crossSim = prefs.getBoolean("cross_sim_slot_$slot", false)
        val wfcRoaming = prefs.getBoolean("wfc_roaming_slot_$slot", true)
        val ssUt = prefs.getBoolean("ss_ut_slot_$slot", true)
        val showIms = prefs.getBoolean("show_ims_slot_$slot", true)
        val allowApn = prefs.getBoolean("allow_apn_slot_$slot", false)

        val bundle = PersistableBundle()
        // VoLTE enabling & provisioning overrides
        bundle.putBoolean("carrier_volte_available_bool", volte)
        bundle.putBoolean("enhanced_4g_lte_on_by_default_bool", volte)
        bundle.putBoolean("hide_enhanced_4g_lte_bool", !volte)
        bundle.putBoolean("editable_enhanced_4g_lte_bool", volte)
        bundle.putBoolean("carrier_volte_provisioned_bool", volte)
        bundle.putBoolean("carrier_volte_provisioning_required_bool", false)

        // VoNR (5G Calling) overrides
        bundle.putBoolean("vonr_enabled_bool", vonr)
        bundle.putBoolean("vonr_setting_visibility_bool", vonr)

        // VoWiFi (Wi-Fi Calling) overrides
        bundle.putBoolean("carrier_wfc_ims_available_bool", vowifi)
        bundle.putBoolean("carrier_default_wfc_ims_enabled_bool", vowifi)
        bundle.putBoolean("carrier_wfc_ims_provisioned_bool", vowifi)
        bundle.putBoolean("editable_wfc_mode_bool", vowifi)
        bundle.putBoolean("editable_wfc_roaming_mode_bool", vowifi)
        bundle.putBoolean("carrier_default_wfc_ims_roaming_enabled_bool", wfcRoaming)

        // Other settings
        bundle.putBoolean("carrier_cross_sim_ims_available_bool", crossSim)
        bundle.putBoolean("enable_cross_sim_calling_on_opportunistic_data_bool", crossSim)
        bundle.putBoolean("carrier_supports_ss_over_ut_bool", ssUt)
        bundle.putBoolean("show_ims_registration_status_bool", showIms)
        bundle.putBoolean("allow_adding_apns_bool", allowApn)
        return bundle
    }

    /** Serializes a bundle of booleans as "key=1;key=0" so the applied snapshot can be stored in prefs. */
    fun signature(bundle: PersistableBundle): String =
        bundle.keySet().sorted().joinToString(";") { "$it=${if (bundle.getBoolean(it)) 1 else 0}" }

    private fun parseSignature(sig: String): Map<String, Boolean> =
        sig.split(";").mapNotNull {
            val parts = it.split("=")
            if (parts.size == 2) parts[0] to (parts[1] == "1") else null
        }.toMap()

    /** Records (from the broker) that overrides were written for [slot]. */
    fun markApplied(prefs: SharedPreferences, slot: Int, bundle: PersistableBundle) {
        prefs.edit()
            .putBoolean("activated_slot_$slot", true)
            .putString("applied_sig_slot_$slot", signature(bundle))
            .putString("activated_fingerprint", Build.FINGERPRINT)
            .putBoolean("lost_notified", false)
            .commit()
    }

    /** Restores the activation keys for [slot] from a prefs snapshot taken before [markApplied]. */
    fun restoreActivation(prefs: SharedPreferences, slot: Int, snapshot: Map<String, *>) {
        val editor = prefs.edit()
        for (key in listOf("activated_slot_$slot", "applied_sig_slot_$slot", "activated_fingerprint", "lost_notified")) {
            when (val value = snapshot[key]) {
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
                else -> editor.remove(key)
            }
        }
        editor.commit()
    }

    fun markCleared(prefs: SharedPreferences, slot: Int) {
        prefs.edit()
            .putBoolean("activated_slot_$slot", false)
            .remove("applied_sig_slot_$slot")
            .commit()
    }

    fun isActivated(prefs: SharedPreferences, slot: Int): Boolean =
        prefs.getBoolean("activated_slot_$slot", false)

    fun hasPhonePermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    /**
     * Reads the live carrier config for [slot] and compares it to the snapshot written at activation.
     * Never throws; anything indeterminate is reported as [State.UNKNOWN].
     */
    fun queryState(context: Context, slot: Int): State {
        val prefs = prefs(context)
        if (!hasPhonePermission(context)) {
            return if (isActivated(prefs, slot)) State.UNKNOWN else State.DEFAULT
        }
        return try {
            val subManager = context.getSystemService(SubscriptionManager::class.java) ?: return State.UNKNOWN
            val sub = subManager.getActiveSubscriptionInfoForSimSlotIndex(slot) ?: return State.NO_SIM
            if (!isActivated(prefs, slot)) return State.DEFAULT

            val expected = parseSignature(prefs.getString("applied_sig_slot_$slot", null) ?: return State.UNKNOWN)
            val ccm = context.getSystemService(CarrierConfigManager::class.java) ?: return State.UNKNOWN
            val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ccm.getConfigForSubId(
                    sub.subscriptionId,
                    CarrierConfigManager.KEY_CARRIER_CONFIG_APPLIED_BOOL,
                    *expected.keys.toTypedArray()
                )
            } else {
                @Suppress("DEPRECATION")
                ccm.getConfigForSubId(sub.subscriptionId)
            } ?: return State.UNKNOWN
            // Config not loaded for the real carrier yet (e.g. SIM still initializing) — don't guess.
            if (!CarrierConfigManager.isConfigForIdentifiedCarrier(config)) return State.UNKNOWN

            val matches = expected.all { (key, value) ->
                config.containsKey(key) && config.getBoolean(key) == value
            }
            if (matches) State.APPLIED else State.LOST
        } catch (e: Exception) {
            State.UNKNOWN
        }
    }
}
