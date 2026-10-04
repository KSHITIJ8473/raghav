package com.netnaija.app.settings

import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.netnaija.app.NetNaijaApp
import com.netnaija.app.NetNaijaAppPlugin
import com.netnaija.app.NetNaijaAppSources
import kotlin.math.max

class NetNaijaAppSettingsFragment(
    private val plugin: NetNaijaAppPlugin,
    private val sharedPref: SharedPreferences
) : DialogFragment() {

    private val HOST_POOL = listOf(
        "https://api6.aoneroom.com",
        "https://api5.aoneroom.com",
        "https://api4.aoneroom.com",
        "https://api4sg.aoneroom.com",
        "https://api3.aoneroom.com"
    )

    // ids resolve through the plugin's own resource table; going through the
    // host app's would collide with app resource ids and crash
    private val res: Resources = plugin.resources ?: throw Exception("Unable to access plugin resources")

    private fun findView(view: View, name: String): View {
        val id = res.getIdentifier(name, "id", "com.netnaija.app")
        if (id == 0) throw Exception("View ID $name not found.")
        return view.findViewById(id) ?: throw Exception("View $name not found.")
    }

    private fun drawable(name: String) = res.getDrawable(res.getIdentifier(name, "drawable", "com.netnaija.app"), null)

    override fun onStart() {
        super.onStart()
        val dialog = dialog ?: return
        val window = dialog.window ?: return
        val metrics = resources.displayMetrics
        val maxDialogWidth = (500f * metrics.density).toInt()
        val width = if (metrics.widthPixels > maxDialogWidth) maxDialogWidth else (metrics.widthPixels * 0.94f).toInt()
        window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setBackgroundDrawable(ColorDrawable(0))
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // the binary xml is read from the plugin arsc, the layout itself stays
        // free of inline resource refs and takes its drawables from here
        val layoutId = res.getIdentifier("fragment_netnaija_app_settings", "layout", "com.netnaija.app")
        if (layoutId == 0) throw Exception("Settings layout not found.")
        val layoutParser = res.getLayout(layoutId)
        val view = inflater.inflate(layoutParser, container, false)
        view.background = drawable("dialog_background")

        val hostRow = findView(view, "hostRow")
        val hostSubtitle = findView(view, "hostSubtitle") as TextView
        val chevronHost = findView(view, "chevron_host") as ImageView
        val sourcesRow = findView(view, "sourcesRow")
        val sourcesSubtitle = findView(view, "sourcesSubtitle") as TextView
        val chevronSources = findView(view, "chevron_sources") as ImageView
        val sectionsRow = findView(view, "sectionsRow")
        val sectionsSubtitle = findView(view, "sectionsSubtitle") as TextView
        val chevronSections = findView(view, "chevron_sections") as ImageView
        val saveContainer = findView(view, "saveContainer")
        val saveIcon = findView(view, "saveIcon") as ImageView

        hostRow.background = drawable("settings_item_background")
        sourcesRow.background = drawable("settings_item_background")
        sectionsRow.background = drawable("settings_item_background")
        chevronHost.setImageDrawable(drawable("ic_chevron"))
        chevronSources.setImageDrawable(drawable("ic_chevron"))
        chevronSections.setImageDrawable(drawable("ic_chevron"))
        saveContainer.background = drawable("save_button_background")
        saveIcon.setImageDrawable(drawable("save_icon"))

        val hostNames = HOST_POOL.map { it.removePrefix("https://") }.toTypedArray()
        var currentHostIndex = max(HOST_POOL.indexOf(sharedPref.getString("netnaija_app_host", HOST_POOL[4])), 0)
        hostSubtitle.text = "Current: ${hostNames[currentHostIndex]}"

        val known = NetNaijaAppSources.knownSources()
        val enabled = known.count { NetNaijaAppSources.isEnabled(it) }
        sourcesSubtitle.text = "$enabled of ${known.size} enabled"

        val shown = NetNaijaApp.visibleSections(sharedPref).size
        sectionsSubtitle.text = "$shown of ${NetNaijaApp.sectionCatalog.size} shown"

        hostRow.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Select API Host")
                .setSingleChoiceItems(hostNames, currentHostIndex) { dialog, which ->
                    currentHostIndex = which
                    val selected = HOST_POOL[which]
                    sharedPref.edit().putString("netnaija_app_host", selected).apply()
                    hostSubtitle.text = "Current: ${hostNames[which]}"
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        sourcesRow.setOnClickListener {
            NetNaijaAppSourcesFragment().show(parentFragmentManager, "NetNaijaAppSources")
        }

        sectionsRow.setOnClickListener {
            NetNaijaAppSectionsFragment(sharedPref).show(parentFragmentManager, "NetNaijaAppSections")
        }

        saveContainer.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Save & Reload")
                .setMessage("Changes have been saved. Do you want to restart the app to apply them?")
                .setPositiveButton("Yes") { dialog, _ ->
                    dismiss()
                    restartApp()
                }
                .setNegativeButton("No", null)
                .show()
        }

        return view
    }

    // a full restart is the only way to make the freshly stored host reach the
    // already constructed provider instance
    private fun restartApp() {
        try {
            val context = requireContext().applicationContext
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val componentName = intent?.component
            if (componentName != null) {
                val restartIntent = Intent.makeRestartActivityTask(componentName)
                context.startActivity(restartIntent)
                Runtime.getRuntime().exit(0)
            }
        } catch (_: Exception) {}
    }
}
