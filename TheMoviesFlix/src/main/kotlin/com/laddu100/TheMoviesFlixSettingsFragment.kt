package com.laddu100

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.api.Log
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TheMoviesFlixSettingsFragment(private val plugin: Plugin) : BottomSheetDialogFragment() {

    private val TAG = "TMF_Settings"

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val smallPad = (8 * dp).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(-1, -2)
        }

        val header = TextView(ctx).apply {
            text = "TheMoviesFlix Settings"
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, smallPad)
        }
        root.addView(header)

        val help = TextView(ctx).apply {
            text = "If search returns no results or shows a \"Just a moment\" screen, tap Bypass Cloudflare to open a WebView and solve the challenge. Cookies are saved automatically and reused for 15 hours."
            textSize = 13f
            setTextColor(Color.parseColor("#B0B0C0"))
            setPadding(0, 0, 0, smallPad)
        }
        root.addView(help)

        val bypassBtn = Button(ctx).apply {
            text = if (TheMoviesFlixPlugin.cfCookies.isNotBlank()) {
                "CF Cookies Saved - Refresh"
            } else {
                "Bypass Cloudflare"
            }
            background = makeButtonBackground(0xFF6D5ACF.toInt())
            setTextColor(Color.WHITE)
            setPadding(0, smallPad, 0, smallPad)
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = smallPad }
        }
        root.addView(bypassBtn)

        val clearBtn = Button(ctx).apply {
            text = "Clear CF Cookies"
            background = makeButtonBackground(0xFFE5484D.toInt())
            setTextColor(Color.WHITE)
            setPadding(0, smallPad, 0, smallPad)
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = smallPad }
        }
        root.addView(clearBtn)

        val saveBtn = Button(ctx).apply {
            text = "Save & Close"
            background = makeButtonBackground(0xFF2E7D32.toInt())
            setTextColor(Color.WHITE)
            setPadding(0, smallPad, 0, smallPad)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        root.addView(saveBtn)

        bypassBtn.setOnClickListener {
            val host = TheMoviesFlixPlugin.cfCookieHost.ifBlank { "https://moviesflixhq.com" }
            val bypassUrl = "$host/?s=movie"
            try {
                val cm = android.webkit.CookieManager.getInstance()
                listOf("cf_clearance", "cf_chl_rc_ni", "cf_chl_prog").forEach { name ->
                    cm.setCookie(host, "$name=; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
                }
                cm.flush()
            } catch (e: Exception) {
                Log.e(TAG, "CookieManager clear: ${e.message}")
            }
            TheMoviesFlixPlugin.cfCookies = ""
            TheMoviesFlixPlugin.cfUserAgent = ""
            TheMoviesFlixPlugin.cfCookieHost = ""
            TMFCFStore.clear()

            bypassBtn.text = "Solving..."
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val success = showTMFCFBypassDialogAndWait(bypassUrl)
                    bypassBtn.text = if (success && TheMoviesFlixPlugin.cfCookies.isNotBlank()) {
                        "CF Cookies Saved - Refresh"
                    } else {
                        "Bypass Cloudflare"
                    }
                    if (success) {
                        Toast.makeText(ctx, "CF cookies saved", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(ctx, "CF bypass cancelled", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Bypass dialog error: ${e.message}")
                    bypassBtn.text = "Bypass Cloudflare"
                    Toast.makeText(ctx, "Bypass failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        clearBtn.setOnClickListener {
            AlertDialog.Builder(ctx)
                .setTitle("Clear CF Cookies?")
                .setMessage("This will remove the saved Cloudflare cookies and User-Agent. You will need to bypass Cloudflare again before search works.")
                .setPositiveButton("Clear") { _, _ ->
                    val host = TheMoviesFlixPlugin.cfCookieHost
                    if (host.isNotBlank()) {
                        try {
                            val cm = android.webkit.CookieManager.getInstance()
                            listOf("cf_clearance", "cf_chl_rc_ni", "cf_chl_prog").forEach { name ->
                                cm.setCookie(host, "$name=; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
                            }
                            cm.flush()
                        } catch (e: Exception) {
                            Log.e(TAG, "CookieManager clear: ${e.message}")
                        }
                    }
                    TheMoviesFlixPlugin.cfCookies = ""
                    TheMoviesFlixPlugin.cfUserAgent = ""
                    TheMoviesFlixPlugin.cfCookieHost = ""
                    TMFCFStore.clear()
                    bypassBtn.text = "Bypass Cloudflare"
                    Toast.makeText(ctx, "CF cookies cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
                .show()
        }

        saveBtn.setOnClickListener {
            Toast.makeText(ctx, "Settings saved", Toast.LENGTH_SHORT).show()
            dismiss()
        }

        return root
    }

    private fun makeButtonBackground(color: Int): android.graphics.drawable.Drawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f
            setColor(color)
        }
    }
}
