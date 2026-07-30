package com.torrentsv1

import android.app.Dialog
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.appcompat.widget.SwitchCompat
import com.lagradost.cloudstream3.MainActivity

class SettingsFragment(
    @Suppress("UNUSED_PARAMETER") private val plugin: TorrentsV1Plugin
) : DialogFragment() {

    private val cBg = Color.parseColor("#0D0D0D")
    private val cCard = Color.parseColor("#1A1A1A")
    private val cCardBorder = Color.parseColor("#2A2A2A")
    private val cAccent = Color.parseColor("#E53935")
    private val cAccentDark = Color.parseColor("#B71C1C")
    private val cText = Color.parseColor("#FFFFFF")
    private val cTextSub = Color.parseColor("#9E9E9E")
    private val cTextDim = Color.parseColor("#616161")

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

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val dp = ctx.resources.displayMetrics.density
        val density = resources.displayMetrics.density
            fun Int.dp() = (this * density).toInt()

        val scroll = ScrollView(ctx).apply { setBackgroundColor(cBg) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 24.dp(), 20.dp(), 24.dp())
            setBackgroundColor(cBg)
        }
        scroll.addView(root)

        // Header
        root.addView(TextView(ctx).apply {
            text = "TORRENTS V1"
            textSize = 24f; setTextColor(cAccent); gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD); letterSpacing = 0.05f
        })
        root.addView(TextView(ctx).apply {
            text = "Tap a section to configure"
            textSize = 12f; setTextColor(cTextDim); gravity = Gravity.CENTER
            setPadding(0, 2.dp(), 0, 16.dp())
        })

        root.addView(sectionButton(ctx, "CATALOGS", "AniList, TMDB, Cinemeta", dp) {
            showCatalogDialog(ctx, dp)
        })

        root.addView(sectionButton(ctx, "TORRENT SOURCES", "Torrentio, TorrentsDB, Animetosho", dp) {
            showSourcesDialog(ctx, dp)
        })

        root.addView(sectionButton(ctx, "DEBRID SERVICE", "Provider & API key", dp) {
            showDebridDialog(ctx, dp)
        })

        root.addView(sectionButton(ctx, "STREMIO ADDONS", "Custom addon URLs", dp) {
            showAddonsDialog(ctx, dp)
        })

        root.addView(Button(ctx).apply {
            text = "CLOSE"
            setTextColor(cTextSub); textSize = 14f
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, 16.dp(), 0, 0)
            setOnClickListener { dismiss() }
        })

        return scroll
    }

    private fun sectionButton(
        ctx: Context, title: String, subtitle: String, dp: Float, onClick: () -> Unit
    ): LinearLayout {
        val density = resources.displayMetrics.density
            fun Int.dp() = (this * density).toInt()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())
            background = GradientDrawable().apply {
                setStroke(1, cCardBorder); cornerRadius = 14 * dp; setColor(cCard)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 10.dp()
            }
            isClickable = true; isFocusable = true

            val textCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(TextView(ctx).apply {
                this.text = title; textSize = 16f; setTextColor(cText)
                setTypeface(typeface, android.graphics.Typeface.BOLD); letterSpacing = 0.04f
            })
            textCol.addView(TextView(ctx).apply {
                this.text = subtitle; textSize = 12f; setTextColor(cTextDim)
                setPadding(0, 2.dp(), 0, 0)
            })
            addView(textCol)
            addView(TextView(ctx).apply {
                text = "→"; textSize = 20f; setTextColor(cAccent)
            })

            setOnClickListener { onClick() }
        }
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun showCatalogDialog(ctx: Context, dp: Float) {
        val density = resources.displayMetrics.density
            fun Int.dp() = (this * density).toInt()
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 16.dp(), 20.dp(), 8.dp())
            setBackgroundColor(cBg)
        }
        layout.addView(sectionLabel(ctx, "Catalogs", dp))
        layout.addView(toggleRow(ctx, "AniList", "Anime catalog", KEY_ANILIST, true, dp))
        layout.addView(toggleRow(ctx, "TMDB", "Movies & TV Series", KEY_TMDB, true, dp))
        layout.addView(toggleRow(ctx, "Cinemeta", "Stremio metadata", KEY_CINEMETA, false, dp))

        AlertDialog.Builder(ctx).setView(layout)
            .setPositiveButton("Save") { _, _ -> reloadPlugin() }
            .setNegativeButton("Cancel", null)
            .create().apply { show(); styleButtons() }
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun showSourcesDialog(ctx: Context, dp: Float) {
        val density = resources.displayMetrics.density
            fun Int.dp() = (this * density).toInt()
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 16.dp(), 20.dp(), 8.dp())
            setBackgroundColor(cBg)
        }
        layout.addView(sectionLabel(ctx, "Torrent Sources", dp))
        layout.addView(toggleRow(ctx, "Torrentio", "Main torrent provider", KEY_TORRENTIO, true, dp))
        layout.addView(toggleRow(ctx, "TorrentsDB", "Alternative provider", KEY_TORRENTSDB, true, dp))
        layout.addView(toggleRow(ctx, "Animetosho", "Anime-specific", KEY_ANIMETOSHO, true, dp))

        AlertDialog.Builder(ctx).setView(layout)
            .setPositiveButton("Save") { _, _ -> reloadPlugin() }
            .setNegativeButton("Cancel", null)
            .create().apply { show(); styleButtons() }
    }

    private fun showDebridDialog(ctx: Context, dp: Float) {
        val density = resources.displayMetrics.density
            fun Int.dp() = (this * density).toInt()
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 16.dp(), 20.dp(), 8.dp())
            setBackgroundColor(cBg)
        }
        layout.addView(sectionLabel(ctx, "Debrid Service", dp))
        layout.addView(TextView(ctx).apply {
            text = "Resolve torrents to direct streams"
            textSize = 12f; setTextColor(cTextDim); setPadding(0, 0, 0, 8.dp())
        })

        // Provider
        layout.addView(TextView(ctx).apply {
            text = "Provider"; textSize = 14f; setTextColor(cTextSub); setPadding(0, 4.dp(), 0, 2.dp())
        })
        val providers = listOf("None", "RealDebrid", "Premiumize", "AllDebrid", "DebridLink", "EasyDebrid", "Offcloud", "TorBox", "Put.io")
        val spinner = Spinner(ctx).apply {
            adapter = darkSpinnerAdapter(ctx, providers)
            val saved = getStringSetting(KEY_DEBRID_PROVIDER)
            if (saved.isNotBlank()) { val pos = providers.indexOf(saved); if (pos >= 0) setSelection(pos) }
        }
        layout.addView(spinner)

        // API Key
        layout.addView(TextView(ctx).apply {
            text = "API Key"; textSize = 14f; setTextColor(cTextSub); setPadding(0, 10.dp(), 0, 2.dp())
        })
        val keyInput = EditText(ctx).apply {
            hint = "Enter API key"; setHintTextColor(cTextDim); setTextColor(cText)
            setBackgroundColor(cCard); setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            setText(getStringSetting(KEY_DEBRID_KEY))
        }
        layout.addView(keyInput)

        AlertDialog.Builder(ctx).setView(layout)
            .setPositiveButton("Save") { _, _ ->
                setStringSetting(KEY_DEBRID_PROVIDER, spinner.selectedItem?.toString() ?: "None")
                setStringSetting(KEY_DEBRID_KEY, keyInput.text?.toString() ?: "")
                reloadPlugin()
            }
            .setNegativeButton("Cancel", null)
            .create().apply { show(); styleButtons() }
    }

    private fun showAddonsDialog(ctx: Context, dp: Float) {
        val density = resources.displayMetrics.density
            fun Int.dp() = (this * density).toInt()
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 16.dp(), 20.dp(), 8.dp())
            setBackgroundColor(cBg)
        }
        layout.addView(sectionLabel(ctx, "Stremio Addons", dp))
        layout.addView(TextView(ctx).apply {
            text = "Add custom Stremio addon URLs"
            textSize = 12f; setTextColor(cTextDim); setPadding(0, 0, 0, 8.dp())
        })

        val addonsContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(addonsContainer)

        val btnAdd = Button(ctx).apply {
            text = "+ ADD ADDON"; setTextColor(cAccent); setBackgroundColor(Color.TRANSPARENT)
            textSize = 13f; setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(btnAdd)

        fun refresh() { refreshAddonList(addonsContainer, dp) }
        btnAdd.setOnClickListener { showAddAddonDialog(ctx, dp) { refresh() } }
        refresh()

        AlertDialog.Builder(ctx).setView(layout)
            .setPositiveButton("Done") { _, _ -> showRestartDialog(ctx) }
            .setNegativeButton("Cancel", null)
            .create().apply { show(); styleButtons() }
    }

    private fun showAddAddonDialog(ctx: Context, dp: Float, onAdded: () -> Unit) {
        val density = resources.displayMetrics.density
            fun Int.dp() = (this * density).toInt()
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 12.dp(), 20.dp(), 0); setBackgroundColor(cBg)
        }

        layout.addView(TextView(ctx).apply { text = "Name"; textSize = 14f; setTextColor(cAccent); setPadding(0, 4.dp(), 0, 2.dp()) })
        val etName = EditText(ctx).apply { hint = "Addon name"; setHintTextColor(cTextDim); setTextColor(cText); setBackgroundColor(cCard); setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp()) }
        layout.addView(etName)

        layout.addView(TextView(ctx).apply { text = "URL"; textSize = 14f; setTextColor(cAccent); setPadding(0, 10.dp(), 0, 2.dp()) })
        val etUrl = EditText(ctx).apply { hint = "https://addon.example.com/manifest.json"; setHintTextColor(cTextDim); setTextColor(cText); setBackgroundColor(cCard); setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp()) }
        layout.addView(etUrl)

        layout.addView(TextView(ctx).apply { text = "Type"; textSize = 14f; setTextColor(cAccent); setPadding(0, 10.dp(), 0, 2.dp()) })
        val typeSpinner = Spinner(ctx).apply { adapter = darkSpinnerAdapter(ctx, listOf("HTTPS", "TORRENT", "DEBRID", "SUBTITLE")) }
        layout.addView(typeSpinner)

        AlertDialog.Builder(ctx).setTitle("Add Stremio Addon").setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val name = etName.text?.toString()?.trim() ?: ""
                val url = etUrl.text?.toString()?.trim() ?: ""
                val type = typeSpinner.selectedItem?.toString() ?: "HTTPS"
                if (name.isNotBlank() && url.isNotBlank()) {
                    val addons = getStremioAddons().toMutableList()
                    addons.add(StremioAddon(name, url, type))
                    saveStremioAddons(addons)
                    onAdded()
                }
            }
            .setNegativeButton("Cancel", null)
            .create().apply { show(); styleButtons() }
    }

    private fun reloadPlugin() {
        try { MainActivity.reloadHomeEvent.invoke(true) } catch (_: Throwable) {}
    }

    private fun showRestartDialog(ctx: Context) {
        AlertDialog.Builder(ctx)
            .setTitle("Restart Required")
            .setMessage("Changes have been saved. Do you want to restart the app to apply them?")
            .setPositiveButton("Yes") { _, _ -> restartApp() }
            .setNegativeButton("No") { _, _ -> reloadPlugin() }
            .show()
    }

    private fun restartApp() {
        try {
            val context = requireContext().applicationContext
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(context.packageName)
            val componentName = intent?.component
            if (componentName != null) {
                val restartIntent = android.content.Intent.makeRestartActivityTask(componentName)
                context.startActivity(restartIntent)
                Runtime.getRuntime().exit(0)
            }
        } catch (_: Throwable) {
            reloadPlugin()
        }
    }

    private fun sectionLabel(ctx: Context, text: String, dp: Float): TextView {
        return TextView(ctx).apply {
            this.text = text; textSize = 18f; setTextColor(cAccent)
            setPadding(0, 0, 0, (12 * dp).toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD); letterSpacing = 0.04f
        }
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun toggleRow(
        ctx: Context, title: String, subtitle: String,
        key: String, default: Boolean, dp: Float
    ): LinearLayout {
        val density = resources.displayMetrics.density
            fun Int.dp() = (this * density).toInt()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10.dp(), 0, 10.dp())

            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(ctx).apply { this.text = title; textSize = 16f; setTextColor(cText) })
            col.addView(TextView(ctx).apply {
                this.text = subtitle; textSize = 12f; setTextColor(cTextDim); setPadding(0, 2.dp(), 0, 0)
            })
            addView(col)

            addView(SwitchCompat(ctx).apply {
                isChecked = getSetting(key, default)
                trackTintList = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(cAccent, Color.parseColor("#333333"))
                )
                thumbTintList = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(Color.WHITE, Color.parseColor("#666666"))
                )
                setOnCheckedChangeListener { _, checked -> setSetting(key, checked) }
            })
        }
    }

    private fun refreshAddonList(container: LinearLayout, dp: Float) {
        container.removeAllViews()
        val addons = getStremioAddons()
        val density = resources.displayMetrics.density
            fun Int.dp() = (this * density).toInt()
        if (addons.isEmpty()) {
            container.addView(TextView(container.context).apply {
                text = "No addons yet"; textSize = 13f; setTextColor(cTextDim)
                setPadding(0, 12.dp(), 0, 12.dp()); gravity = Gravity.CENTER
            })
            return
        }
        for ((index, addon) in addons.withIndex()) {
            val ctx = container.context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(12.dp(), 8.dp(), 8.dp(), 8.dp())
                background = GradientDrawable().apply {
                    setStroke(1, cCardBorder); cornerRadius = 8 * dp; setColor(Color.parseColor("#0F0F0F"))
                }
                val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                params.bottomMargin = 6.dp(); layoutParams = params
            }
            val icon = when (addon.type.uppercase()) { "TORRENT" -> "🧲"; "DEBRID" -> "☁️"; "SUBTITLE" -> "📝"; else -> "🔌" }
            row.addView(TextView(ctx).apply {
                text = "$icon  ${addon.name}\n${addon.url}"; textSize = 13f; setTextColor(cText)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(ImageButton(ctx).apply {
                setImageResource(android.R.drawable.ic_menu_delete)
                setBackgroundColor(Color.TRANSPARENT); setColorFilter(cAccent)
                setOnClickListener {
                    val updated = addons.toMutableList(); updated.removeAt(index)
                    saveStremioAddons(updated); refreshAddonList(container, dp)
                }
            })
            container.addView(row)
        }
    }

    private fun darkSpinnerAdapter(ctx: Context, items: List<String>): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_dropdown_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(cText); textSize = 14f
                }
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getDropDownView(position, convertView, parent) as TextView).apply {
                    setTextColor(cText); setBackgroundColor(cCard)
                    setPadding((12 * resources.displayMetrics.density).toInt(),
                        (10 * resources.displayMetrics.density).toInt(),
                        (12 * resources.displayMetrics.density).toInt(),
                        (10 * resources.displayMetrics.density).toInt())
                }
            }
        }
    }

    private fun AlertDialog.styleButtons() {
        getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(cAccent)
        getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(cTextDim)
    }
    private fun Float.dp() = (this * resources.displayMetrics.density).toInt()
    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
}
