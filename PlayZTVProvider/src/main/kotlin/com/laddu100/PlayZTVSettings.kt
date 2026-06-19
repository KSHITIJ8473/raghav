package com.laddu100

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.Switch
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast

/**
 * PlayZTV provider selector — displayed as a bottom sheet.
 *
 * Uses Switch toggles (instead of checkboxes) and a different layout
 * flow than the LIVETV counterpart. Shares drawable/string resources
 * via the `com.laddu100` package namespace.
 */
class PlayZTVSettings(
    private val plugin: PlayZTVPlugin,
    private val sharedPref: SharedPreferences?,
    private val playlistNames: List<String>
) : BottomSheetDialogFragment() {

    // ── State ──────────────────────────────────────────────────────────────────

    private val selected = playlistNames
        .filter { sharedPref?.getBoolean(it, false) == true }
        .toMutableSet()

    // ── Resource helpers ───────────────────────────────────────────────────────

    @SuppressLint("DiscouragedApi")
    private fun loadDrawable(name: String): Drawable? {
        val res = plugin.resources ?: return null
        val id = res.getIdentifier(name, "drawable", "com.laddu100")
        return if (id != 0) ResourcesCompat.getDrawable(res, id, null) else null
    }

    @SuppressLint("DiscouragedApi")
    private fun loadString(name: String): String? {
        val res = plugin.resources ?: return null
        val id = res.getIdentifier(name, "string", "com.laddu100")
        return if (id != 0) res.getString(id) else null
    }

    @SuppressLint("DiscouragedApi")
    private fun <T : View> View.findByIdName(name: String): T? {
        val res = plugin.resources ?: return null
        val id = res.getIdentifier(name, "id", "com.laddu100")
        return if (id != 0) findViewById(id) else null
    }

    private fun View.applyTvPadding() {
        setPadding(paddingLeft + 12, paddingTop + 12, paddingRight + 12, paddingBottom + 12)
        background = loadDrawable("outline")
    }

    // ── Fragment lifecycle ─────────────────────────────────────────────────────

    @SuppressLint("DiscouragedApi")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val res = plugin.resources ?: return null
        val layoutId = res.getIdentifier("settings", "layout", "com.laddu100")
        return if (layoutId != 0) inflater.inflate(res.getLayout(layoutId), container, false) else null
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    @RequiresApi(Build.VERSION_CODES.M)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Title texts
        view.findByIdName<TextView>("header_tw")?.text = loadString("header_tw")
        view.findByIdName<TextView>("header2_tw")?.text = loadString("header2_tw")

        // Save button
        val saveBtn = view.findByIdName<ImageButton>("save_btn")
        saveBtn?.applyTvPadding()
        saveBtn?.setImageDrawable(loadDrawable("save_icon"))

        // Build rows
        val container = view.findByIdName<LinearLayout>("list")
        for (name in playlistNames) {
            container?.addView(createToggleRow(name))
        }

        // Save action
        saveBtn?.setOnClickListener { persistAndPrompt() }
    }

    // ── Row builder ────────────────────────────────────────────────────────────

    private fun createToggleRow(name: String): RelativeLayout {
        val ctx = requireContext()

        val root = RelativeLayout(ctx).apply {
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 0, 0, 12)
        }

        val label = TextView(ctx).apply {
            id = View.generateViewId()
            text = name.substringAfter("playlist_")
            textSize = 17f
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_START)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
        }

        val toggle = Switch(ctx).apply {
            id = View.generateViewId()
            isChecked = selected.contains(name)
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_END)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
            setOnCheckedChangeListener { _, on ->
                if (on) selected.add(name) else selected.remove(name)
            }
        }

        root.addView(label)
        root.addView(toggle)
        return root
    }

    // ── Persistence ────────────────────────────────────────────────────────────

    private fun persistAndPrompt() {
        sharedPref?.edit()?.apply {
            clear()
            for (name in selected) putBoolean(name, true)
            apply()
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Restart Required")
            .setMessage("Changes saved. Restart the app to apply them?")
            .setPositiveButton("Yes") { _, _ ->
                dismiss()
                triggerRestart()
            }
            .setNegativeButton("No") { dlg, _ ->
                dlg.dismiss()
                showToast("Settings saved. Restart to apply changes.")
            }
            .show()
    }

    private fun triggerRestart() {
        val ctx = requireContext().applicationContext
        val launchIntent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        val comp = launchIntent?.component ?: return
        ctx.startActivity(Intent.makeRestartActivityTask(comp))
        Runtime.getRuntime().exit(0)
    }
}
