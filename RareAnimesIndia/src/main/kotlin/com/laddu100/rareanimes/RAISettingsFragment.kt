package com.laddu100.rareanimes

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
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RAISettingsFragment(private val plugin: Plugin) : BottomSheetDialogFragment() {

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
            text = "Rare Toons India Settings"
            textSize = 20f; setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, smallPad)
        })

        root.addView(TextView(ctx).apply {
            text = "If the site shows a \"Just a moment\" screen, pick a site below and solve the challenge. Cookies are saved per site for 15 hours."
            textSize = 13f; setTextColor(Color.parseColor("#B0B0C0"))
            setPadding(0, 0, 0, smallPad)
        })

        val statusView = TextView(ctx).apply {
            text = if (RAICFStore.hasAnyCookies()) "CF cookies saved" else "No CF cookies saved"
            textSize = 12f; setTextColor(Color.parseColor("#7FB069"))
            setPadding(0, 0, 0, smallPad)
        }
        root.addView(statusView)

        val sites = listOf(
            "rareanimes.mov" to "https://$MAIN_HOST",
            "store.animetoonhindi.com" to "https://$STORE_HOST",
            "codedew.com" to "https://$CODEDEW_HOST",
            "argon player" to "https://$ARGON_HOST"
        )

        sites.forEach { (label, url) ->
            root.addView(Button(ctx).apply {
                text = "Bypass Cloudflare - $label"
                background = makeBg(0xFF6D5ACF.toInt())
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = smallPad }
                setOnClickListener {
                    statusView.text = "Opening $label..."
                    CoroutineScope(Dispatchers.Main).launch {
                        try {
                            val success = showRAICFBypassDialogAndWait(url)
                            statusView.text = when {
                                success && RAICFStore.hasAnyCookies() -> "CF cookies saved"
                                else -> "Bypass cancelled"
                            }
                            Toast.makeText(
                                ctx,
                                if (success) "Saved" else "Cancelled",
                                Toast.LENGTH_SHORT
                            ).show()
                        } catch (_: Exception) {
                            statusView.text = "Bypass failed"
                        }
                    }
                }
            })
        }

        val clearBtn = Button(ctx).apply {
            text = "Clear All CF Cookies"
            background = makeBg(0xFFE5484D.toInt())
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = smallPad }
        }
        root.addView(clearBtn)

        clearBtn.setOnClickListener {
            AlertDialog.Builder(ctx)
                .setTitle("Clear CF cookies?")
                .setMessage("You may need to bypass Cloudflare again before content loads.")
                .setPositiveButton("Clear") { _, _ ->
                    RAICFStore.clear()
                    statusView.text = "No CF cookies saved"
                    Toast.makeText(ctx, "Cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
                .show()
        }

        val saveBtn = Button(ctx).apply {
            text = "Close"
            background = makeBg(0xFF2E7D32.toInt())
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        root.addView(saveBtn)
        saveBtn.setOnClickListener { dismiss() }

        return root
    }

    private fun makeBg(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 12f
        setColor(color)
    }
}
