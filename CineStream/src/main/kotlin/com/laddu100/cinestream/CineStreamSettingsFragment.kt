package com.laddu100.cinestream

import android.annotation.SuppressLint
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

class CineStreamSettingsFragment(private val plugin: Plugin) : BottomSheetDialogFragment() {

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

        root.addView(TextView(ctx).apply {
            text = "CinestreamSite Settings"
            textSize = 20f; setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, smallPad)
        })

        root.addView(TextView(ctx).apply {
            text = "If the site shows a \"Just a moment\" screen, tap Bypass Cloudflare to solve the challenge. Cookies are saved for 15 hours."
            textSize = 13f; setTextColor(Color.parseColor("#B0B0C0"))
            setPadding(0, 0, 0, smallPad)
        })

        val bypassBtn = Button(ctx).apply {
            text = if (CineStreamCFStore.getCookies() != null) "CF Cookies Saved" else "Bypass Cloudflare"
            background = makeBg(0xFF6D5ACF.toInt())
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = smallPad }
        }
        root.addView(bypassBtn)

        val clearBtn = Button(ctx).apply {
            text = "Clear CF Cookies"
            background = makeBg(0xFFE5484D.toInt())
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = smallPad }
        }
        root.addView(clearBtn)

        val saveBtn = Button(ctx).apply {
            text = "Save & Close"
            background = makeBg(0xFF2E7D32.toInt())
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        root.addView(saveBtn)

        bypassBtn.setOnClickListener {
            val host = "https://cinestream.kje.us"
            try {
                val cm = android.webkit.CookieManager.getInstance()
                listOf("cf_clearance", "cf_chl_rc_ni", "cf_chl_prog").forEach { name ->
                    cm.setCookie(host, "$name=; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
                }
                cm.flush()
            } catch (e: Exception) {
                Log.e("CineStream_Settings", "clear: ${e.message}")
            }
            CineStreamCFStore.clear()

            bypassBtn.text = "Solving..."
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val success = showCineStreamCFBypassDialogAndWait(host)
                    bypassBtn.text = if (success && CineStreamCFStore.getCookies() != null) "CF Cookies Saved" else "Bypass Cloudflare"
                    Toast.makeText(ctx, if (success) "Saved" else "Cancelled", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    bypassBtn.text = "Bypass Cloudflare"
                    Toast.makeText(ctx, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        clearBtn.setOnClickListener {
            AlertDialog.Builder(ctx)
                .setTitle("Clear CF Cookies?")
                .setMessage("You will need to bypass Cloudflare again before content loads.")
                .setPositiveButton("Clear") { _, _ ->
                    val host = "https://cinestream.kje.us"
                    try {
                        val cm = android.webkit.CookieManager.getInstance()
                        listOf("cf_clearance", "cf_chl_rc_ni", "cf_chl_prog").forEach { name ->
                            cm.setCookie(host, "$name=; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
                        }
                        cm.flush()
                    } catch (e: Exception) {}
                    CineStreamCFStore.clear()
                    bypassBtn.text = "Bypass Cloudflare"
                    Toast.makeText(ctx, "Cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
                .show()
        }

        saveBtn.setOnClickListener {
            dismiss()
        }

        return root
    }

    private fun makeBg(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 12f
        setColor(color)
    }
}
