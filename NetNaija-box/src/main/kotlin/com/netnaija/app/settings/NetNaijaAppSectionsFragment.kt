package com.netnaija.app.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.DialogFragment
import com.lagradost.cloudstream3.MainActivity
import com.netnaija.app.NetNaijaApp
import org.json.JSONArray

class NetNaijaAppSectionsFragment(private val sharedPref: SharedPreferences) : DialogFragment() {

    private val cText = Color.parseColor("#F4F4F6")
    private val cSub = Color.parseColor("#8F8F98")
    private val cDim = Color.parseColor("#5C5C66")
    private val cAccent = Color.parseColor("#E50914")
    private val cAccentDeep = Color.parseColor("#B91C1C")
    private val cOnDark = Color.parseColor("#130D10")

    private val order = ArrayList<Pair<String, String>>()
    private val hidden = HashSet<String>()
    private var listContainer: LinearLayout? = null
    private var countView: TextView? = null

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

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: android.os.Bundle?
    ): View {
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        fun Int.dp() = (this * d).toInt()

        order.addAll(NetNaijaApp.orderedSections(sharedPref))
        hidden.addAll(NetNaijaApp.hiddenSections(sharedPref))

        val scroll = ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(), 26.dp(), 24.dp(), 20.dp())
            background = GradientDrawable().apply {
                setColor(cOnDark)
                cornerRadius = 28 * d
                setStroke(d.toInt(), Color.parseColor("#3A1E23"))
            }
        }
        scroll.addView(root)

        root.addView(TextView(ctx).apply {
            text = "NETNAIJA-BOX"
            textSize = 11f; setTextColor(Color.parseColor("#FF2E3B"))
            setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.24f
        })
        root.addView(TextView(ctx).apply {
            text = "Home Sections"
            textSize = 26f; setTextColor(Color.parseColor("#FAFAFB"))
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(ctx).apply {
            text = "Pick the rows on your home page and their order"
            textSize = 12.5f; setTextColor(cSub)
            setPadding(0, 3.dp(), 0, 18.dp())
        })

        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(6.dp(), 0, 6.dp(), 10.dp())
            addView(TextView(ctx).apply {
                text = "SECTIONS"
                textSize = 11f; setTextColor(cSub); setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            countView = TextView(ctx).apply {
                textSize = 11f; setTextColor(Color.parseColor("#FF6B74"))
                setTypeface(typeface, Typeface.BOLD)
                background = GradientDrawable().apply {
                    setStroke(1, Color.argb(0x48, 0xE5, 0x09, 0x14)); cornerRadius = 12 * d
                    setColor(Color.argb(0x24, 0xE5, 0x09, 0x14))
                }
                setPadding(10.dp(), 4.dp(), 10.dp(), 4.dp())
            }
            addView(countView)
        })

        listContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)
        rebuildList(ctx, d)

        root.addView(Button(ctx).apply {
            text = "RESET TO DEFAULT"
            setTextColor(cAccent); textSize = 13f; setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.04f
            setPadding(0, 13.dp(), 0, 13.dp())
            stateListAnimator = null
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(d.toInt(), Color.parseColor("#4A252A"))
                cornerRadius = 16 * d
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 16.dp() }
            setOnClickListener {
                AlertDialog.Builder(ctx)
                    .setTitle("Reset to Default")
                    .setMessage("Restore the default order and show every section?")
                    .setPositiveButton("Reset") { _, _ ->
                        order.clear()
                        order.addAll(NetNaijaApp.sectionCatalog)
                        hidden.clear()
                        rebuildList(ctx, d)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })

        root.addView(Button(ctx).apply {
            text = "SAVE SECTIONS"
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
            text = "Switch a section off to hide it, arrows move it up or down"
            textSize = 10.5f; setTextColor(cDim); gravity = Gravity.CENTER
            setPadding(0, 10.dp(), 0, 0)
        })

        return scroll
    }

    private fun save(ctx: Context) {
        try {
            val savedOrder = JSONArray()
            order.forEach { savedOrder.put(it.first) }
            sharedPref.edit()
                .putString(NetNaijaApp.PREF_SECTION_ORDER, savedOrder.toString())
                .putString(NetNaijaApp.PREF_SECTION_HIDDEN, hidden.joinToString(","))
                .apply()
        } catch (e: Exception) {
            Toast.makeText(ctx, "Could not save the section layout", Toast.LENGTH_SHORT).show()
            return
        }
        try { MainActivity.reloadHomeEvent.invoke(true) } catch (_: Throwable) {}
        Toast.makeText(ctx, "Home page updated", Toast.LENGTH_SHORT).show()
        dismiss()
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun rebuildList(ctx: Context, d: Float) {
        fun Int.dp() = (this * d).toInt()
        val list = listContainer ?: return
        list.removeAllViews()

        order.forEachIndexed { index, (key, name) ->
            val isHidden = key in hidden
            val label = TextView(ctx).apply {
                text = name; textSize = 15f
                setTextColor(if (isHidden) cDim else cText)
                setTypeface(typeface, Typeface.BOLD)
            }

            val switch = SwitchCompat(ctx).apply {
                isChecked = !isHidden
                trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(cAccent, Color.parseColor("#2A1A1D"))
                )
                thumbTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(Color.WHITE, Color.parseColor("#7A7A82"))
                )
                setOnCheckedChangeListener { _, checked ->
                    if (checked) hidden.remove(key) else hidden.add(key)
                    label.setTextColor(if (checked) cText else cDim)
                    updateCount()
                }
            }

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(12.dp(), 9.dp(), 8.dp(), 9.dp())
                background = rowBackground(d, 18f)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8.dp() }
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(label)
                })
                addView(arrowButton(ctx, d, "\u25B2", index > 0) { move(index, -1, ctx, d) })
                addView(arrowButton(ctx, d, "\u25BC", index < order.size - 1) { move(index, 1, ctx, d) })
                addView(switch)
                setOnClickListener { switch.toggle() }
            }
            list.addView(row)
        }
        updateCount()
    }

    private fun move(from: Int, delta: Int, ctx: Context, d: Float) {
        val to = from + delta
        if (to < 0 || to >= order.size) return
        val item = order.removeAt(from)
        order.add(to, item)
        rebuildList(ctx, d)
    }

    private fun arrowButton(
        ctx: Context,
        d: Float,
        glyph: String,
        enabled: Boolean,
        onClick: () -> Unit
    ): TextView {
        return TextView(ctx).apply {
            text = glyph
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(if (enabled) cAccent else cDim)
            isEnabled = enabled
            isClickable = enabled
            isFocusable = enabled
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(1, if (enabled) Color.parseColor("#4A252A") else Color.parseColor("#2A161A"))
                cornerRadius = 12 * d
            }
            layoutParams = LinearLayout.LayoutParams((34 * d).toInt(), (34 * d).toInt()).apply {
                leftMargin = (2 * d).toInt(); rightMargin = (2 * d).toInt()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun updateCount() {
        val on = order.count { it.first !in hidden }
        countView?.text = "$on/${order.size} ON"
    }

    private fun rowBackground(d: Float, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor("#1B1214"))
        setStroke(1, Color.parseColor("#33191E"))
        cornerRadius = radiusDp * d
    }
}
