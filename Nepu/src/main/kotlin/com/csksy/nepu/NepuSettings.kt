package com.csksy.nepu

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.api.Log
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

private const val TAG = "NepuSettings"
private const val SHEET_BG = 0xFF17171B.toInt()
private const val CARD_BG = 0xFF1F1F26.toInt()
private const val STROKE = 0xFF2A2A33.toInt()
private const val ACCENT = 0xFF3DD598.toInt()
private const val ACCENT_DIM = 0xFF5A5A66.toInt()
private const val ACCENT_TEXT = 0xFF0E1713.toInt()
private const val SECONDARY = 0xFF232329.toInt()
private const val TEXT_SUBTLE = 0xFF9E9EA8.toInt()
private const val TEXT_FAINT = 0xFF6E6E78.toInt()

class NepuSettingsFragment(private val plugin: Plugin) : BottomSheetDialogFragment() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var statusDot: View? = null
    private var statusTitle: TextView? = null
    private var statusDetail: TextView? = null
    private var verifyButton: Button? = null

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun d(v: Int) = (v * dp).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SHEET_BG)
            setPadding(d(22), d(20), d(22), d(18))
        }

        val grabber = View(ctx).apply {
            background = GradientDrawable().apply {
                setColor(ACCENT_DIM)
                cornerRadius = d(2).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(d(36), d(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        root.addView(grabber)
        root.addView(View(ctx), LinearLayout.LayoutParams(1, d(16)))

        root.addView(TextView(ctx).apply {
            text = "Nepu"
            textSize = 21f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(ctx).apply {
            text = "Cloudflare access"
            textSize = 12.5f
            setTextColor(TEXT_SUBTLE)
            setPadding(0, d(2), 0, 0)
        })
        root.addView(View(ctx), LinearLayout.LayoutParams(1, d(16)))

        val statusCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(CARD_BG)
                cornerRadius = d(16).toFloat()
                setStroke(d(1), STROKE)
            }
            setPadding(d(16), d(14), d(16), d(14))
        }

        val dotHolder = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(d(30), d(30))
        }
        statusDot = View(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(d(10), d(10), Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ACCENT)
            }
        }
        dotHolder.addView(statusDot)
        statusCard.addView(dotHolder)

        val textColumn = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                leftMargin = d(14)
            }
        }
        statusTitle = TextView(ctx).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        statusDetail = TextView(ctx).apply {
            textSize = 12f
            setTextColor(TEXT_SUBTLE)
            setPadding(0, d(2), 0, 0)
        }
        textColumn.addView(statusTitle)
        textColumn.addView(statusDetail)
        statusCard.addView(textColumn)
        root.addView(statusCard, LinearLayout.LayoutParams(-1, -2))
        root.addView(View(ctx), LinearLayout.LayoutParams(1, d(14)))

        verifyButton = Button(ctx).apply {
            transformationMethod = null
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ACCENT_TEXT)
            stateListAnimator = null
            background = GradientDrawable().apply {
                setColor(ACCENT)
                cornerRadius = d(26).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(-1, d(52))
            setOnClickListener { runVerification() }
        }
        root.addView(verifyButton)
        root.addView(View(ctx), LinearLayout.LayoutParams(1, d(10)))

        val resetButton = Button(ctx).apply {
            text = "Reset session"
            transformationMethod = null
            textSize = 14f
            setTextColor(Color.WHITE)
            stateListAnimator = null
            background = GradientDrawable().apply {
                setColor(SECONDARY)
                cornerRadius = d(26).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(-1, d(48))
            setOnClickListener {
                NepuSession.clear()
                wipeWebViewCookies("https://nepu.io")
                renderStatus()
                Toast.makeText(ctx, "Session cleared", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(resetButton)
        root.addView(View(ctx), LinearLayout.LayoutParams(1, d(12)))

        root.addView(TextView(ctx).apply {
            text = "Verification opens a short browser check and keeps Nepu working for about 12 hours. It runs on its own when a request is blocked."
            textSize = 11.5f
            setTextColor(TEXT_FAINT)
            setLineSpacing(d(2).toFloat(), 1f)
        })

        renderStatus()
        return root
    }

    private fun remainingText(): String {
        val elapsed = System.currentTimeMillis() - NepuSession.savedAtMillis()
        val left = SESSION_TTL_MS - elapsed
        if (left <= 0) return "expired"
        val hours = TimeUnit.MILLISECONDS.toHours(left)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(left) % 60
        return if (hours > 0) "about ${hours}h ${minutes}m left" else "about ${minutes}m left"
    }

    @SuppressLint("SetTextI18n")
    private fun renderStatus() {
        val active = NepuSession.active()
        (statusDot?.background as? GradientDrawable)?.setColor(if (active) ACCENT else ACCENT_DIM)
        statusTitle?.text = if (active) "Access active" else "Verification required"
        statusDetail?.text = when {
            active -> "Session saved, ${remainingText()}"
            NepuSession.savedAtMillis() == 0L -> "Verify once so Nepu can load content"
            else -> "Previous session expired"
        }
        verifyButton?.apply {
            text = if (active) "Verify again" else "Verify Cloudflare"
            isEnabled = true
            alpha = 1f
        }
    }

    @SuppressLint("SetTextI18n")
    private fun runVerification() {
        val button = verifyButton ?: return
        button.isEnabled = false
        button.alpha = 0.6f
        button.text = "Verifying"
        scope.launch {
            try {
                NepuSession.clear()
                wipeWebViewCookies("https://nepu.io")
                val success = solveNepuCloudflare("https://nepu.io/")
                if (!isAdded) return@launch
                if (success && NepuSession.active()) {
                    Toast.makeText(requireContext(), "Nepu is ready", Toast.LENGTH_SHORT).show()
                } else if (isAdded) {
                    Toast.makeText(requireContext(), "Verification incomplete", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "verify: ${e.message}")
                if (isAdded) {
                    Toast.makeText(requireContext(), "Verification failed", Toast.LENGTH_SHORT).show()
                }
            }
            if (isAdded) renderStatus()
        }
    }
}
