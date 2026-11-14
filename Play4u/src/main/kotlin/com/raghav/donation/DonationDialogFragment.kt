package com.raghav.donation

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.lagradost.api.Log
import java.util.Calendar
import java.util.Locale

class DonationDialogFragment : DialogFragment() {

    companion object {
        private const val TAG = "RaghavDonation"

        private const val DONATE_URL = "https://buymeacoffee.com/raghav766"
        private const val DISCORD_URL = "https://discord.gg/XpxyxkKdUm"
        private const val REPO_URL = "https://github.com/KSHITIJ8473/raghav"
        private const val UPI_DONATE_URL = "https://kshitij8473.github.io/donate/"

        private const val DISMISS_SECONDS = 16

        private const val PANEL_TOP = 0xE6222228.toInt()
        private const val PANEL_BOTTOM = 0xE016161A.toInt()
        private const val PANEL_TOP_LEGACY = 0xF2222228.toInt()
        private const val PANEL_BOTTOM_LEGACY = 0xF016161A.toInt()
        private const val EDGE = 0x33FFFFFF.toInt()
        private const val EDGE_SOFT = 0x1FFFFFFF.toInt()
        private const val GLASS_FILL = 0x14FFFFFF.toInt()
        private const val GLASS_RAISED = 0x1FFFFFFF.toInt()
        private const val SHEEN_TOP = 0x26FFFFFF.toInt()
        private const val SHEEN_BOTTOM = 0x04FFFFFF.toInt()
        private const val DIVIDER = 0x1FFFFFFF.toInt()
        private const val TEXT_DIM = 0xFF98989F.toInt()
        private const val TEXT_MID = 0xFFD1D1D6.toInt()
        private const val BADGE_TEXT = 0xFF6EB5FF.toInt()
        private const val BLUE = 0xFF0A84FF.toInt()
        private const val BLUE_DEEP = 0xFF0060DF.toInt()
        private const val DISCORD = 0xFF5865F2.toInt()
        private const val DISCORD_DEEP = 0xFF4752C4.toInt()
        private const val GREEN = 0xFF30D158.toInt()
        private const val CYAN = 0xFF64D2FF.toInt()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var dismissTicker: Runnable? = null
    private var dismissButton: Button? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val density = resources.displayMetrics.density
        dialog.window?.apply {
            setDimAmount(0.45f)
            setBackgroundDrawable(GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    intArrayOf(PANEL_TOP, PANEL_BOTTOM)
                } else {
                    intArrayOf(PANEL_TOP_LEGACY, PANEL_BOTTOM_LEGACY)
                }
                cornerRadius = dp(density, 26f)
                setStroke(dp(density, 1), EDGE)
            })
        }
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
        val goal = readGoal()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(-1, -2)
        }

        val hero = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(density, 20), dp(density, 20), dp(density, 20), dp(density, 18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(SHEEN_TOP, SHEEN_BOTTOM)
            ).apply {
                cornerRadii = floatArrayOf(
                    dp(density, 26f), dp(density, 26f),
                    dp(density, 26f), dp(density, 26f),
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
            setTextColor(BADGE_TEXT)
            setPadding(dp(density, 10), dp(density, 4), dp(density, 10), dp(density, 4))
            background = GradientDrawable().apply {
                setColor(GLASS_FILL)
                cornerRadius = dp(density, 8f)
                setStroke(dp(density, 1), EDGE_SOFT)
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
            setTextColor(TEXT_MID)
            setPadding(dp(density, 8), dp(density, 3), dp(density, 8), dp(density, 3))
            background = GradientDrawable().apply {
                setColor(GLASS_FILL)
                cornerRadius = dp(density, 7f)
            }
            layoutParams = LinearLayout.LayoutParams(-2, -2)
        })

        hero.addView(badgeRow)

        hero.addView(TextView(ctx).apply {
            text = "Support My Work And Keep The Repo Alive"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(-1, -2).also {
                it.bottomMargin = dp(density, 6)
            }
        })

        hero.addView(TextView(ctx).apply {
            text = "If you'd like to contribute, it would really help me maintain the repo."
            textSize = 13f
            setTextColor(TEXT_DIM)
            setLineSpacing(0f, 1.35f)
            layoutParams = LinearLayout.LayoutParams(-1, -2).also {
                it.bottomMargin = dp(density, 16)
            }
        })

        val statsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        statsRow.addView(chip(ctx, density, "100% Free", GREEN))
        val fixChip = chip(ctx, density, "Constant Fixes and Maintenance", CYAN)
        (fixChip.layoutParams as LinearLayout.LayoutParams).topMargin = dp(density, 6)
        statsRow.addView(fixChip)
        hero.addView(statsRow)

        hero.addView(goalCard(ctx, density, goal))

        root.addView(hero)

        root.addView(View(ctx).apply {
            setBackgroundColor(DIVIDER)
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
            setTextColor(0xFFFFFFFF.toInt())
            setAllCaps(false)
            typeface = Typeface.DEFAULT_BOLD
            isFocusable = true
            setPadding(dp(density, 20), 0, dp(density, 20), 0)
            val bg = GradientDrawable().apply {
                setColors(intArrayOf(BLUE, BLUE_DEEP))
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                cornerRadius = dp(density, 16f)
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
            text = "Donate via upi (India)"
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setAllCaps(false)
            typeface = Typeface.DEFAULT_BOLD
            isFocusable = true
            setPadding(dp(density, 20), 0, dp(density, 20), 0)
            val bg = GradientDrawable().apply {
                setColor(GLASS_RAISED)
                cornerRadius = dp(density, 16f)
                setStroke(dp(density, 1), EDGE)
            }
            background = bg
            setOnFocusChangeListener { _, hasFocus -> bg.alpha = if (hasFocus) 190 else 255 }
            setOnClickListener {
                openUrl(ctx, UPI_DONATE_URL)
                dismissAllowingStateLoss()
            }
            layoutParams = LinearLayout.LayoutParams(-1, dp(density, 50)).also {
                it.marginStart = dp(density, 20)
                it.marginEnd = dp(density, 20)
                it.bottomMargin = dp(density, 10)
            }
        })

        btnSection.addView(Button(ctx).apply {
            text = "Join Discord"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setAllCaps(false)
            typeface = Typeface.DEFAULT_BOLD
            isFocusable = true
            val bg = GradientDrawable().apply {
                setColors(intArrayOf(DISCORD, DISCORD_DEEP))
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                cornerRadius = dp(density, 16f)
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
            setColor(0x0DFFFFFF.toInt())
            cornerRadius = dp(density, 16f)
            setStroke(dp(density, 1), EDGE)
        }
        val dismissBtn = Button(ctx).apply {
            text = "Maybe Later ($DISMISS_SECONDS)"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setAllCaps(false)
            typeface = Typeface.DEFAULT_BOLD
            isFocusable = true
            background = dismissBg
            setOnFocusChangeListener { _, hasFocus ->
                dismissBg.setStroke(
                    dp(density, 1),
                    if (hasFocus) 0x66FFFFFF.toInt() else EDGE
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
            setTextColor(TEXT_DIM)
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.ITALIC)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        })

        root.addView(btnSection)

        startDismissCountdown(dismissBtn)

        root.setOnClickListener { stopCountdown() }

        return root
    }

    private fun readGoal(): DonationManager.Goal {
        val args = arguments ?: return DonationManager.Goal(0.0, 40.0, "$", "", "")
        return DonationManager.Goal(
            args.getDouble("raised", 0.0),
            args.getDouble("target", 40.0),
            args.getString("currency") ?: "$",
            args.getString("donor") ?: "",
            args.getString("donation") ?: ""
        )
    }

    private fun goalCard(
        ctx: Context,
        density: Float,
        goal: DonationManager.Goal
    ): LinearLayout {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(density, 14), dp(density, 12), dp(density, 14), dp(density, 12))
            background = GradientDrawable().apply {
                setColor(GLASS_RAISED)
                cornerRadius = dp(density, 12f)
                setStroke(dp(density, 1), EDGE_SOFT)
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).also {
                it.topMargin = dp(density, 14)
            }
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).also {
                it.bottomMargin = dp(density, 8)
            }
        }
        header.addView(TextView(ctx).apply {
            text = "Monthly Goal"
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT_MID)
            layoutParams = LinearLayout.LayoutParams(-2, -2)
        })
        header.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        header.addView(TextView(ctx).apply {
            text = "${money(goal.raised, goal.currency)} of ${money(goal.target, goal.currency)}"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(-2, -2)
        })
        card.addView(header)

        card.addView(goalBar(ctx, density, goal))

        card.addView(TextView(ctx).apply {
            text = if (goal.achieved) {
                "Goal reached this month, thank you!"
            } else {
                "${goal.percent}% funded so far"
            }
            textSize = 11f
            setTextColor(if (goal.achieved) GREEN else TEXT_DIM)
            layoutParams = LinearLayout.LayoutParams(-1, -2).also {
                it.topMargin = dp(density, 8)
            }
        })

        if (goal.donor.isNotBlank() && goal.donation.isNotBlank()) {
            card.addView(TextView(ctx).apply {
                text = recentContribution(goal)
                textSize = 11f
                setTextColor(TEXT_DIM)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(-1, -2).also {
                    it.topMargin = dp(density, 8)
                }
            })
        }
        return card
    }

    private fun goalBar(
        ctx: Context,
        density: Float,
        goal: DonationManager.Goal
    ): ProgressBar {
        val track = GradientDrawable().apply {
            setColor(GLASS_FILL)
            cornerRadius = dp(density, 6f)
        }
        val fill = GradientDrawable().apply {
            if (goal.achieved) {
                setColor(GREEN)
            } else {
                setColors(intArrayOf(BLUE, CYAN))
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
            }
            cornerRadius = dp(density, 6f)
        }
        val layers = LayerDrawable(
            arrayOf(track, ClipDrawable(fill, Gravity.LEFT, ClipDrawable.HORIZONTAL))
        )
        layers.setId(0, android.R.id.background)
        layers.setId(1, android.R.id.progress)
        return ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = goal.percent
            progressDrawable = layers
            layoutParams = LinearLayout.LayoutParams(-1, dp(density, 10))
        }
    }

    private fun recentContribution(goal: DonationManager.Goal): SpannableStringBuilder {
        val line = SpannableStringBuilder()
        var start = line.length
        line.append("RECENT CONTRIBUTION")
        line.setSpan(StyleSpan(Typeface.BOLD), start, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        line.setSpan(ForegroundColorSpan(TEXT_MID), start, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        line.append(" - ")
        start = line.length
        line.append(goal.donor)
        line.setSpan(StyleSpan(Typeface.BOLD), start, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        line.setSpan(ForegroundColorSpan(0xFFFFFFFF.toInt()), start, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        line.append(" donated ")
        start = line.length
        line.append(goal.donation)
        line.setSpan(StyleSpan(Typeface.BOLD), start, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        line.setSpan(ForegroundColorSpan(GREEN), start, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return line
    }

    private fun money(amount: Double, currency: String): String {
        val whole = amount.toLong()
        return if (amount == whole.toDouble()) {
            "$currency$whole"
        } else {
            currency + String.format(Locale.US, "%.2f", amount)
        }
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

    private fun chip(ctx: Context, density: Float, label: String, color: Int): TextView {
        return TextView(ctx).apply {
            text = label
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color)
            setPadding(dp(density, 10), dp(density, 6), dp(density, 10), dp(density, 6))
            background = GradientDrawable().apply {
                setColor(GLASS_FILL)
                cornerRadius = dp(density, 9f)
                setStroke(dp(density, 1), EDGE_SOFT)
            }
            layoutParams = LinearLayout.LayoutParams(-2, -2)
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
        } catch (e: Exception) {
            Log.e(TAG, "open url failed: ${e.message}")
        }
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
