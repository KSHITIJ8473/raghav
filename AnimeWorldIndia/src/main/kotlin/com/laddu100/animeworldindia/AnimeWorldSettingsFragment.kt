package com.laddu100.animeworldindia

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
import com.lagradost.api.Log
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AnimeWorldSettingsFragment(private val plugin: Plugin) : BottomSheetDialogFragment() {

    private val panelTop = 0xFF1C1C20.toInt()
    private val panelBottom = 0xFF141418.toInt()
    private val edge = 0x33FFFFFF.toInt()
    private val edgeSoft = 0x1FFFFFFF.toInt()
    private val glassFill = 0x14FFFFFF.toInt()
    private val textDim = 0xFF98989F.toInt()
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
            text = "RAGHAV REPO · ANIME WORLD INDIA"
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
            text = "If the site shows a \"Just a moment\" screen, tap Bypass Cloudflare to solve the challenge. Cookies are saved for 15 hours."
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
        val cookieOn = AnimeWorldCFStore.getCookies() != null
        statusChip.addView(View(ctx).apply {
            background = GradientDrawable().apply {
                setColor(if (cookieOn) green else textDim)
                cornerRadius = dp(4).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).also { it.rightMargin = dp(8) }
        })
        statusChip.addView(TextView(ctx).apply {
            text = if (cookieOn) "CF cookies saved" else "No CF cookies saved"
            textSize = 11.5f
            setTextColor(if (cookieOn) green else textDim)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(statusChip)

        val bypassBtn = Button(ctx).apply {
            text = if (cookieOn) "Bypass Cloudflare Again" else "Bypass Cloudflare"
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setAllCaps(false)
            typeface = Typeface.DEFAULT_BOLD
            isFocusable = true
            setPadding(dp(20), 0, dp(20), 0)
            background = GradientDrawable().apply {
                setColors(intArrayOf(blueHi, blueDeep))
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                cornerRadius = dp(16).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(-1, dp(50)).also { it.bottomMargin = dp(10) }
        }
        root.addView(bypassBtn)

        val clearBg = GradientDrawable().apply {
            setColor(glassFill)
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), edge)
        }
        val clearBtn = Button(ctx).apply {
            text = "Clear CF Cookies"
            textSize = 14f
            setTextColor(0xFFD1D1D6.toInt())
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

        bypassBtn.setOnClickListener {
            val host = "https://watchanimeworld.top"
            try {
                val cm = android.webkit.CookieManager.getInstance()
                listOf("cf_clearance", "cf_chl_rc_ni", "cf_chl_prog").forEach { name ->
                    cm.setCookie(host, "$name=; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
                }
                cm.flush()
            } catch (e: Exception) {
                Log.d("AnimeWorld", "cookie clear failed: ${e.message}")
            }
            AnimeWorldCFStore.clear()

            bypassBtn.text = "Solving..."
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val success = showAnimeWorldCFBypassDialogAndWait(host)
                    bypassBtn.text = if (success && AnimeWorldCFStore.getCookies() != null) "Bypass Cloudflare Again" else "Bypass Cloudflare"
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
                    val host = "https://watchanimeworld.top"
                    try {
                        val cm = android.webkit.CookieManager.getInstance()
                        listOf("cf_clearance", "cf_chl_rc_ni", "cf_chl_prog").forEach { name ->
                            cm.setCookie(host, "$name=; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
                        }
                        cm.flush()
                    } catch (e: Exception) {
                        Log.d("AnimeWorld", "cookie clear failed: ${e.message}")
                    }
                    AnimeWorldCFStore.clear()
                    bypassBtn.text = "Bypass Cloudflare"
                    Toast.makeText(ctx, "Cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
                .show()
        }

        closeBtn.setOnClickListener { dismiss() }

        return root
    }
}
