package com.justplay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
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
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.MainActivity

class JustPlaySettingsFragment : DialogFragment() {

    private val cText = Color.parseColor("#FFFFFF")
    private val cSub = Color.parseColor("#9AA4B8")
    private val cDim = Color.parseColor("#5C677D")
    private val cAccent = Color.parseColor("#3B6CFF")
    private val cAccentDeep = Color.parseColor("#1E46D6")

    private data class SiteRow(val id: String, val label: String, val sub: String)

    private val sites = listOf(
        SiteRow("netnaija", "NetNaija", "Direct mp4 streams"),
        SiteRow("vegamovies", "VegaMovies", "Dual audio movies and series"),
        SiteRow("hdhub4u", "HDHub4u", "Movies and series"),
        SiteRow("4khdhub", "4KHDHub", "UHD movies and packs"),
        SiteRow("themoviesflix", "TheMoviesFlix", "Movies and web series"),
        SiteRow("multimovies", "Multimovies", "Streaming servers"),
        SiteRow("movies4u", "Movies4u", "Movies and series")
    )

    private val pending = HashSet<String>()
    private val switches = HashMap<String, SwitchCompat>()
    private var listContainer: LinearLayout? = null
    private var countView: TextView? = null

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            val dm = resources.displayMetrics
            val maxW = (430 * dm.density).toInt()
            val w = if (dm.widthPixels > maxW) maxW else (dm.widthPixels * 0.94f).toInt()
            val h = (dm.heightPixels * 0.84f).toInt()
            setLayout(w, h)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
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

        sites.forEach { site ->
            if (!JustPlay.siteEnabled(site.id)) pending.add(site.id)
        }

        val scroll = ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22.dp(), 26.dp(), 22.dp(), 20.dp())
            background = glassBackground(d)
        }
        scroll.addView(root)

        root.addView(TextView(ctx).apply {
            text = "JUSTPLAY"
            textSize = 26f; setTextColor(cAccent); gravity = Gravity.START
            setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.08f
        })
        root.addView(TextView(ctx).apply {
            text = "S O U R C E   M A N A G E R"
            textSize = 11f; setTextColor(cDim); gravity = Gravity.START
            setPadding(0, 3.dp(), 0, 18.dp())
        })

        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(4.dp(), 0, 4.dp(), 10.dp())
            addView(TextView(ctx).apply {
                text = "SITES"
                textSize = 11f; setTextColor(cSub); setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.06f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            countView = TextView(ctx).apply {
                textSize = 11f; setTextColor(cAccent); setTypeface(typeface, Typeface.BOLD)
                background = GradientDrawable().apply {
                    setStroke(1, Color.argb(0x30, 0x3B, 0x6C, 0xFF)); cornerRadius = 12 * d
                    setColor(Color.argb(0x1A, 0x3B, 0x6C, 0xFF))
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
            background = glassPane(d, 16f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16.dp() }
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
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 10.dp() }
            setOnClickListener { save(ctx) }
        })

        root.addView(TextView(ctx).apply {
            text = "Changes apply after the app restarts"
            textSize = 11f; setTextColor(cDim); gravity = Gravity.CENTER
            setPadding(0, 10.dp(), 0, 0)
        })

        return scroll
    }

    private fun save(ctx: Context) {
        sites.forEach { site ->
            try {
                CloudStreamApp.setKey("JUSTPLAY_SITE_${site.id}", site.id !in pending)
            } catch (_: Exception) {}
        }
        AlertDialog.Builder(ctx)
            .setTitle("Restart Required")
            .setMessage("Sources saved. Restart CloudStream now to apply them?")
            .setPositiveButton("Restart") { _, _ -> restartApp() }
            .setNegativeButton("Later") { _, _ ->
                try {
                    MainActivity.reloadHomeEvent.invoke(true)
                } catch (_: Throwable) {}
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
        } catch (_: Throwable) {}
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun rebuildList(ctx: Context, d: Float) {
        fun Int.dp() = (this * d).toInt()
        val list = listContainer ?: return
        list.removeAllViews()
        switches.clear()

        sites.forEach { site ->
            val switch = SwitchCompat(ctx).apply {
                isChecked = site.id !in pending
                trackTintList = ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_checked),
                        intArrayOf()
                    ),
                    intArrayOf(cAccent, Color.argb(0xFF, 0x2A, 0x31, 0x45))
                )
                thumbTintList = ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_checked),
                        intArrayOf()
                    ),
                    intArrayOf(Color.WHITE, Color.argb(0xFF, 0x7A, 0x84, 0x9C))
                )
                setOnCheckedChangeListener { _, checked ->
                    if (checked) pending.remove(site.id) else pending.add(site.id)
                    updateCount()
                }
            }
            switches[site.id] = switch

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(16.dp(), 12.dp(), 14.dp(), 12.dp())
                background = glassPane(d, 16f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 8.dp() }
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(ctx).apply {
                        text = site.label; textSize = 15f; setTextColor(cText)
                        setTypeface(typeface, Typeface.BOLD)
                    })
                    addView(TextView(ctx).apply {
                        text = site.sub; textSize = 11f; setTextColor(cDim)
                        setPadding(0, 2.dp(), 0, 0)
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
        val on = sites.count { it.id !in pending }
        countView?.text = "$on/${sites.size} ON"
    }

    private fun glassPane(d: Float, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        orientation = GradientDrawable.Orientation.TOP_BOTTOM
        colors = intArrayOf(
            Color.argb(0x20, 0xFF, 0xFF, 0xFF),
            Color.argb(0x0C, 0xFF, 0xFF, 0xFF)
        )
        setStroke(1, Color.argb(0x28, 0xFF, 0xFF, 0xFF))
        cornerRadius = radiusDp * d
    }

    private fun glassBackground(d: Float): LayerDrawable = LayerDrawable(arrayOf(
        GradientDrawable().apply {
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            colors = intArrayOf(Color.parseColor("#070A12"), Color.parseColor("#0D1322"))
        },
        GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = 280f * d
            setGradientCenter(0.15f, 0.05f)
            colors = intArrayOf(Color.argb(0x26, 0x3B, 0x6C, 0xFF), Color.TRANSPARENT)
        },
        GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = 320f * d
            setGradientCenter(0.9f, 0.95f)
            colors = intArrayOf(Color.argb(0x20, 0x7A, 0x5C, 0xFF), Color.TRANSPARENT)
        }
    ))
}
