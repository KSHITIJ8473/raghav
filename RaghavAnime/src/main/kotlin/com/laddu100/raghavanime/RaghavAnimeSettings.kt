package com.laddu100.raghavanime

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

object RaghavAnimeSettings {

    private const val APP_TITLE = "RaghavAnime"
    private const val REPO_TAG = "RAGHAV REPO · RAGHAVANIME"

    private const val BG = 0xFF070709.toInt()
    private const val SURFACE = 0xFF1A1A1E.toInt()
    private const val SURFACE_2 = 0xFF2A2A2E.toInt()
    private const val BORDER = 0x1AFFFFFF.toInt()
    private const val BORDER_HI = 0x2EFFFFFF.toInt()
    private const val TEXT = 0xFFFFFFFF.toInt()
    private const val SUBTEXT = 0xFF98989F.toInt()
    private const val ACCENT = 0xFF0A84FF.toInt()
    private const val ACCENT_HI = 0xFF409CFF.toInt()
    private const val ACCENT_DEEP = 0xFF0060DF.toInt()
    private const val GREEN = 0xFF34C759.toInt()

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
            intArrayOf(GREEN, 0xFF3A3A3C.toInt())
        )
        thumbTintList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFD1D1D6.toInt())
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
            ring.color = 0x59FFFFFF.toInt()
            ring.strokeWidth = r * 0.15f
            canvas.drawCircle(cx, cy, r, ring)
            fill.shader = LinearGradient(
                cx - r, cy - r, cx + r, cy + r,
                ACCENT_HI, ACCENT_DEEP, Shader.TileMode.CLAMP
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
            pill.addView(View(ctx).apply { background = shape(GREEN, 4, ctx) },
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
        h.addView(View(ctx).apply { background = shape(0x66FFFFFF.toInt(), 2, ctx) },
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
            background = shape(SURFACE, 18, ctx, 1, BORDER)
            setPadding(dp(ctx, 16), dp(ctx, 18), dp(ctx, 16), dp(ctx, 18))
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 12) }
        }

        val bar = View(ctx).apply {
            background = shape(ACCENT, 2, ctx)
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
            text = "›"; setTextColor(0xFFD1D1D6.toInt()); textSize = 20f
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
            background = shape(SURFACE, 16, ctx, 1, BORDER)
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
            text = REPO_TAG
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
                colors = intArrayOf(0xFF26262B.toInt(), 0xFF141418.toInt())
                cornerRadius = dp(ctx, 26).toFloat()
                setStroke(dp(ctx, 1), 0x33FFFFFF.toInt())
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
            text = APP_TITLE; setTextColor(TEXT); textSize = 25f
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
        pills.addView(statusPill(ctx, RaghavPerf.profile().name.replace('_', ' '), withDot = true))
        pills.addView(statusPill(ctx, "CONCURRENCY ${RaghavPerf.sourceConcurrency()}", withDot = false))
        hero.addView(pills)
        root.addView(hero)

        val section = sectionHeader(ctx, "OPTIONS")
        root.addView(section, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(ctx, 22); bottomMargin = dp(ctx, 6) })

        val featuresRow = homeRow(ctx, "Features", "Watch time and recommendations") { openFeatures(ctx) }
        root.addView(featuresRow)

        val postersRow = homeRow(ctx, "Posters", "Custom poster template and labels") { openPosters(ctx) }
        root.addView(postersRow)

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
                colors = intArrayOf(0xFFFFFFFF.toInt(), 0xFFD9D9DE.toInt())
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
        featuresRow.alpha = 0f; featuresRow.translationX = 70f
        postersRow.alpha = 0f; postersRow.translationX = 70f
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
            slideIn(featuresRow, 1)
            slideIn(postersRow, 2)
            slideIn(perfRow, 3)
            close.animate().alpha(1f).setStartDelay(320).setDuration(350)
                .setInterpolator(DecelerateInterpolator()).start()
        }
        dlg.show()
    }

    private fun openFeatures(ctx: Context) {
        subWindow(ctx, "FEATURES") { body ->
            body.addView(labelBlock(ctx, "Experimental features",
                "Watch time tracking and homepage recommendations"))

            // recommendations read the watch history, turning the tracker off
            // would starve them so it needs a confirmation first
            var trackerSwitch: SwitchCompat? = null
            val (trackerRow, sw) = toggleRow(
                ctx, "Watch Time Tracker",
                "Tracks watch time per anime and feeds the recommendations",
                RaghavAnimeFeatures.isEnabled("watch_time")
            ) { checked ->
                if (checked) {
                    RaghavAnimeFeatures.setEnabled("watch_time", true)
                } else {
                    AlertDialog.Builder(ctx)
                        .setTitle("Watch Time Tracker")
                        .setMessage("Recommendations depend on the watch history. Turn the tracker off anyway?")
                        .setPositiveButton("Turn off") { _, _ ->
                            RaghavAnimeFeatures.setEnabled("watch_time", false)
                        }
                        .setNegativeButton("Keep on") { _, _ ->
                            trackerSwitch?.isChecked = true
                        }
                        .show()
                }
            }
            trackerSwitch = sw
            body.addView(trackerRow)

            val (recRow, _) = toggleRow(
                ctx, "Anime Recommendations",
                "Recommended anime on the homepage from your watch history",
                RaghavAnimeFeatures.isEnabled("recommendations")
            ) { checked ->
                RaghavAnimeFeatures.setEnabled("recommendations", checked)
                if (!checked) {
                    Toast.makeText(ctx, "Recommendations turned off", Toast.LENGTH_SHORT).show()
                }
            }
            body.addView(recRow)

            body.addView(actionRow(ctx, "Reset recommendations",
                "Clears the current recommendation rows", "RESET") {
                AlertDialog.Builder(ctx)
                    .setTitle("Reset Recommendations")
                    .setMessage("Clear all recommendations from the homepage? New ones appear after the next watch.")
                    .setPositiveButton("Reset") { _, _ ->
                        RaghavAnimeFeatures.resetRecommendations()
                        Toast.makeText(ctx, "Recommendations cleared", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            })

            body.addView(actionRow(ctx, "Reset watch history",
                "Deletes all watch time data and recommendations", "RESET") {
                AlertDialog.Builder(ctx)
                    .setTitle("Reset Watch History")
                    .setMessage("This deletes all watch time data and resets recommendations. Continue?")
                    .setPositiveButton("Reset") { _, _ ->
                        RaghavAnimeFeatures.resetWatchHistory()
                        Toast.makeText(ctx, "Watch history reset", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            })
        }
    }

    private fun openPosters(ctx: Context) {
        subWindow(ctx, "POSTERS") { body ->
            body.addView(labelBlock(ctx, "Custom posters",
                "Poster template and title labels"))

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

            val templateField = EditText(ctx).apply {
                hint = "https://…/{imdb_id}.jpg"
                setText(CustomPosters.template)
                textSize = 13.5f
                setTextColor(TEXT)
                setHintTextColor(SUBTEXT)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                background = null
                setSingleLine(true)
                setPadding(dp(ctx, 2), dp(ctx, 10), dp(ctx, 2), dp(ctx, 10))
            }
            val fieldCard = LinearLayout(ctx).apply {
                background = shape(SURFACE, 16, ctx, 1, BORDER)
                setPadding(dp(ctx, 14), dp(ctx, 4), dp(ctx, 14), dp(ctx, 4))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(ctx, 8) }
            }
            fieldCard.addView(templateField, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))

            class Sample(
                val label: String, val anilistId: Int, val imdbId: String,
                val tmdbId: Int, val isMovie: Boolean, val poster: String
            )
            val samples = listOf(
                Sample("One Piece", 21, "tt0388629", 37854, false,
                    "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx21-ELSYx3yMPcKM.jpg"),
                Sample("Your Name", 21519, "tt5311514", 372058, true,
                    "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx21519-SUo3ZQuCbYhJ.png"),
            )

            val images = samples.map {
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = shape(SURFACE_2, 12, ctx, 1, BORDER)
                    clipToOutline = true
                }
            }
            val names = samples.map { s ->
                TextView(ctx).apply {
                    text = s.label; setTextColor(TEXT); textSize = 13f
                    setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
                }
            }
            val notes = samples.map {
                TextView(ctx).apply {
                    setTextColor(SUBTEXT); textSize = 10.5f; gravity = Gravity.CENTER
                }
            }
            val previewRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = samples.size.toFloat()
            }
            samples.forEachIndexed { i, _ ->
                previewRow.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(ctx, 6), 0, dp(ctx, 6), 0)
                    addView(images[i], LinearLayout.LayoutParams(dp(ctx, 110), dp(ctx, 165)))
                    addView(names[i], LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(ctx, 7) })
                    addView(notes[i], LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(ctx, 2) })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }

            // guard against a slow load overwriting a newer preview
            var generation = 0
            fun reloadPreview() {
                val gen = ++generation
                val tpl = if (CustomPosters.enabled) templateField.text.toString().trim()
                    .replace("%7B", "{", ignoreCase = true)
                    .replace("%7D", "}", ignoreCase = true)
                    .takeIf { it.startsWith("http://") || it.startsWith("https://") } else null
                samples.forEachIndexed { i, s ->
                    names[i].visibility = if (CustomPosters.hideLabels) View.GONE else View.VISIBLE
                    images[i].setImageDrawable(null)
                    notes[i].text = "Loading"
                    scope.launch {
                        val url = tpl?.let { t ->
                            t.replace("{imdb_id}", s.imdbId)
                                .replace("{tmdb_id}", s.tmdbId.toString())
                                .replace("{anilist_id}", s.anilistId.toString())
                                .replace("{type}", if (s.isMovie) "movie" else "series")
                        } ?: s.poster
                        val bmp = try {
                            withContext(Dispatchers.IO) { downloadPoster(url) }
                        } catch (_: Exception) { null }
                        if (gen != generation) return@launch
                        if (bmp != null) {
                            images[i].setImageBitmap(bmp)
                            notes[i].text = if (tpl != null) "Custom poster" else "Default poster"
                        } else {
                            notes[i].text = if (tpl != null) "Custom poster failed to load" else "Could not load poster"
                        }
                    }
                }
            }

            val chips = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(ctx, 2), 0, dp(ctx, 2))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(ctx, 8) }
            }
            CustomPosters.presets.forEach { (label, url) ->
                chips.addView(TextView(ctx).apply {
                    text = label; textSize = 12.5f; setTextColor(ACCENT_HI)
                    setTypeface(typeface, Typeface.BOLD)
                    background = shape(SURFACE_2, 16, ctx, 1, BORDER_HI)
                    setPadding(dp(ctx, 14), dp(ctx, 7), dp(ctx, 14), dp(ctx, 7))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { rightMargin = dp(ctx, 8) }
                    isClickable = true; isFocusable = true
                    setOnClickListener {
                        templateField.setText(url)
                        reloadPreview()
                    }
                })
            }

            val (posterRow, _) = toggleRow(
                ctx, "Custom Poster",
                "Use your own poster template",
                CustomPosters.enabled
            ) { checked ->
                CustomPosters.enabled = checked
                val v = if (checked) View.VISIBLE else View.GONE
                fieldCard.visibility = v
                chips.visibility = v
                reloadPreview()
            }
            body.addView(posterRow)

            val fieldVisible = if (CustomPosters.enabled) View.VISIBLE else View.GONE
            fieldCard.visibility = fieldVisible
            chips.visibility = fieldVisible
            body.addView(fieldCard)
            body.addView(chips)

            val (hideRow, _) = toggleRow(
                ctx, "Hide Labels",
                "No titles under the posters",
                CustomPosters.hideLabels
            ) { checked ->
                CustomPosters.hideLabels = checked
                reloadPreview()
            }
            body.addView(hideRow)

            body.addView(labelBlock(ctx, "Preview", null))
            body.addView(previewRow)
            reloadPreview()

            body.addView(Button(ctx).apply {
                text = "SAVE"; textSize = 15f
                setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
                letterSpacing = 0.03f
                stateListAnimator = null
                isAllCaps = false
                background = GradientDrawable().apply {
                    orientation = GradientDrawable.Orientation.LEFT_RIGHT
                    colors = intArrayOf(ACCENT_HI, ACCENT_DEEP)
                    cornerRadius = dp(ctx, 16).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(ctx, 12) }
                setPadding(0, dp(ctx, 15), 0, dp(ctx, 15))
                setOnClickListener {
                    CustomPosters.template = templateField.text.toString()
                    Toast.makeText(ctx, "Template saved", Toast.LENGTH_SHORT).show()
                }
            })
        }
    }

    private fun downloadPoster(url: String): Bitmap? = try {
        app.baseClient.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.byteStream()?.let { BitmapFactory.decodeStream(it) }
        }
    } catch (_: Exception) {
        null
    }

    private fun openPerformance(ctx: Context) {
        subWindow(ctx, "PERFORMANCE") { body ->
            body.addView(labelBlock(ctx, "Source concurrency",
                "How many sources fetch links at the same time"))

            var value = RaghavPerf.sourceConcurrency()

            val stepRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = shape(SURFACE, 14, ctx, 1, BORDER)
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
                text = "Parallel sources"; setTextColor(TEXT); textSize = 14f
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
                setTextColor(ACCENT_HI); textSize = 16f
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
                if (value > 6) {
                    value--
                    valueText.text = value.toString()
                }
            })
            stepRow.addView(valueText)
            stepRow.addView(stepButton("+") {
                if (value < 40) {
                    value++
                    valueText.text = value.toString()
                }
            })
            body.addView(stepRow)

            body.addView(actionRow(ctx, "Device default",
                "Follow the profile picked from the hardware again", "DEFAULT") {
                RaghavPerf.setUserConcurrency(0)
                value = RaghavPerf.sourceConcurrency()
                valueText.text = value.toString()
                Toast.makeText(ctx, "Back to the device default", Toast.LENGTH_SHORT).show()
            })

            body.addView(Button(ctx).apply {
                text = "SAVE"; textSize = 15f
                setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
                letterSpacing = 0.03f
                stateListAnimator = null
                isAllCaps = false
                background = GradientDrawable().apply {
                    orientation = GradientDrawable.Orientation.LEFT_RIGHT
                    colors = intArrayOf(ACCENT_HI, ACCENT_DEEP)
                    cornerRadius = dp(ctx, 16).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(ctx, 12) }
                setPadding(0, dp(ctx, 15), 0, dp(ctx, 15))
                setOnClickListener {
                    RaghavPerf.setUserConcurrency(value)
                    Toast.makeText(ctx, "Concurrency saved", Toast.LENGTH_SHORT).show()
                }
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
            background = shape(SURFACE, 14, ctx, 1, BORDER)
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
            setTextColor(ACCENT_HI)
            background = shape(SURFACE_2, 16, ctx, 1, BORDER_HI)
            isAllCaps = false; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
            minHeight = 0; minWidth = 0
            setOnClickListener { onClick() }
        })
        return row
    }
}
