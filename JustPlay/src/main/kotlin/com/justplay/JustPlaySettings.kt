package com.justplay

import android.animation.ObjectAnimator
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.MainActivity

object JustPlaySettings {

    private const val BG = 0xFF0B0506.toInt()
    private const val SURFACE = 0xFF150F11.toInt()
    private const val SURFACE_2 = 0xFF201518.toInt()
    private const val BORDER = 0xFF2E1C1F.toInt()
    private const val BORDER_HI = 0xFF4A272C.toInt()
    private const val TEXT = 0xFFF8EDEE.toInt()
    private const val SUBTEXT = 0xFF9D8488.toInt()
    private const val RED = 0xFFE53935.toInt()
    private const val RED_BRIGHT = 0xFFFF5A52.toInt()
    private const val RED_DEEP = 0xFF8E1418.toInt()

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    private fun shape(
        color: Int, radiusDp: Int, ctx: Context,
        strokeDp: Int = 0, strokeColor: Int = 0
    ): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(ctx, radiusDp).toFloat()
        if (strokeDp > 0) setStroke(dp(ctx, strokeDp), strokeColor)
    }

    private fun themedSwitch(ctx: Context): SwitchCompat = SwitchCompat(ctx).apply {
        trackTintList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(RED_DEEP, 0xFF231316.toInt())
        )
        thumbTintList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(RED_BRIGHT, 0xFF6E5A5D.toInt())
        )
    }

    private fun stagger(v: View, i: Int, base: Long = 60L) {
        v.alpha = 0f
        v.translationY = 30f
        v.animate().alpha(1f).translationY(0f)
            .setStartDelay(base * i).setDuration(400)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun slideIn(v: View, i: Int, base: Long = 90L) {
        v.animate().alpha(1f).translationX(0f)
            .setStartDelay(base + 80L * i).setDuration(430)
            .setInterpolator(OvershootInterpolator(0.9f)).start()
    }

    class LogoMark(context: Context) : View(context) {
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) / 2f - width * 0.05f
            ring.color = RED
            ring.strokeWidth = r * 0.15f
            canvas.drawCircle(cx, cy, r, ring)
            fill.shader = LinearGradient(
                cx - r, cy - r, cx + r, cy + r,
                RED_BRIGHT, RED_DEEP, Shader.TileMode.CLAMP
            )
            val s = r * 0.6f
            val tri = Path()
            tri.moveTo(cx - s * 0.55f, cy - s)
            tri.lineTo(cx - s * 0.55f, cy + s)
            tri.lineTo(cx + s * 0.85f, cy)
            tri.close()
            canvas.drawPath(tri, fill)
        }
    }

    private fun statusPill(ctx: Context, label: String, withDot: Boolean): LinearLayout {
        val pill = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(SURFACE, 20, ctx, 1, BORDER_HI)
            setPadding(dp(ctx, 12), dp(ctx, 7), dp(ctx, 12), dp(ctx, 7))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(ctx, 8) }
        }
        if (withDot) {
            pill.addView(View(ctx).apply { background = shape(RED_BRIGHT, 4, ctx) },
                LinearLayout.LayoutParams(dp(ctx, 7), dp(ctx, 7)).apply {
                    rightMargin = dp(ctx, 7)
                })
        }
        pill.addView(TextView(ctx).apply {
            text = label; setTextColor(TEXT); textSize = 9.5f
            setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.14f
        })
        return pill
    }

    private fun sectionHeader(ctx: Context, title: String): LinearLayout {
        val h = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        h.addView(View(ctx).apply { background = shape(RED, 2, ctx) },
            LinearLayout.LayoutParams(dp(ctx, 18), dp(ctx, 3)).apply {
                rightMargin = dp(ctx, 10)
            })
        h.addView(TextView(ctx).apply {
            text = title; setTextColor(SUBTEXT); textSize = 11f
            setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.18f
        })
        h.addView(View(ctx).apply { background = shape(BORDER, 1, ctx) },
            LinearLayout.LayoutParams(0, dp(ctx, 1), 1f).apply { leftMargin = dp(ctx, 12) })
        return h
    }

    private fun homeRow(
        ctx: Context,
        title: String,
        sub: String,
        onClick: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(SURFACE, 16, ctx, 1, BORDER)
            setPadding(dp(ctx, 16), dp(ctx, 18), dp(ctx, 16), dp(ctx, 18))
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 12) }
        }

        val bar = View(ctx).apply {
            background = shape(RED, 2, ctx)
            scaleY = 0.55f
        }
        row.addView(bar, LinearLayout.LayoutParams(dp(ctx, 4), dp(ctx, 42)).apply {
            rightMargin = dp(ctx, 15)
        })

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(ctx).apply {
            text = title
            setTextColor(TEXT); textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.02f
        })
        col.addView(TextView(ctx).apply {
            text = sub
            setTextColor(SUBTEXT); textSize = 11.5f
            setPadding(0, dp(ctx, 4), dp(ctx, 8), 0)
        })
        row.addView(col)

        val chev = FrameLayout(ctx).apply {
            background = shape(SURFACE_2, 17, ctx, 1, BORDER_HI)
        }
        chev.addView(TextView(ctx).apply {
            text = "›"; setTextColor(RED); textSize = 20f
            setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(dp(ctx, 34), dp(ctx, 34)))
        row.addView(chev)

        row.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    v.animate().scaleX(0.985f).scaleY(0.985f).setDuration(110)
                        .setInterpolator(DecelerateInterpolator()).start()
                    bar.animate().scaleY(1f).setDuration(190)
                        .setInterpolator(OvershootInterpolator()).start()
                    chev.animate().translationX(dp(ctx, 3).toFloat()).setDuration(140).start()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                    bar.animate().scaleY(0.55f).setDuration(230)
                        .setInterpolator(DecelerateInterpolator()).start()
                    chev.animate().translationX(0f).setDuration(200).start()
                    if (e.action == MotionEvent.ACTION_UP) v.performClick()
                    true
                }
                else -> false
            }
        }
        row.setOnClickListener { onClick() }
        return row
    }

    private fun labelBlock(ctx: Context, title: String, subtitle: String?): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 4), dp(ctx, 10), dp(ctx, 4), dp(ctx, 6))
            addView(TextView(ctx).apply {
                text = title; setTextColor(TEXT); textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                letterSpacing = 0.06f
            })
            if (!subtitle.isNullOrBlank()) {
                addView(TextView(ctx).apply {
                    text = subtitle; setTextColor(SUBTEXT); textSize = 11f
                    setPadding(0, dp(ctx, 3), 0, 0)
                })
            }
        }

    private fun toggleRow(
        ctx: Context,
        label: String,
        desc: String?,
        initial: Boolean,
        onChange: (Boolean) -> Unit
    ): Pair<LinearLayout, SwitchCompat> {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(SURFACE, 14, ctx, 1, BORDER)
            setPadding(dp(ctx, 16), dp(ctx, 15), dp(ctx, 16), dp(ctx, 15))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 8) }
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(ctx).apply {
            text = label; setTextColor(TEXT); textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        })
        if (!desc.isNullOrBlank()) {
            col.addView(TextView(ctx).apply {
                text = desc; setTextColor(SUBTEXT); textSize = 11f
                setPadding(0, dp(ctx, 3), 0, 0)
            })
        }
        row.addView(col)
        val sw = themedSwitch(ctx)
        sw.isChecked = initial
        sw.setOnCheckedChangeListener { _: CompoundButton, v: Boolean -> onChange(v) }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        return row to sw
    }

    private fun bounceBackOn(ctx: Context, row: LinearLayout, sw: SwitchCompat) {
        sw.isChecked = true
        ObjectAnimator.ofFloat(row, View.TRANSLATION_X, 0f, -16f, 13f, -8f, 4f, 0f).apply {
            duration = 460
            interpolator = DecelerateInterpolator()
            start()
        }
        row.animate().scaleX(1.02f).scaleY(1.02f).setDuration(120).withEndAction {
            row.animate().scaleX(1f).scaleY(1f).setDuration(210).start()
        }.start()
        Toast.makeText(
            ctx,
            "Both can't be turned off, one source mode has to stay active.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun subWindow(
        ctx: Context,
        title: String,
        body: (LinearLayout) -> Unit
    ) {
        val dlg = Dialog(ctx).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(BG, 0, ctx)
            setPadding(dp(ctx, 20), dp(ctx, 40), dp(ctx, 20), dp(ctx, 20))
        }
        val header = TextView(ctx).apply {
            text = title; setTextColor(TEXT); textSize = 26f
            setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.08f
        }
        root.addView(header)
        val sub = TextView(ctx).apply {
            text = "RAGHAV REPO · JUSTPLAY"
            setTextColor(SUBTEXT); textSize = 10f
            letterSpacing = 0.22f
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 20))
        }
        root.addView(sub)

        val scroll = ScrollView(ctx)
        val bodyRoot = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(bodyRoot)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        body(bodyRoot)

        val close = Button(ctx).apply {
            text = "CLOSE"; textSize = 13f; letterSpacing = 0.18f
            setTextColor(BG); background = shape(TEXT, 14, ctx)
            isAllCaps = false; setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 52)
            ).apply { topMargin = dp(ctx, 16) }
            setOnClickListener { dlg.dismiss() }
        }
        root.addView(close)

        dlg.setContentView(root)
        dlg.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        dlg.window?.setBackgroundDrawable(shape(BG, 0, ctx))
        dlg.setOnShowListener {
            listOf(header, sub, close).forEachIndexed { i, v -> stagger(v, i, 40L) }
            for (i in 0 until bodyRoot.childCount) {
                stagger(bodyRoot.getChildAt(i), i + 1, 45L)
            }
        }
        dlg.show()
    }

    private fun saveAndRestartButton(ctx: Context, onSave: () -> Unit): Button =
        Button(ctx).apply {
            text = "SAVE & RESTART"; textSize = 15f
            setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.03f
            stateListAnimator = null
            isAllCaps = false
            background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                colors = intArrayOf(RED_BRIGHT, RED_DEEP)
                cornerRadius = dp(ctx, 16).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 12) }
            setPadding(0, dp(ctx, 15), 0, dp(ctx, 15))
            setOnClickListener { onSave() }
        }

    private fun confirmRestart(ctx: Context, message: String) {
        AlertDialog.Builder(ctx)
            .setTitle("Restart Required")
            .setMessage(message)
            .setPositiveButton("Restart") { _, _ -> restartApp(ctx) }
            .setNegativeButton("Later") { _, _ ->
                try {
                    MainActivity.reloadHomeEvent.invoke(true)
                } catch (_: Throwable) {}
            }
            .show()
    }

    fun show(context: Context) {

        var ctx = context
        var p = context
        while (p is android.content.ContextWrapper) {
            if (p is android.app.Activity) { ctx = p; break }
            p = p.baseContext
        }

        val dlg = Dialog(ctx).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(BG, 0, ctx)
            setPadding(dp(ctx, 22), dp(ctx, 36), dp(ctx, 22), dp(ctx, 22))
        }

        val hero = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(0xFF230A0E.toInt(), 0xFF110609.toInt())
                cornerRadius = dp(ctx, 24).toFloat()
                setStroke(dp(ctx, 1), RED_DEEP)
            }
            setPadding(dp(ctx, 20), dp(ctx, 22), dp(ctx, 20), dp(ctx, 18))
        }
        val heroTop = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heroTop.addView(LogoMark(ctx), LinearLayout.LayoutParams(
            dp(ctx, 50), dp(ctx, 50)
        ).apply { rightMargin = dp(ctx, 16) })
        val heroCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        heroCol.addView(TextView(ctx).apply {
            text = "JustPlay"; setTextColor(TEXT); textSize = 27f
            setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.01f
        })
        heroCol.addView(TextView(ctx).apply {
            text = "RAGHAV REPOSITORY"; setTextColor(SUBTEXT); textSize = 9.5f
            letterSpacing = 0.24f; setPadding(0, dp(ctx, 5), 0, 0)
        })
        heroTop.addView(heroCol, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        hero.addView(heroTop)

        val pills = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(ctx, 16), 0, 0)
        }
        val dlOn = JustPlay.downloadOnlyEnabled()
        val streamOn = JustPlay.streamOnlyEnabled()
        val modeText = when {
            dlOn && streamOn -> "ALL LINKS"
            dlOn -> "DOWNLOAD MODE"
            else -> "STREAM MODE"
        }
        pills.addView(statusPill(ctx, modeText, withDot = true))
        pills.addView(statusPill(
            ctx,
            "${JustPlay.sites.count { JustPlay.siteEnabled(it.id) }}/${JustPlay.sites.size} SITES ON",
            withDot = false
        ))
        hero.addView(pills)
        root.addView(hero)

        val section = sectionHeader(ctx, "OPTIONS")
        root.addView(section, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(ctx, 22); bottomMargin = dp(ctx, 6) })

        val sitesRow = homeRow(ctx, "Sites", "Customize sources") { openSites(ctx) }
        root.addView(sitesRow)

        val manageRow = homeRow(ctx, "Manage Sources", "Link modes") { openManageSources(ctx) }
        root.addView(manageRow)

        val perfRow = homeRow(ctx, "Performance", "Loading concurrency") { openPerformance(ctx) }
        root.addView(perfRow)

        root.addView(View(ctx), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val close = Button(ctx).apply {
            text = "CLOSE"; textSize = 13f; letterSpacing = 0.18f
            setTextColor(BG)
            background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                colors = intArrayOf(RED_BRIGHT, RED_DEEP)
                cornerRadius = dp(ctx, 14).toFloat()
            }
            isAllCaps = false; setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 52)
            ).apply { topMargin = dp(ctx, 24) }
            setOnClickListener { dlg.dismiss() }
        }
        root.addView(close)

        hero.alpha = 0f; hero.translationY = 28f
        hero.scaleX = 0.94f; hero.scaleY = 0.94f
        section.alpha = 0f; section.translationX = 70f
        sitesRow.alpha = 0f; sitesRow.translationX = 70f
        manageRow.alpha = 0f; manageRow.translationX = 70f
        perfRow.alpha = 0f; perfRow.translationX = 70f
        close.alpha = 0f

        dlg.setContentView(root)
        dlg.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        dlg.window?.setBackgroundDrawable(shape(BG, 0, ctx))

        dlg.setOnShowListener {
            hero.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(460).setInterpolator(DecelerateInterpolator()).start()
            slideIn(section, 0)
            slideIn(sitesRow, 1)
            slideIn(manageRow, 2)
            slideIn(perfRow, 3)
            close.animate().alpha(1f).setStartDelay(320).setDuration(350)
                .setInterpolator(DecelerateInterpolator()).start()
        }
        dlg.show()
    }

    private fun openSites(ctx: Context) {
        subWindow(ctx, "SITES") { body ->
            body.addView(labelBlock(ctx, "Source sites",
                "Toggle the sites JustPlay pulls links from"))

            val switches = HashMap<String, SwitchCompat>()
            JustPlay.sites.forEach { site ->
                val (row, sw) = toggleRow(ctx, site.label, site.sub,
                    JustPlay.siteEnabled(site.id)) { }
                switches[site.id] = sw
                body.addView(row)
            }

            body.addView(actionRow(ctx, "Enable all",
                "Turn every site back on", "ENABLE ALL") {
                switches.values.forEach { it.isChecked = true }
                Toast.makeText(ctx, "All sources enabled", Toast.LENGTH_SHORT).show()
            })

            body.addView(saveAndRestartButton(ctx) {
                JustPlay.sites.forEach { site ->
                    try {
                        CloudStreamApp.setKey(
                            "JUSTPLAY_SITE_${site.id}",
                            switches[site.id]?.isChecked ?: true
                        )
                    } catch (_: Exception) {}
                }
                confirmRestart(ctx, "Sources saved. Restart CloudStream now to apply them?")
            })

            body.addView(TextView(ctx).apply {
                text = "Changes apply after the app restarts"
                textSize = 11f; setTextColor(SUBTEXT); gravity = Gravity.CENTER
                setPadding(0, dp(ctx, 10), 0, 0)
            })
        }
    }

    private fun openManageSources(ctx: Context) {
        subWindow(ctx, "MANAGE SOURCES") { body ->
            body.addView(labelBlock(ctx, "Link modes", null))

            var dlSwitch: SwitchCompat? = null
            var streamSwitch: SwitchCompat? = null
            var downloadRow: LinearLayout? = null
            var streamRow: LinearLayout? = null
            var guard = false

            fun dlChanged(checked: Boolean) {
                if (guard) return

                if (!checked && streamSwitch?.isChecked != true) {
                    val row = downloadRow ?: return
                    val sw = dlSwitch ?: return
                    guard = true
                    bounceBackOn(ctx, row, sw)
                    guard = false
                }
            }

            fun streamChanged(checked: Boolean) {
                if (guard) return
                if (!checked && dlSwitch?.isChecked != true) {
                    val row = streamRow ?: return
                    val sw = streamSwitch ?: return
                    guard = true
                    bounceBackOn(ctx, row, sw)
                    guard = false
                }
            }

            val (rowDl, swDl) = toggleRow(
                ctx, "Download Only Sources", null,
                JustPlay.downloadOnlyEnabled(), ::dlChanged
            )
            dlSwitch = swDl
            downloadRow = rowDl
            body.addView(rowDl)

            val (rowSt, swSt) = toggleRow(
                ctx, "Stream Only Sources", null,
                JustPlay.streamOnlyEnabled(), ::streamChanged
            )
            streamSwitch = swSt
            streamRow = rowSt
            body.addView(rowSt)

            val dlRef = dlSwitch
            val streamRef = streamSwitch
            body.addView(saveAndRestartButton(ctx) {
                JustPlay.setDownloadOnly(dlRef?.isChecked == true)
                JustPlay.setStreamOnly(streamRef?.isChecked == true)
                confirmRestart(ctx, "Link modes saved. Restart CloudStream now to apply them?")
            })
        }
    }

    private fun openPerformance(ctx: Context) {
        subWindow(ctx, "PERFORMANCE") { body ->
            body.addView(labelBlock(ctx, "Scraping concurrency",
                "How many sites fetch links at the same time"))

            var value = JustPlay.siteConcurrency()

            val stepRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = shape(SURFACE, 12, ctx, 1, BORDER)
                setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(ctx, 8); topMargin = dp(ctx, 4) }
            }
            val stepCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            stepCol.addView(TextView(ctx).apply {
                text = "Parallel sites"; setTextColor(TEXT); textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
            })
            stepCol.addView(TextView(ctx).apply {
                text = "Higher is faster but heavy on weak devices"
                setTextColor(SUBTEXT); textSize = 11f
                setPadding(0, dp(ctx, 3), 0, 0)
            })
            stepRow.addView(stepCol)

            val valueText = TextView(ctx).apply {
                text = value.toString()
                setTextColor(RED_BRIGHT); textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                minWidth = dp(ctx, 30)
            }

            fun stepButton(label: String, onClick: () -> Unit): Button =
                Button(ctx).apply {
                    text = label; textSize = 15f
                    setTextColor(TEXT)
                    background = shape(SURFACE_2, 16, ctx, 1, BORDER_HI)
                    isAllCaps = false; setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
                    minHeight = 0; minWidth = 0
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { leftMargin = dp(ctx, 8) }
                    setOnClickListener { onClick() }
                }

            stepRow.addView(stepButton("-") {
                if (value > 2) {
                    value--
                    valueText.text = value.toString()
                }
            })
            stepRow.addView(valueText)
            stepRow.addView(stepButton("+") {
                if (value < 12) {
                    value++
                    valueText.text = value.toString()
                }
            })
            body.addView(stepRow)

            body.addView(Button(ctx).apply {
                text = "SAVE"; textSize = 15f
                setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
                letterSpacing = 0.03f
                stateListAnimator = null
                isAllCaps = false
                background = GradientDrawable().apply {
                    orientation = GradientDrawable.Orientation.LEFT_RIGHT
                    colors = intArrayOf(RED_BRIGHT, RED_DEEP)
                    cornerRadius = dp(ctx, 16).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(ctx, 12) }
                setPadding(0, dp(ctx, 15), 0, dp(ctx, 15))
                setOnClickListener {
                    JustPlay.setConcurrency(value)
                    Toast.makeText(ctx, "Concurrency saved", Toast.LENGTH_SHORT).show()
                }
            })

            body.addView(TextView(ctx).apply {
                text = "Applies the next time links are loaded, no restart needed"
                textSize = 11f; setTextColor(SUBTEXT); gravity = Gravity.CENTER
                setPadding(0, dp(ctx, 10), 0, 0)
            })
        }
    }

    private fun actionRow(
        ctx: Context, label: String, desc: String?,
        btnText: String, onClick: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(SURFACE, 12, ctx, 1, BORDER)
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 8); topMargin = dp(ctx, 4) }
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(ctx).apply {
            text = label; setTextColor(TEXT); textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        })
        if (!desc.isNullOrBlank()) {
            col.addView(TextView(ctx).apply {
                text = desc; setTextColor(SUBTEXT); textSize = 11f
                setPadding(0, dp(ctx, 3), 0, 0)
            })
        }
        row.addView(col)
        row.addView(Button(ctx).apply {
            text = btnText; textSize = 12f
            setTextColor(RED_BRIGHT)
            background = shape(SURFACE_2, 16, ctx, 1, BORDER_HI)
            isAllCaps = false; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
            minHeight = 0; minWidth = 0
            setOnClickListener { onClick() }
        })
        return row
    }

    private fun restartApp(ctx: Context) {
        try {
            val context = ctx.applicationContext
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(context.packageName)
            val componentName = intent?.component
            if (componentName != null) {
                val restartIntent = Intent.makeRestartActivityTask(componentName)
                context.startActivity(restartIntent)
                Runtime.getRuntime().exit(0)
            }
        } catch (_: Throwable) {}
    }
}
