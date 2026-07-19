package com.playfy

import android.content.Context
import android.util.Base64
import android.util.Log
import dalvik.system.PathClassLoader
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.io.File

/**
 * PlayFy TV Crypto Utilities
 *
 * Decryption pipeline (reverse-engineered from PlayFy TV decompiled sources):
 *
 *   PRIMARY PATH (com.playfy.tv.helpers.NintyMin.ohMyNo):
 *     The app loads libnative-lib.so + libumairi-lib.so at startup.
 *     ohMyNo(encryptedString, context) → decrypted JSON string.
 *     We replicate this by loading the NintyMin class from PlayFy TV's APK
 *     via PathClassLoader, then calling ohMyNo() via reflection.
 *
 *   FALLBACK PATH (from c6/f0.java, shared code with LivXow TV):
 *     1. Apply reverse substitution cipher: rc.a.b() maps f11359b[i]→f11358a[i]
 *     2. Base64 decode the result
 *     3. AES-128-CBC decrypt using hardcoded key/IV
 *        KEY = {77,56,109,107,75,108,78,76,55,53,75,52,110,108,49,53} = "M8mkKlNL75K4nl15"
 *        IV  = {107,78,55,109,53,75,108,49,112,78,53,110,107,52,120,75} = "kN7m5Kl1pN5nk4xK"
 *
 * Note: The fallback keys are from the PlayFy TV decompiled APK. They may not
 * decrypt PlayFy's current backend if the server uses different keys for ohMyNo.
 * The primary path (native lib) is the reliable solution.
 */
object PlayFyCryptoUtils {

    private const val TAG = "PlayFyCrypto"
    private const val PLAYFY_PKG = "com.playfy.tv"

    // -----------------------------------------------------------------------
    // Substitution cipher (rc.a.b in decompiled code)
    // f11361d maps SUB_TO[i] -> SUB_FROM[i] (reverse direction)
    // -----------------------------------------------------------------------
    private const val SUB_FROM =
        "aAbBcCdDeEfFgGhHiIjJkKlLmMnNoOpPqQrRsStTuUvVwWxXyYzZ"
    private const val SUB_TO =
        "fFgGjJkKaApPbBmMoOzZeEnNcCdDrRqQtTvVuUxXhHiIwWyYlLsS"

    /** f11361d lookup: reverse substitution SUB_TO -> SUB_FROM */
    private val f11361d: CharArray = CharArray(128) { it.toChar() }.also { table ->
        for (i in SUB_TO.indices) {
            table[SUB_TO[i].code] = SUB_FROM[i]
        }
    }

    // -----------------------------------------------------------------------
    // Fallback AES key/IV (from c6/f0.java in decompiled PlayFy TV APK)
    // -----------------------------------------------------------------------
    private val FALLBACK_KEY = byteArrayOf(
        77, 56, 109, 107, 75, 108, 78, 76,
        55, 53, 75, 52, 110, 108, 49, 53
    ) // "M8mkKlNL75K4nl15"

    private val FALLBACK_IV = byteArrayOf(
        107, 78, 55, 109, 53, 75, 108, 49,
        112, 78, 53, 110, 107, 52, 120, 75
    ) // "kN7m5Kl1pN5nk4xK"

    // -----------------------------------------------------------------------
    // Native library loader via PathClassLoader
    // -----------------------------------------------------------------------

    @Volatile private var nintyMinClass: Class<*>? = null
    @Volatile private var loadAttempted  = false

    /**
     * Load com.playfy.tv.helpers.NintyMin from the installed PlayFy TV APK.
     * The PathClassLoader also loads libnative-lib.so and libumairi-lib.so
     * from PlayFy TV's native library directory.
     */
    private fun getNintyMinClass(context: Context): Class<*>? {
        if (loadAttempted) return nintyMinClass
        loadAttempted = true

        return try {
            val pm      = context.packageManager
            val appInfo = pm.getApplicationInfo(PLAYFY_PKG, 0)
            val apkPath = appInfo.sourceDir
            val nativeDir = appInfo.nativeLibraryDir

            Log.d(TAG, "Native lib dir: $nativeDir")

            // Also include split APKs if present
            val splitPaths = appInfo.splitSourceDirs
            val allApks = buildString {
                append(apkPath)
                splitPaths?.forEach { append(File.pathSeparator).append(it) }
            }

            val loader = PathClassLoader(
                allApks,
                nativeDir,
                ClassLoader.getSystemClassLoader()
            )

            val cls = loader.loadClass("com.playfy.tv.helpers.NintyMin")
            nintyMinClass = cls
            Log.d(TAG, "NintyMin loaded successfully")
            cls
        } catch (e: Exception) {
            Log.w(TAG, "Could not load NintyMin: ${e.message}")
            null
        }
    }

    /**
     * Call NintyMin.ohMyNo(encryptedStr, context) via reflection.
     */
    private fun callOhMyNo(cls: Class<*>, encrypted: String, context: Context): String? {
        return try {
            val m = cls.getMethod("ohMyNo", String::class.java, Context::class.java)
            m.invoke(null, encrypted, context) as? String
        } catch (e: Exception) {
            Log.e(TAG, "ohMyNo failed: ${e.message}")
            null
        }
    }

    // -----------------------------------------------------------------------
    // Fallback decryption (substitution cipher + AES-128-CBC)
    // -----------------------------------------------------------------------

    private fun reverseSubstitution(text: String): String =
        buildString {
            for (c in text) {
                append(if (c.code < 128) f11361d[c.code] else c)
            }
        }

    private fun normalizeBase64(s: String): String {
        var v = s
            .replace("-", "+")
            .replace("_", "/")
            .replace("\n", "")
            .replace("\r", "")
            .replace(" ", "")
            .replace("\t", "")
        while (v.length % 4 != 0) v += "="
        return v
    }

    private fun fallbackDecrypt(encrypted: String): String? {
        return try {
            val mapped       = reverseSubstitution(encrypted.replace("\\s".toRegex(), ""))
            val cipherBytes  = Base64.decode(normalizeBase64(mapped), Base64.DEFAULT)
            val cipher       = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(FALLBACK_KEY, "AES"),
                IvParameterSpec(FALLBACK_IV)
            )
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8).trim()
        } catch (e: Exception) {
            Log.e(TAG, "Fallback AES failed: ${e.message}")
            null
        }
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Decrypt a PlayFy TV API `data` field.
     *
     * Priority:
     *  1. If already JSON, return as-is.
     *  2. NintyMin.ohMyNo() via PlayFy TV's native library.
     *  3. Fallback AES with hardcoded keys from decompiled APK.
     *
     * @param body    The encrypted `data` string from the API JSON response
     * @param context Android context (needed for NintyMin.ohMyNo and PathClassLoader)
     */
    fun decrypt(body: String?, context: Context): String? {
        val raw = body?.trim().orEmpty()
        if (raw.isEmpty()) return null

        // Already plaintext?
        if (raw.startsWith("{") || raw.startsWith("[")) return raw

        // Primary: NintyMin.ohMyNo via PlayFy TV's installed native libs
        val cls = getNintyMinClass(context)
        if (cls != null) {
            val result = callOhMyNo(cls, raw, context)
            if (!result.isNullOrBlank() &&
                (result.startsWith("{") || result.startsWith("["))
            ) {
                Log.d(TAG, "Primary decrypt OK (NintyMin.ohMyNo)")
                return result
            }
        }

        // Fallback: substitution cipher + AES
        val fallback = fallbackDecrypt(raw)
        if (!fallback.isNullOrBlank() &&
            (fallback.startsWith("{") || fallback.startsWith("["))
        ) {
            Log.d(TAG, "Fallback AES decrypt OK")
            return fallback
        }

        Log.e(TAG, "All decryption attempts failed")
        return null
    }

    /** Check if PlayFy TV is installed on the device */
    fun isPlayFyInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getApplicationInfo(PLAYFY_PKG, 0)
            true
        } catch (_: Exception) {
            false
        }
    }
}
