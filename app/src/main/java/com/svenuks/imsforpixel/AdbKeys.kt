package com.svenuks.imsforpixel

import android.content.Context
import android.util.Log
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import okio.Path.Companion.toPath
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-time setup of the ADB client key store. Loading/generating the RSA key takes seconds,
 * so it runs on a background thread; every Kadb call must [await] it first.
 */
object AdbKeys {
    private const val TAG = "IMSForSven"
    private val started = AtomicBoolean(false)
    private val ready = CountDownLatch(1)

    fun initAsync(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val filesDir = context.applicationContext.filesDir
        Thread({
            try {
                HiddenApiBypass.addHiddenApiExemptions("L")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply HiddenApiBypass exemptions", e)
            }
            try {
                val store = OkioFilePrivateKeyStore(java.io.File(filesDir, "kadb_private_key.pem").absolutePath.toPath())
                KadbCert.configure(store = store, policy = KadbCertPolicy(), additionalPrivateKeysPem = emptyList())
                KadbCert.ensureReady()
                Log.d(TAG, "KadbCert configured successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to configure KadbCert", e)
            } finally {
                ready.countDown()
            }
        }, "adb-key-init").start()
    }

    /** Blocks the calling (background) thread until the key store is ready. */
    fun await() {
        ready.await(30, TimeUnit.SECONDS)
    }
}
