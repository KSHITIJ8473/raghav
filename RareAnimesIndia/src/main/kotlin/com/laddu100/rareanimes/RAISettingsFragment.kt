package com.laddu100.rareanimes

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
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

    private val panelTop = 0xFF1C1C20.toInt()
    private val panelBottom = 0xFF141418.toInt()
    private val edge = 0x33FFFFFF.toInt()
    private val edgeSoft = 0x1FFFFFFF.toInt()
    private val surface = 0xFF1A1A1E.toInt()
    private val glassFill = 0x14FFFFFF.toInt()
    private val glassRaised = 0x1FFFFFFF.toInt()
    private val textDim = 0xFF98989F.toInt()
    private val textMid = 0xFFD1D1D6.toInt()
    private val green = 0xFF34C759.toInt()
    private val blueHi = 0xFF409CFF.toInt()
    private val blueDeep = 0xFF0060DF.toInt()

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(panelTop, panelBottom)
            ).apply {
                cornerRadii = floatArrayOf(
                    dp(26).toFloat(), dp(26).toFloat(),
                    dp(26).toFloat(), dp(26).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setStroke(dp(1), edge)
            }
            layoutParams = ViewGroup.LayoutParams(-1, -2)
        }

        root.addView(TextView(ctx).apply {
            text = "RAGHAV REPO · RARE TOONS INDIA"
            textSize = 10f
            setTextColor(textDim)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.18f
            setPadding(dp(4), 0, dp(4), dp(10))
        })

        root.addView(TextView(ctx).apply {
            text = "Settings"
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), 0, dp(4), dp(6))
        })

        root.addView(TextView(ctx).apply {
            text = "If a site shows a \"Just a moment\" screen, pick it below and solve the challenge. Cookies are saved per site for 15 hours."
            textSize = 12.5f
            setTextColor(textDim)
            setLineSpacing(0f, 1.3f)
            setPadding(dp(4), 0, dp(4), dp(16))
        })

        val statusChip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = GradientDrawable().apply {
                setColor(glassFill)
                cornerRadius = dp(9).toFloat()
                setStroke(dp(1), edgeSoft)
            }
            layoutParams = LinearLayout.LayoutParams(-2, -2).also { it.bottomMargin = dp(16) }
        }
        val statusDot = View(ctx)
        val statusText = TextView(ctx).apply {
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
        }
        fun renderStatus() {
            val anyCookie = RAICFStore.hasAnyCookies()
            statusDot.background = GradientDrawable().apply {
                setColor(if (anyCookie) green else textDim)
                cornerRadius = dp(4).toFloat()
            }
            statusText.text = if (anyCookie) "CF cookies saved" else "No CF cookies saved"
            statusText.setTextColor(if (anyCookie) green else textDim)
        }
        statusChip.addView(statusDot, LinearLayout.LayoutParams(dp(7), dp(7)).also { it.rightMargin = dp(8) })
        statusChip.addView(statusText)
        root.addView(statusChip)
        renderStatus()

        val sites = listOf(
            "rareanimes.mov" to "https://$MAIN_HOST",
            "store.animetoonhindi.com" to "https://$STORE_HOST",
            "codedew.com" to "https://$CODEDEW_HOST",
            "argon player" to "https://$ARGON_HOST"
        )

        val rows = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(6) }
        }
        sites.forEach { (label, url) ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(15), dp(16), dp(15))
                isClickable = true
                background = GradientDrawable().apply {
                    setColor(surface)
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), edgeSoft)
                }
                layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) }
            }
            row.addView(TextView(ctx).apply {
                text = label
                textSize = 14f
                setTextColor(0xFFFFFFFF.toInt())
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            row.addView(TextView(ctx).apply {
                text = "SOLVE  ›"
                textSize = 11f
                letterSpacing = 0.1f
                setTextColor(blueHi)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = GradientDrawable().apply {
                    setColor(glassRaised)
                    cornerRadius = dp(11).toFloat()
                    setStroke(dp(1), edgeSoft)
                }
            })
            row.setOnClickListener {
                statusText.text = "Opening $label..."
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        val success = showRAICFBypassDialogAndWait(url)
                        renderStatus()
                        Toast.makeText(
                            ctx,
                            if (success) "Saved" else "Cancelled",
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (_: Exception) {
                        renderStatus()
                    }
                }
            }
            rows.addView(row)
        }
        root.addView(rows)

        val clearBg = GradientDrawable().apply {
            setColor(glassFill)
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), edge)
        }
        val clearBtn = Button(ctx).apply {
            text = "Clear All CF Cookies"
            textSize = 14f
            setTextColor(textMid)
            setAllCaps(false)
            typeface = Typeface.DEFAULT_BOLD
            isFocusable = true
            background = clearBg
            setOnFocusChangeListener { _, hasFocus ->
                clearBg.setStroke(dp(1), if (hasFocus) 0x66FFFFFF.toInt() else edge)
            }
            layoutParams = LinearLayout.LayoutParams(-1, dp(46)).also { it.bottomMargin = dp(14) }
        }
        root.addView(clearBtn)

        clearBtn.setOnClickListener {
            AlertDialog.Builder(ctx)
                .setTitle("Clear CF cookies?")
                .setMessage("You may need to bypass Cloudflare again before content loads.")
                .setPositiveButton("Clear") { _, _ ->
                    RAICFStore.clear()
                    renderStatus()
                    Toast.makeText(ctx, "Cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
                .show()
        }

        val closeBtn = Button(ctx).apply {
            text = "CLOSE"
            textSize = 13f
            letterSpacing = 0.18f
            setTextColor(0xFF070709.toInt())
            setAllCaps(false)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColors(intArrayOf(0xFFFFFFFF.toInt(), 0xFFD9D9DE.toInt()))
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                cornerRadius = dp(14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(-1, dp(48))
        }
        root.addView(closeBtn)
        closeBtn.setOnClickListener { dismiss() }

        return root
    }
}
