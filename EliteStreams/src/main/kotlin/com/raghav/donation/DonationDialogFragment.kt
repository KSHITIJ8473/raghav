package com.raghav.donation

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import java.util.Calendar
import java.util.Locale

class DonationDialogFragment : DialogFragment() {

    companion object {
        private const val DONATE_URL = "https://buymeacoffee.com/raghav766"
        private const val DISCORD_URL = "https://discord.gg/V9VC8w29U"
        private const val REPO_URL = "https://github.com/KSHITIJ8473/raghav"

        private const val DISMISS_SECONDS = 9

        private const val BG = "#131012"
        private const val BG_HERO_TOP = "#1D1113"
        private const val BG_CHIP = "#1B1416"
        private const val BG_CHIP_ALT = "#1A1315"
        private const val STROKE = "#2E1B1E"
        private const val STROKE_STRONG = "#402226"
        private const val DIVIDER = "#2A1A1D"
        private const val TEXT_DIM = "#A1A1AA"
        private const val TEXT_MID = "#D4D4D8"
        private const val TEXT_BRIGHT = "#E4E4E7"
        private const val RED = "#F87171"
        private const val RED_SOFT = "#FCA5A5"
        private const val DISCORD = "#5865F2"
        private const val DISCORD_DARK = "#4752C4"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var dismissTicker: Runnable? = null
    private var dismissButton: Button? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setDimAmount(0.7f)
        return dialog
    }

    override fun onStart() {
        super.onStart()
        val metrics = resources.displayMetrics
        val maxWidth = (440 * metrics.density).toInt()
        val screenWidth = (metrics.widthPixels * 0.92).toInt()
        dialog?.window?.apply {
            setLayout(minOf(screenWidth, maxWidth), ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val density = resources.displayMetrics.density

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor(BG))
                cornerRadius = dp(density, 22f)
                setStroke(dp(density, 1), Color.parseColor(STROKE))
            }
            layoutParams = ViewGroup.LayoutParams(-1, -2)
        }

        val hero = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(density, 20), dp(density, 20), dp(density, 20), dp(density, 18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor(BG_HERO_TOP), Color.parseColor(BG))
            ).apply {
                cornerRadii = floatArrayOf(
                    dp(density, 22f), dp(density, 22f),
                    dp(density, 22f), dp(density, 22f),
                    0f, 0f, 0f, 0f
                )
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }

        val badgeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).also {
                it.bottomMargin = dp(density, 14)
            }
        }

        badgeRow.addView(TextView(ctx).apply {
            text = "Raghav Repo  •  raghav ↗"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(RED))
            setPadding(dp(density, 10), dp(density, 4), dp(density, 10), dp(density, 4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#211316"))
                cornerRadius = dp(density, 6f)
                setStroke(dp(density, 1), Color.parseColor(STROKE_STRONG))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                stopCountdown()
                openUrl(ctx, REPO_URL)
            }
            layoutParams = LinearLayout.LayoutParams(-2, -2)
        })

        badgeRow.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })

        badgeRow.addView(TextView(ctx).apply {
            text = currentMonth()
            textSize = 10.5f
            setTextColor(Color.parseColor(TEXT_MID))
            setPadding(dp(density, 8), dp(density, 3), dp(density, 8), dp(density, 3))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(BG_CHIP_ALT))
                cornerRadius = dp(density, 5f)
            }
            layoutParams = LinearLayout.LayoutParams(-2, -2)
        })

        hero.addView(badgeRow)

        hero.addView(TextView(ctx).apply {
            text = "Support My Work And Keep The Repo Alive"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(-1, -2).also {
                it.bottomMargin = dp(density, 6)
            }
        })

        hero.addView(TextView(ctx).apply {
            text = "If you'd like to contribute, it would really help me maintain the repo."
            textSize = 13f
            setTextColor(Color.parseColor(TEXT_DIM))
            setLineSpacing(0f, 1.35f)
            layoutParams = LinearLayout.LayoutParams(-1, -2).also {
                it.bottomMargin = dp(density, 16)
            }
        })

        val statsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        statsRow.addView(statChip(ctx, density, "100% Free", RED))
        val fixChip = statChip(ctx, density, "Constant Fixes and Maintenance", RED_SOFT)
        (fixChip.layoutParams as LinearLayout.LayoutParams).topMargin = dp(density, 6)
        statsRow.addView(fixChip)
        hero.addView(statsRow)

        val upiRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).also {
                it.topMargin = dp(density, 12)
            }
        }
        upiRow.addView(TextView(ctx).apply {
            val sb = SpannableStringBuilder()
            val headStart = sb.length
            sb.append("UPI Coming Soon:")
            sb.setSpan(StyleSpan(Typeface.BOLD), headStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(Color.parseColor(TEXT_BRIGHT)), headStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(" ")
            val bodyStart = sb.length
            sb.append("UPI donations will be added soon.")
            sb.setSpan(ForegroundColorSpan(Color.parseColor(TEXT_DIM)), bodyStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = sb
            textSize = 12f
            setLineSpacing(0f, 1.25f)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        hero.addView(upiRow)

        root.addView(hero)

        root.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor(DIVIDER))
            layoutParams = LinearLayout.LayoutParams(-1, dp(density, 1))
        })

        val btnSection = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(density, 16), dp(density, 16), dp(density, 16), dp(density, 16))
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }

        btnSection.addView(Button(ctx).apply {
            text = "Donate"
            textSize = 15f
            setTextColor(Color.WHITE)
            setAllCaps(false)
            typeface = Typeface.DEFAULT_BOLD
            isFocusable = true
            setPadding(dp(density, 20), 0, dp(density, 20), 0)
            val bg = GradientDrawable().apply {
                setColors(intArrayOf(Color.parseColor("#EF4444"), Color.parseColor("#DC2626")))
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                cornerRadius = dp(density, 14f)
            }
            background = bg
            setOnFocusChangeListener { _, hasFocus -> bg.alpha = if (hasFocus) 200 else 255 }
            setOnClickListener {
                openUrl(ctx, DONATE_URL)
                dismissAllowingStateLoss()
            }
            layoutParams = LinearLayout.LayoutParams(-1, dp(density, 50)).also {
                it.bottomMargin = dp(density, 10)
            }
        })

        btnSection.addView(Button(ctx).apply {
            text = "Join Discord"
            textSize = 13f
            setTextColor(Color.WHITE)
            setAllCaps(false)
            typeface = Typeface.DEFAULT_BOLD
            isFocusable = true
            val bg = GradientDrawable().apply {
                setColors(intArrayOf(Color.parseColor(DISCORD), Color.parseColor(DISCORD_DARK)))
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                cornerRadius = dp(density, 14f)
            }
            background = bg
            setOnFocusChangeListener { _, hasFocus -> bg.alpha = if (hasFocus) 200 else 255 }
            setOnClickListener {
                openUrl(ctx, DISCORD_URL)
                dismissAllowingStateLoss()
            }
            layoutParams = LinearLayout.LayoutParams(-1, dp(density, 46)).also {
                it.bottomMargin = dp(density, 10)
            }
        })

        val dismissBg = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(density, 14f)
            setStroke(dp(density, 1), Color.parseColor("#3F3F46"))
        }
        val dismissBtn = Button(ctx).apply {
            text = "Maybe Later ($DISMISS_SECONDS)"
            textSize = 13f
            setTextColor(Color.WHITE)
            setAllCaps(false)
            typeface = Typeface.DEFAULT_BOLD
            isFocusable = true
            background = dismissBg
            setOnFocusChangeListener { _, hasFocus ->
                dismissBg.setStroke(
                    dp(density, 1),
                    Color.parseColor(if (hasFocus) TEXT_MID else "#3F3F46")
                )
            }
            layoutParams = LinearLayout.LayoutParams(-1, dp(density, 46)).also {
                it.bottomMargin = dp(density, 10)
            }
        }
        btnSection.addView(dismissBtn)

        btnSection.addView(TextView(ctx).apply {
            text = "(not affiliated with CloudStream)"
            textSize = 10.5f
            setTextColor(Color.parseColor(TEXT_DIM))
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.ITALIC)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        })

        root.addView(btnSection)

        startDismissCountdown(dismissBtn)

        root.setOnClickListener { stopCountdown() }

        return root
    }

    private fun startDismissCountdown(dismissBtn: Button) {
        dismissButton = dismissBtn
        var secondsLeft = DISMISS_SECONDS
        val ticker = object : Runnable {
            override fun run() {
                secondsLeft--
                if (secondsLeft <= 0) {
                    if (isAdded) dismissAllowingStateLoss()
                } else {
                    dismissBtn.text = "Maybe Later ($secondsLeft)"
                    mainHandler.postDelayed(this, 1000L)
                }
            }
        }
        dismissTicker = ticker
        mainHandler.postDelayed(ticker, 1000L)
        dismissBtn.setOnClickListener { dismissAllowingStateLoss() }
    }

    private fun stopCountdown() {
        dismissTicker?.let { mainHandler.removeCallbacks(it) }
        dismissTicker = null
        dismissButton?.text = "Maybe Later"
    }

    private fun statChip(
        ctx: Context,
        density: Float,
        label: String,
        color: String
    ): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(density, 10), dp(density, 6), dp(density, 12), dp(density, 6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(BG_CHIP))
                cornerRadius = dp(density, 8f)
                setStroke(dp(density, 1), Color.parseColor(STROKE))
            }
            layoutParams = LinearLayout.LayoutParams(-2, -2)
            addView(TextView(ctx).apply {
                text = label
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(color))
                layoutParams = LinearLayout.LayoutParams(-2, -2)
            })
        }
    }

    private fun currentMonth(): String {
        val cal = Calendar.getInstance()
        val month = cal.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.US) ?: ""
        return "$month ${cal.get(Calendar.YEAR)}"
    }

    private fun openUrl(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    override fun onDismiss(dialog: DialogInterface) {
        stopCountdown()
        super.onDismiss(dialog)
        DonationManager.onDialogDismissed()
    }

    override fun onDestroy() {
        stopCountdown()
        super.onDestroy()
        DonationManager.onDialogDismissed()
    }

    private fun dp(density: Float, v: Int): Int = (v * density).toInt()

    private fun dp(density: Float, v: Float): Float = v * density
}
