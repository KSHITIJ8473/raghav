package com.torrentsv1

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.lagradost.cloudstream3.MainActivity

object TorrentsSettings {

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
            canvas.drawRect(cx - s * 0.18f, cy - s, cx + s * 0.18f, cy + s * 0.15f, fill)
            val tri = Path()
            tri.moveTo(cx - s * 0.55f, cy + s * 0.1f)
            tri.lineTo(cx + s * 0.55f, cy + s * 0.1f)
            tri.lineTo(cx, cy + s * 0.85f)
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

        // the bar rests at half height and stretches while pressed
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

        // the chevron nudges right while pressed
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

    private fun orderSelector(ctx: Context, tmdbOnTop: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 8) }
        }
        var tmdbFirst = tmdbOnTop
        val anilistChip = TextView(ctx)
        val tmdbChip = TextView(ctx)

        fun render() {
            listOf(false to anilistChip, true to tmdbChip).forEach { (isTmdb, chip) ->
                val active = isTmdb == tmdbFirst
                chip.text = if (isTmdb) "TMDB ON TOP" else "ANILIST ON TOP"
                chip.gravity = Gravity.CENTER
                chip.textSize = 11f
                chip.letterSpacing = 0.12f
                chip.setTypeface(chip.typeface, Typeface.BOLD)
                chip.setTextColor(if (active) android.graphics.Color.WHITE else SUBTEXT)
                chip.background = if (active) shape(RED_DEEP, 12, ctx, 1, RED_BRIGHT)
                else shape(SURFACE_2, 12, ctx, 1, BORDER)
                chip.setPadding(dp(ctx, 10), dp(ctx, 13), dp(ctx, 10), dp(ctx, 13))
            }
        }

        anilistChip.setOnClickListener {
            tmdbFirst = false; render(); onChange(false)
            anilistChip.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
        tmdbChip.setOnClickListener {
            tmdbFirst = true; render(); onChange(true)
            tmdbChip.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
        render()
        wrap.addView(anilistChip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        wrap.addView(tmdbChip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(ctx, 8)
        })
        return wrap
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
            text = "RAGHAV REPO · TORRENTSV1"
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
            setTextColor(android.graphics.Color.WHITE); setTypeface(typeface, Typeface.BOLD)
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

    fun show(context: Context) {
        // unwrap in case a ContextWrapper is handed over
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
            text = "TorrentsV1"; setTextColor(TEXT); textSize = 27f
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
        val debridProvider = getStringSetting(KEY_DEBRID_PROVIDER)
        val debridKey = getStringSetting(KEY_DEBRID_KEY)
        val debridOn = debridProvider.isNotBlank() && debridKey.isNotBlank() && debridProvider != "None"
        pills.addView(statusPill(ctx, if (debridOn) "DEBRID ACTIVE" else "MAGNET MODE", withDot = true))
        val sourcesOn = listOf(KEY_TORRENTIO, KEY_TORRENTSDB, KEY_ANIMETOSHO)
            .count { getSetting(it, true) }
        pills.addView(statusPill(ctx, "$sourcesOn/3 SOURCES ON", withDot = false))
        hero.addView(pills)
        root.addView(hero)

        val section = sectionHeader(ctx, "OPTIONS")
        root.addView(section, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(ctx, 22); bottomMargin = dp(ctx, 6) })

        val catalogsRow = homeRow(ctx, "Catalogs", "AniList and TMDB sections") { openCatalogs(ctx) }
        root.addView(catalogsRow)

        val sourcesRow = homeRow(ctx, "Torrent Sources", "Torrentio, TorrentsDB, Animetosho") { openSources(ctx) }
        root.addView(sourcesRow)

        val debridRow = homeRow(ctx, "Debrid Service", "Provider and API key") { openDebrid(ctx) }
        root.addView(debridRow)

        val addonsRow = homeRow(ctx, "Stremio Addons", "Custom addon URLs") { openAddons(ctx) }
        root.addView(addonsRow)

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

        // hidden until shown so the first frame never flashes
        hero.alpha = 0f; hero.translationY = 28f
        hero.scaleX = 0.94f; hero.scaleY = 0.94f
        section.alpha = 0f; section.translationX = 70f
        catalogsRow.alpha = 0f; catalogsRow.translationX = 70f
        sourcesRow.alpha = 0f; sourcesRow.translationX = 70f
        debridRow.alpha = 0f; debridRow.translationX = 70f
        addonsRow.alpha = 0f; addonsRow.translationX = 70f
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
            slideIn(catalogsRow, 1)
            slideIn(sourcesRow, 2)
            slideIn(debridRow, 3)
            slideIn(addonsRow, 4)
            close.animate().alpha(1f).setStartDelay(320).setDuration(350)
                .setInterpolator(DecelerateInterpolator()).start()
        }
        dlg.show()
    }

    private fun openCatalogs(ctx: Context) {
        subWindow(ctx, "CATALOGS") { body ->
            body.addView(labelBlock(ctx, "Browse catalogs", "Toggle the home page sections"))

            val switches = HashMap<String, SwitchCompat>()
            listOf(
                Triple("AniList", "Anime catalog", KEY_ANILIST),
                Triple("TMDB", "Movies & TV series", KEY_TMDB)
            ).forEach { (label, desc, key) ->
                val (row, sw) = toggleRow(ctx, label, desc, getSetting(key, true)) { }
                switches[key] = sw
                body.addView(row)
            }

            body.addView(labelBlock(ctx, "Home page order", "Which catalog loads at the top"))
            var tmdbOnTop = getSetting(KEY_TMDB_ON_TOP, false)
            body.addView(orderSelector(ctx, tmdbOnTop) { tmdbOnTop = it })

            body.addView(saveAndRestartButton(ctx) {
                switches.forEach { (key, sw) -> setSetting(key, sw.isChecked) }
                setSetting(KEY_TMDB_ON_TOP, tmdbOnTop)
                confirmRestart(ctx, "Catalogs saved. Restart CloudStream now to apply them?")
            })

            body.addView(TextView(ctx).apply {
                text = "Changes apply after the app restarts"
                textSize = 11f; setTextColor(SUBTEXT); gravity = Gravity.CENTER
                setPadding(0, dp(ctx, 10), 0, 0)
            })
        }
    }

    private fun openSources(ctx: Context) {
        subWindow(ctx, "TORRENT SOURCES") { body ->
            body.addView(labelBlock(ctx, "Providers", "Pick where torrents are pulled from"))

            val switches = HashMap<String, SwitchCompat>()
            listOf(
                Triple("Torrentio", "Main torrent provider", KEY_TORRENTIO),
                Triple("TorrentsDB", "Alternative provider", KEY_TORRENTSDB),
                Triple("Animetosho", "Anime releases", KEY_ANIMETOSHO)
            ).forEach { (label, desc, key) ->
                val (row, sw) = toggleRow(ctx, label, desc, getSetting(key, true)) { }
                switches[key] = sw
                body.addView(row)
            }

            body.addView(saveAndRestartButton(ctx) {
                switches.forEach { (key, sw) -> setSetting(key, sw.isChecked) }
                confirmRestart(ctx, "Sources saved. Restart CloudStream now to apply them?")
            })

            body.addView(TextView(ctx).apply {
                text = "Changes apply after the app restarts"
                textSize = 11f; setTextColor(SUBTEXT); gravity = Gravity.CENTER
                setPadding(0, dp(ctx, 10), 0, 0)
            })
        }
    }

    private fun openDebrid(ctx: Context) {
        subWindow(ctx, "DEBRID SERVICE") { body ->
            body.addView(labelBlock(ctx, "Provider", "Resolve torrents into direct streams"))

            val providers = listOf("None", "RealDebrid", "Premiumize", "AllDebrid", "DebridLink", "EasyDebrid", "Offcloud", "TorBox", "Put.io")
            val spinner = Spinner(ctx).apply {
                adapter = darkSpinnerAdapter(ctx, providers)
                val saved = getStringSetting(KEY_DEBRID_PROVIDER)
                if (saved.isNotBlank()) {
                    val pos = providers.indexOf(saved)
                    if (pos >= 0) setSelection(pos)
                }
            }
            body.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = shape(SURFACE_2, 12, ctx, 1, BORDER)
                setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
                addView(spinner)
            })

            body.addView(labelBlock(ctx, "API key", "From your debrid account"))
            val keyInput = EditText(ctx).apply {
                hint = "Enter API key"; setHintTextColor(SUBTEXT); setTextColor(TEXT)
                textSize = 14f
                background = shape(SURFACE_2, 12, ctx, 1, BORDER)
                setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
                setText(getStringSetting(KEY_DEBRID_KEY))
            }
            body.addView(keyInput)

            body.addView(saveAndRestartButton(ctx) {
                setStringSetting(KEY_DEBRID_PROVIDER, spinner.selectedItem?.toString() ?: "None")
                setStringSetting(KEY_DEBRID_KEY, keyInput.text?.toString() ?: "")
                confirmRestart(ctx, "Debrid settings saved. Restart CloudStream now to apply them?")
            })

            body.addView(TextView(ctx).apply {
                text = "Without debrid the plugin returns plain magnets"
                textSize = 11f; setTextColor(SUBTEXT); gravity = Gravity.CENTER
                setPadding(0, dp(ctx, 10), 0, 0)
            })
        }
    }

    private fun openAddons(ctx: Context) {
        subWindow(ctx, "STREMIO ADDONS") { body ->
            body.addView(labelBlock(ctx, "Custom addons", "Point the plugin at extra Stremio addons"))

            val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            body.addView(list)
            fun refresh() = refreshAddonList(ctx, list)
            refresh()

            body.addView(actionRow(ctx, "Add addon", "Name, URL and type", "ADD") {
                showAddAddonDialog(ctx) { refresh() }
            })

            body.addView(saveAndRestartButton(ctx) {
                confirmRestart(ctx, "Addons saved. Restart CloudStream now to apply them?")
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

    private fun refreshAddonList(ctx: Context, container: LinearLayout) {
        container.removeAllViews()
        val addons = getStremioAddons()
        if (addons.isEmpty()) {
            container.addView(TextView(ctx).apply {
                text = "No addons yet"; textSize = 13f; setTextColor(SUBTEXT)
                setPadding(0, dp(ctx, 12), 0, dp(ctx, 12)); gravity = Gravity.CENTER
            })
            return
        }
        for ((index, addon) in addons.withIndex()) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 8), dp(ctx, 10))
                background = shape(SURFACE, 10, ctx, 1, BORDER)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(ctx, 6) }
            }
            val typeLabel = when (addon.type.uppercase()) {
                "TORRENT" -> "[Torrent]"; "DEBRID" -> "[Debrid]"; "SUBTITLE" -> "[Subs]"; else -> "[Addon]"
            }
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(ctx).apply {
                text = "$typeLabel  ${addon.name}"; textSize = 13f; setTextColor(TEXT)
                setTypeface(typeface, Typeface.BOLD)
            })
            col.addView(TextView(ctx).apply {
                text = addon.url; textSize = 10.5f; setTextColor(SUBTEXT)
                setPadding(0, dp(ctx, 2), 0, 0)
            })
            row.addView(col)
            row.addView(ImageButton(ctx).apply {
                setImageResource(android.R.drawable.ic_menu_delete)
                setBackgroundColor(android.graphics.Color.TRANSPARENT); setColorFilter(RED)
                setOnClickListener {
                    val updated = addons.toMutableList(); updated.removeAt(index)
                    saveStremioAddons(updated); refreshAddonList(ctx, container)
                }
            })
            container.addView(row)
        }
    }

    private fun showAddAddonDialog(ctx: Context, onAdded: () -> Unit) {
        val dlg = Dialog(ctx).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(SURFACE, 20, ctx, 1, BORDER_HI)
            setPadding(dp(ctx, 20), dp(ctx, 20), dp(ctx, 20), dp(ctx, 16))
        }

        card.addView(TextView(ctx).apply {
            text = "ADD ADDON"; setTextColor(TEXT); textSize = 18f
            setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.08f
            setPadding(0, 0, 0, dp(ctx, 12))
        })

        card.addView(labelBlock(ctx, "Name", null))
        val etName = EditText(ctx).apply {
            hint = "Addon name"; setHintTextColor(SUBTEXT); setTextColor(TEXT); textSize = 14f
            background = shape(SURFACE_2, 12, ctx, 1, BORDER)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
        }
        card.addView(etName)

        card.addView(labelBlock(ctx, "URL", null))
        val etUrl = EditText(ctx).apply {
            hint = "https://addon.example.com/manifest.json"
            setHintTextColor(SUBTEXT); setTextColor(TEXT); textSize = 14f
            background = shape(SURFACE_2, 12, ctx, 1, BORDER)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
        }
        card.addView(etUrl)

        card.addView(labelBlock(ctx, "Type", null))
        val typeSpinner = Spinner(ctx).apply {
            adapter = darkSpinnerAdapter(ctx, listOf("HTTPS", "TORRENT", "DEBRID", "SUBTITLE"))
        }
        card.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(SURFACE_2, 12, ctx, 1, BORDER)
            setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
            addView(typeSpinner)
        })

        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(ctx, 16), 0, 0)
        }
        buttons.addView(Button(ctx).apply {
            text = "CANCEL"; textSize = 13f; setTextColor(SUBTEXT)
            background = shape(SURFACE_2, 14, ctx, 1, BORDER)
            isAllCaps = false; setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 46), 1f).apply {
                rightMargin = dp(ctx, 8)
            }
            setOnClickListener { dlg.dismiss() }
        })
        buttons.addView(Button(ctx).apply {
            text = "ADD"; textSize = 13f
            setTextColor(android.graphics.Color.WHITE)
            background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                colors = intArrayOf(RED_BRIGHT, RED_DEEP)
                cornerRadius = dp(ctx, 14).toFloat()
            }
            isAllCaps = false; setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 46), 1f)
            setOnClickListener {
                val name = etName.text?.toString()?.trim() ?: ""
                val url = etUrl.text?.toString()?.trim() ?: ""
                val type = typeSpinner.selectedItem?.toString() ?: "HTTPS"
                if (name.isNotBlank() && url.isNotBlank()) {
                    val addons = getStremioAddons().toMutableList()
                    addons.add(StremioAddon(name, url, type))
                    saveStremioAddons(addons)
                    Toast.makeText(ctx, "$name added", Toast.LENGTH_SHORT).show()
                    dlg.dismiss()
                    onAdded()
                } else {
                    Toast.makeText(ctx, "Name and URL are both needed", Toast.LENGTH_SHORT).show()
                }
            }
        })
        card.addView(buttons)

        dlg.setContentView(card)
        dlg.window?.setLayout(
            dialogWidth(ctx, 0.92f), ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dlg.window?.setBackgroundDrawable(shape(BG, 0, ctx))
        dlg.show()
    }

    private fun dialogWidth(ctx: Context, fraction: Float): Int {
        val dm = ctx.resources.displayMetrics
        val maxW = (420 * dm.density).toInt()
        val w = (dm.widthPixels * fraction).toInt()
        return if (w > maxW) maxW else w
    }

    private fun darkSpinnerAdapter(ctx: Context, items: List<String>): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_dropdown_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(TEXT); textSize = 14f
                }
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getDropDownView(position, convertView, parent) as TextView).apply {
                    setTextColor(TEXT); setBackgroundColor(SURFACE_2)
                    setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
                }
            }
        }
    }
}
