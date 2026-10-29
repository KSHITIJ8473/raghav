package com.netnaija.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.DialogFragment
import com.lagradost.api.Log
import com.lagradost.cloudstream3.MainActivity
import com.netnaija.NetNaijaSources

class SourceSettingsFragment : DialogFragment() {

    private val cText = Color.parseColor("#F4F4F6")
    private val cSub = Color.parseColor("#8F8F98")
    private val cDim = Color.parseColor("#5C5C66")
    private val cAccent = Color.parseColor("#0A84FF")
    private val cAccentDeep = Color.parseColor("#0060DF")
    private val cOnDark = Color.parseColor("#16161A")

    private val pending = HashSet<String>()
    private val switches = HashMap<String, SwitchCompat>()
    private var listContainer: LinearLayout? = null
    private var countView: TextView? = null
    private var query = ""

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            val dm = resources.displayMetrics
            val maxW = (500 * dm.density).toInt()
            val w = if (dm.widthPixels > maxW) maxW else (dm.widthPixels * 0.94f).toInt()
            val h = (dm.heightPixels * 0.84f).toInt()
            setLayout(w, h)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    override fun onCreateView(inflater: android.view.LayoutInflater, container: ViewGroup?, savedInstanceState: android.os.Bundle?): View {
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        fun Int.dp() = (this * d).toInt()

        pending.addAll(NetNaijaSources.disabled())

        val scroll = ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(), 26.dp(), 24.dp(), 20.dp())
            background = GradientDrawable().apply {
                setColor(cOnDark)
                cornerRadius = 28 * d
                setStroke(d.toInt(), Color.parseColor("#30FFFFFF"))
            }
        }
        scroll.addView(root)

        root.addView(TextView(ctx).apply {
            text = "NETNAIJA"
            textSize = 11f; setTextColor(Color.parseColor("#6EB5FF"))
            setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.24f
        })
        root.addView(TextView(ctx).apply {
            text = "Sources"
            textSize = 26f; setTextColor(Color.parseColor("#FAFAFB"))
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(ctx).apply {
            text = "Choose which audio tracks load"
            textSize = 12.5f; setTextColor(cSub)
            setPadding(0, 3.dp(), 0, 18.dp())
        })

        val search = EditText(ctx).apply {
            hint = "Search sources"; setHintTextColor(cDim); textSize = 14f
            setTextColor(cText); setSingleLine()
            setPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())
            background = rowBackground(d, 16f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString()?.trim().orEmpty().lowercase()
                    rebuildList(ctx, d)
                }
            })
        }
        root.addView(search)
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(6.dp(), 14.dp(), 6.dp(), 10.dp())
            addView(TextView(ctx).apply {
                text = "AUDIO TRACKS & HARDSUBS"
                textSize = 11f; setTextColor(cSub); setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            countView = TextView(ctx).apply {
                textSize = 11f; setTextColor(Color.parseColor("#6EB5FF"))
                setTypeface(typeface, Typeface.BOLD)
                background = GradientDrawable().apply {
                    setStroke(1, Color.argb(0x48, 0x0A, 0x84, 0xFF)); cornerRadius = 12 * d
                    setColor(Color.argb(0x24, 0x0A, 0x84, 0xFF))
                }
                setPadding(10.dp(), 4.dp(), 10.dp(), 4.dp())
            }
            addView(countView)
        })

        listContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)
        rebuildList(ctx, d)

        root.addView(Button(ctx).apply {
            text = "ENABLE ALL"
            setTextColor(cAccent); textSize = 13f; setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.04f
            setPadding(0, 13.dp(), 0, 13.dp())
            stateListAnimator = null
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(d.toInt(), Color.parseColor("#2EFFFFFF"))
                cornerRadius = 16 * d
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 16.dp() }
            setOnClickListener {
                pending.clear()
                switches.values.forEach { it.isChecked = true }
                updateCount()
                Toast.makeText(ctx, "All sources enabled", Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(Button(ctx).apply {
            text = "SAVE & RESTART"
            setTextColor(Color.WHITE); textSize = 15f; setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.03f
            setPadding(0, 15.dp(), 0, 15.dp())
            stateListAnimator = null
            background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                colors = intArrayOf(cAccent, cAccentDeep)
                cornerRadius = 16 * d
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 10.dp() }
            setOnClickListener { save(ctx) }
        })

        root.addView(TextView(ctx).apply {
            text = "Changes apply after the app restarts"
            textSize = 10.5f; setTextColor(cDim); gravity = Gravity.CENTER
            setPadding(0, 10.dp(), 0, 0)
        })

        return scroll
    }

    private fun save(ctx: Context) {
        NetNaijaSources.setDisabled(pending)
        AlertDialog.Builder(ctx)
            .setTitle("Restart Required")
            .setMessage("Sources saved. Restart CloudStream now to apply them?")
            .setPositiveButton("Restart") { _, _ -> restartApp() }
            .setNegativeButton("Later") { _, _ ->
                try { MainActivity.reloadHomeEvent.invoke(true) } catch (e: Throwable) { Log.d("NetNaija", "reload event failed: ${e.message}") }
                dismiss()
            }
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
            try { MainActivity.reloadHomeEvent.invoke(true) } catch (e: Throwable) { Log.d("NetNaija", "reload event failed: ${e.message}") }
        }
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun rebuildList(ctx: Context, d: Float) {
        fun Int.dp() = (this * d).toInt()
        val list = listContainer ?: return
        list.removeAllViews()
        switches.clear()

        val sources = NetNaijaSources.knownSources()
            .filter { query.isEmpty() || it.lowercase().contains(query) }

        if (sources.isEmpty()) {
            list.addView(TextView(ctx).apply {
                text = "No sources match \"$query\""
                textSize = 13f; setTextColor(cDim); gravity = Gravity.CENTER
                setPadding(0, 22.dp(), 0, 22.dp())
            })
            return
        }

        sources.forEach { label ->
            val switch = SwitchCompat(ctx).apply {
                isChecked = label !in pending
                trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(Color.parseColor("#34C759"), Color.parseColor("#3A3A3C"))
                )
                thumbTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(Color.WHITE, Color.parseColor("#D1D1D6"))
                )
                setOnCheckedChangeListener { _, checked ->
                    if (checked) pending.remove(label) else pending.add(label)
                    updateCount()
                }
            }
            switches[label] = switch

            val kind = when {
                label == "Original" -> "Main audio track"
                label.endsWith("Hardsub") -> "Burned-in subtitles"
                else -> "Dubbed audio"
            }

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(16.dp(), 13.dp(), 14.dp(), 13.dp())
                background = rowBackground(d, 18f)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8.dp() }
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(ctx).apply {
                        text = label; textSize = 15f; setTextColor(cText); setTypeface(typeface, Typeface.BOLD)
                    })
                    addView(TextView(ctx).apply {
                        text = kind; textSize = 11f; setTextColor(cDim); setPadding(0, 2.dp(), 0, 0)
                    })
                })
                addView(switch)
                setOnClickListener { switch.toggle() }
            }
            list.addView(row)
        }
        updateCount()
    }

    private fun updateCount() {
        val sources = NetNaijaSources.knownSources()
        val on = sources.count { it !in pending }
        countView?.text = "$on/${sources.size} ON"
    }

    private fun rowBackground(d: Float, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor("#1A1A1E"))
        setStroke(1, Color.parseColor("#1AFFFFFF"))
        cornerRadius = radiusDp * d
    }
}
