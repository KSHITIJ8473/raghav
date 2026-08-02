package com.laddu100.raghavanime.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.appcompat.widget.SwitchCompat
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.MainActivity
import com.laddu100.raghavanime.RaghavAnimeFeatures

class SettingsFragment : DialogFragment() {

    private val cBg = Color.parseColor("#0A0A0A")
    private val cCard = Color.parseColor("#1A1A1A")
    private val cBorder = Color.parseColor("#2A2A2A")
    private val cAccent = Color.parseColor("#FF1744")
    private val cText = Color.parseColor("#FFFFFF")
    private val cTextSub = Color.parseColor("#9E9E9E")
    private val cTextDim = Color.parseColor("#616161")
    private val cWarning = Color.parseColor("#FF5252")

    companion object {
        private const val PREFIX = "raghavanime_"
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            val dm = resources.displayMetrics
            val maxW = (420 * dm.density).toInt()
            val w = if (dm.widthPixels > maxW) maxW else (dm.widthPixels * 0.92f).toInt()
            setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        fun Int.dp() = (this * d).toInt()
        val scroll = ScrollView(ctx).apply { setBackgroundColor(cBg) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 24.dp(), 20.dp(), 24.dp()); setBackgroundColor(cBg)
        }
        scroll.addView(root)
        root.addView(TextView(ctx).apply { text = "RAGHAVANIME"; textSize = 24f; setTextColor(cAccent); gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.05f })
        root.addView(TextView(ctx).apply { text = "Tap a section to configure"; textSize = 12f; setTextColor(cTextDim); gravity = Gravity.CENTER; setPadding(0, 2.dp(), 0, 16.dp()) })
        root.addView(sectionBtn(ctx, "EXPERIMENTAL", "Watch time tracker & anime recommendations", d) { showExperimentalDialog(ctx, d) })
        root.addView(Button(ctx).apply { text = "SAVE & RESTART"; setTextColor(cAccent); textSize = 14f; setBackgroundColor(Color.TRANSPARENT); setTypeface(typeface, Typeface.BOLD); setPadding(0, 16.dp(), 0, 0); setOnClickListener { showRestartDialog(ctx) } })
        return scroll
    }

    private fun sectionBtn(ctx: Context, title: String, subtitle: String, d: Float, onClick: () -> Unit): LinearLayout {
        fun Int.dp() = (this * d).toInt()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())
            background = GradientDrawable().apply { setStroke(1, cBorder); cornerRadius = 14 * d; setColor(cCard) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 10.dp() }
            isClickable = true; isFocusable = true
            addView(LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(ctx).apply { text = title; textSize = 16f; setTextColor(cText); setTypeface(typeface, Typeface.BOLD) })
                addView(TextView(ctx).apply { text = subtitle; textSize = 12f; setTextColor(cTextDim); setPadding(0, 2.dp(), 0, 0) }) })
            addView(TextView(ctx).apply { text = ">"; textSize = 20f; setTextColor(cAccent) })
            setOnClickListener { onClick() }
        }
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun showExperimentalDialog(ctx: Context, d: Float) {
        fun Int.dp() = (this * d).toInt()
        val scroll = ScrollView(ctx).apply { setBackgroundColor(cBg) }
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(20.dp(), 16.dp(), 20.dp(), 8.dp()); setBackgroundColor(cBg) }
        layout.addView(TextView(ctx).apply { text = "Experimental Features"; textSize = 18f; setTextColor(cAccent); setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, 4.dp()) })
        layout.addView(TextView(ctx).apply { text = "May not work properly"; textSize = 11f; setTextColor(cWarning); setPadding(0, 0, 0, 12.dp()) })

        layout.addView(sectionLabel(ctx, "WATCH TRACKING", d))
        val watchTimeToggle = toggleRow(ctx, "Watch Time Tracker", RaghavAnimeFeatures.isEnabled("watch_time"), d) { checked ->
            if (!checked) {
                AlertDialog.Builder(ctx).setTitle("WARNING").setMessage("Anime Recommendations depends on Watch Time Tracker.\n\nIf you turn off Watch Time Tracker, recommendations will no longer update.\n\nAre you sure you want to turn it off?")
                    .setPositiveButton("Yes, turn off") { _, _ ->
                        RaghavAnimeFeatures.setEnabled("watch_time", false)
                    }
                    .setNegativeButton("Cancel") { _, _ ->
                        (layout.findViewWithTag<SwitchCompat>("watch_time_toggle"))?.isChecked = true
                    }.show()
            } else {
                RaghavAnimeFeatures.setEnabled("watch_time", true)
            }
        }
        watchTimeToggle.tag = "watch_time_row"
        (watchTimeToggle.getChildAt(1) as? SwitchCompat)?.tag = "watch_time_toggle"
        layout.addView(watchTimeToggle)
        layout.addView(descText(ctx, "Tracks watch time per anime. Required for recommendations.", d))

        layout.addView(sectionLabel(ctx, "RECOMMENDATIONS", d))
        val recToggle = toggleRow(ctx, "Anime Recommendations", RaghavAnimeFeatures.isEnabled("recommendations"), d) { checked ->
            RaghavAnimeFeatures.setEnabled("recommendations", checked)
            if (!checked) {
                Toast.makeText(ctx, "Recommendations turned off. Homepage will not show recommendations.", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(recToggle)
        layout.addView(descText(ctx, "Shows recommended anime on homepage based on your watch history", d))

        layout.addView(Button(ctx).apply { text = "RESET RECOMMENDATIONS"; setTextColor(cWarning); textSize = 12f; setBackgroundColor(Color.TRANSPARENT); setTypeface(typeface, Typeface.BOLD); setPadding(0, 8.dp(), 0, 8.dp())
            setOnClickListener {
                AlertDialog.Builder(ctx).setTitle("Reset Recommendations").setMessage("This will clear all recommendations from the homepage.\n\nNew recommendations will appear after you watch a new anime.\n\nContinue?")
                    .setPositiveButton("Yes, Reset") { _, _ ->
                        RaghavAnimeFeatures.resetRecommendations()
                        Toast.makeText(ctx, "Recommendations cleared. Watch a new anime to generate new ones.", Toast.LENGTH_LONG).show()
                    }
                    .setNegativeButton("Cancel", null).show()
            } })

        layout.addView(Button(ctx).apply { text = "RESET WATCH HISTORY"; setTextColor(cWarning); textSize = 12f; setBackgroundColor(Color.TRANSPARENT); setTypeface(typeface, Typeface.BOLD); setPadding(0, 4.dp(), 0, 8.dp())
            setOnClickListener {
                AlertDialog.Builder(ctx).setTitle("Reset Watch History").setMessage("This will delete ALL watch time data AND reset recommendations.\n\nAre you sure?")
                    .setPositiveButton("Yes, Reset") { _, _ ->
                        RaghavAnimeFeatures.resetWatchHistory()
                        Toast.makeText(ctx, "Watch history reset", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null).show()
            } })
        scroll.addView(layout)
        AlertDialog.Builder(ctx).setView(scroll).setPositiveButton("Save") { _, _ -> }.setNegativeButton("Cancel", null).create().apply { show(); styleButtons() }
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun toggleRow(ctx: Context, label: String, checked: Boolean, d: Float, onChange: (Boolean) -> Unit): LinearLayout {
        fun Int.dp() = (this * d).toInt()
        return LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 10.dp(), 0, 10.dp())
            addView(TextView(ctx).apply { text = label; textSize = 15f; setTextColor(cText); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            addView(SwitchCompat(ctx).apply { isChecked = checked
                trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(cAccent, Color.parseColor("#333333")))
                thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Color.WHITE, Color.parseColor("#666666")))
                setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) } }) }
    }
    private fun sectionLabel(ctx: Context, text: String, d: Float): TextView {
        fun Int.dp() = (this * d).toInt()
        return TextView(ctx).apply { this.text = text; textSize = 12f; setTextColor(cAccent); setTypeface(typeface, Typeface.BOLD); setPadding(0, 12.dp(), 0, 4.dp()) }
    }
    private fun descText(ctx: Context, text: String, d: Float): TextView {
        fun Int.dp() = (this * d).toInt()
        return TextView(ctx).apply { this.text = "  $text"; textSize = 11f; setTextColor(cTextDim); setPadding(0, 0, 0, 8.dp()) }
    }
    private fun AlertDialog.styleButtons() { getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(cAccent); getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(cTextDim) }
    private fun showRestartDialog(ctx: Context) {
        AlertDialog.Builder(ctx).setTitle("Restart Required").setMessage("Restart the app to apply changes?").setPositiveButton("Yes") { _, _ -> restartApp() }.setNegativeButton("No") { _, _ -> try { MainActivity.reloadHomeEvent.invoke(true) } catch (_: Throwable) {} }.show() }
    private fun restartApp() { try { val context = requireContext().applicationContext; val pm = context.packageManager; val intent = pm.getLaunchIntentForPackage(context.packageName); val componentName = intent?.component; if (componentName != null) { val restartIntent = android.content.Intent.makeRestartActivityTask(componentName); context.startActivity(restartIntent); Runtime.getRuntime().exit(0) } } catch (_: Throwable) { try { MainActivity.reloadHomeEvent.invoke(true) } catch (_: Throwable) {} } }
}
