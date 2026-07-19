package com.playfy

import android.content.Context
import android.content.ContextWrapper
import com.lagradost.api.Log
import com.playfy.tv.helpers.NintyMin
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object PlayFyNativeBridge {
    private const val TAG = "PlayFy"
    private var loaded = false

    private const val SO_BASE_URL = "https://raw.githubusercontent.com/KSHITIJ8473/raghav/raw-assets"

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    fun loadLibraries(context: Context): Boolean {
        if (loaded) return true

        try {

            val abis = android.os.Build.SUPPORTED_ABIS
            val arch = when {
                abis.any { it.contains("arm64") } -> "arm64-v8a"
                abis.any { it.contains("armeabi") } -> "armeabi-v7a"
                abis.any { it.contains("x86_64") } -> "x86_64"
                abis.any { it.contains("x86") } -> "x86"
                else -> "arm64-v8a"
            }

            val libDir = File(context.filesDir, "playfy_libs")
            if (!libDir.exists()) libDir.mkdirs()

            val nativeLib = File(libDir, "libnative-lib.so")
            val umairiLib = File(libDir, "libumairi-lib.so")

            // Download libnative-lib.so if not present
            if (!nativeLib.exists() || nativeLib.length() < 100000) {
                val url = "$SO_BASE_URL/$arch/libnative-lib.so"
                if (!downloadFile(url, nativeLib)) {
                    Log.e(TAG, "NATIVE: Failed to download libnative-lib.so")
                    return false
                }
            } else {
            }

            // Download libumairi-lib.so if not present
            if (!umairiLib.exists() || umairiLib.length() < 100000) {
                val url = "$SO_BASE_URL/$arch/libumairi-lib.so"
                if (!downloadFile(url, umairiLib)) {
                    Log.e(TAG, "NATIVE: Failed to download libumairi-lib.so")
                    return false
                }
            } else {
            }

            // Make read-only (Android requires .so files to be non-writable)
            nativeLib.setReadOnly()
            umairiLib.setReadOnly()

            // Load the .so files — catch the "already loaded" error
            try {
                System.load(nativeLib.absolutePath)
            } catch (e: UnsatisfiedLinkError) {
                if (e.message?.contains("already opened") == true || e.message?.contains("already loaded") == true) {
                } else {
                    Log.e(TAG, "NATIVE:  Failed to load libnative-lib.so: ${e.message}")
                    return false
                }
            }

            try {
                System.load(umairiLib.absolutePath)
            } catch (e: UnsatisfiedLinkError) {
                if (e.message?.contains("already opened") == true || e.message?.contains("already loaded") == true) {
                    Log.d(TAG, "NATIVE: libumairi-lib.so already loaded (OK)")
                } else {
                    Log.e(TAG, "NATIVE:  Failed to load libumairi-lib.so: ${e.message}")
                    return false
                }
            }

            loaded = true
            Log.d(TAG, "NATIVE:  All native libraries loaded successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "NATIVE:  Failed to load: ${e.message}")
            return false
        }
    }

    private fun downloadFile(url: String, outputFile: File): Boolean {
        return try {
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return false
            }
            val bytes = response.body?.bytes()
            response.close()
            if (bytes == null || bytes.isEmpty()) return false
            Log.d(TAG, "NATIVE: Got ${bytes.size} bytes from server")
            outputFile.writeBytes(bytes)
            Log.d(TAG, "NATIVE: Written ${outputFile.length()} bytes to ${outputFile.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "NATIVE: Download failed: ${e.message}")
            false
        }
    }

    fun decrypt(encryptedData: String, lora: String, context: Context): String? {
        if (!loaded && !loadLibraries(context)) {
            Log.e(TAG, "NATIVE: Cannot decrypt — libraries not loaded")
            return null
        }
        try {
            val fakeContext = FakeSignatureContext(context)
            var result = NintyMin.ohMyYes(encryptedData, lora, fakeContext)
            if (result == null) {
                Log.d(TAG, "NATIVE: ohMyYes returned null, trying ohMyNo...")
                result = NintyMin.ohMyNo(encryptedData, fakeContext)
            }
            if (result != null) {
                Log.d(TAG, "NATIVE:  Decrypted ${result.length} chars")
            } else {
                Log.e(TAG, "NATIVE:  Both decryption methods returned null")
            }
            return result
        } catch (e: Exception) {
            Log.e(TAG, "NATIVE: Decrypt error: ${e.message}")
            return null
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "NATIVE: Link error: ${e.message}")
            return null
        }
    }
}

class FakeSignatureContext(private val realContext: Context) : ContextWrapper(realContext) {
    override fun getPackageManager(): android.content.pm.PackageManager {
        val realPm = realContext.packageManager
        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            realPm.javaClass.classLoader,
            arrayOf(android.content.pm.PackageManager::class.java)
        ) { _, method, args ->
            if (method.name == "getPackageInfo" && args != null && args.size >= 2) {
                val pkg = args[0] as? String ?: ""
                val flags = args[1] as Int
                Log.d("PlayFy", "NATIVE: Intercepted getPackageInfo($pkg, $flags)")
                val info = realPm.getPackageInfo(pkg, flags and 64.inv())
                if (flags and 64 != 0) {
                    val cert = android.util.Base64.decode(
                        "MIIDYjCCAkoCAQEwDQYJKoZIhvcNAQELBQAwdjEXMBUGA1UEAwwOSm9obiBEZXZlbG9wZXIxFDASBgNVBAsMC01vYmlsZSBBcHBzMRcwFQYDVQQKDA5EZXZDb21wYW55IEluYzEPMA0GA1UEBwwGQXVzdGluMQ4wDAYDVQQIDAVUZXhhczELMAkGA1UEBhMCVVMwIBcNMjUwNTI0MDczMTI5WhgPMjA1MDA1MTgwNzMxMjla",
                        android.util.Base64.DEFAULT
                    )
                    info.signatures = arrayOf(android.content.pm.Signature(cert))
                    Log.d("PlayFy", "NATIVE: Injected PlayFy signature")
                }
                info
            } else {
                method.invoke(realPm, *(args ?: arrayOfNulls(0)))
            }
        }
        return proxy as android.content.pm.PackageManager
    }
}
