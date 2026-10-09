package com.svenuks.imsforpixel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Runtime permissions the app asks for, and which of them this OS version needs. */
object Permissions {
    /**
     * Android 17+ (when targeting API 37) gates LAN traffic, including NsdManager mDNS discovery
     * of the Wireless Debugging ports, behind ACCESS_LOCAL_NETWORK.
     */
    val localNetworkRequired: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN

    fun isGranted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun hasLocalNetwork(context: Context): Boolean =
        !localNetworkRequired || isGranted(context, Manifest.permission.ACCESS_LOCAL_NETWORK)

    /** Everything the app wants on first launch that isn't granted yet. */
    fun missingAtStartup(context: Context): List<String> = buildList {
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        if (localNetworkRequired) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }.filterNot { isGranted(context, it) }
}
