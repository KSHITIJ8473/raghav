package com.elitestreams

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
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.lagradost.cloudstream3.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object EsSettings {

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

    // bigg boss keeps its channels, it just does not get a toggle
    private val toggleSources = EsFeeds.sources.filter { it.id != "bigg-boss" }

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
            ring.strokeWidth = r * 0.14f
            canvas.drawCircle(cx, cy, r, ring)
            fill.shader = LinearGradient(
                cx - r, cy - r, cx + r, cy + r,
                RED_BRIGHT, RED_DEEP, Shader.TileMode.CLAMP
            )
            val s = r * 0.52f
            val screen = Path()
            screen.moveTo(cx - s, cy - s * 0.72f)
            screen.lineTo(cx + s, cy - s * 0.72f)
            screen.lineTo(cx + s, cy + s * 0.28f)
            screen.lineTo(cx - s, cy + s * 0.28f)
            screen.close()
            canvas.drawPath(screen, fill)
            val stand = Path()
            stand.moveTo(cx - s * 0.4f, cy + s * 0.75f)
            stand.lineTo(cx - s * 0.18f, cy + s * 0.28f)
            stand.lineTo(cx + s * 0.18f, cy + s * 0.28f)
            stand.lineTo(cx + s * 0.4f, cy + s * 0.75f)
            stand.close()
            canvas.drawPath(stand, fill)
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

    fun show(context: Context) {
        var ctx = context
        var p = context
        while (p is android.content.ContextWrapper) {
            if (p is android.app.Activity) {
                ctx = p
                break
            }
            p = p.baseContext
        }

        // feeds load off the main thread, the dialog builds once they are in
        CoroutineScope(Dispatchers.Main).launch {
            val channels = withContext(Dispatchers.IO) { EsFeeds.allChannels() }
            showDialog(ctx, channels)
        }
    }

    private fun showDialog(ctx: Context, channels: List<EsFeeds.ChannelData>) {
        val bySource = channels.groupBy { it.sourceId }

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
            text = "Elite Streams"; setTextColor(TEXT); textSize = 27f
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
        pills.addView(statusPill(ctx, "LIVE", withDot = true))
        pills.addView(statusPill(
            ctx,
            "${toggleSources.count { EliteStreams.sourceEnabled(it.id) }}/${toggleSources.size} SOURCES ON",
            withDot = false
        ))
        hero.addView(pills)
        root.addView(hero)

        val section = sectionHeader(ctx, "SOURCES")
        root.addView(section, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(ctx, 22); bottomMargin = dp(ctx, 6) })

        val scroll = ScrollView(ctx)
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val switches = HashMap<String, SwitchCompat>()
        val rows = mutableListOf<View>()
        toggleSources.forEach { source ->
            val count = bySource[source.id]?.size ?: 0
            val (row, sw) = toggleRow(
                ctx, source.label, "$count channels",
                EliteStreams.sourceEnabled(source.id)
            ) { }
            switches[source.id] = sw
            body.addView(row)
            rows.add(row)
        }

        body.addView(actionRow(ctx, "Enable all",
            "Turn every source back on", "ENABLE ALL") {
            switches.values.forEach { it.isChecked = true }
            Toast.makeText(ctx, "All sources enabled", Toast.LENGTH_SHORT).show()
        })

        body.addView(saveAndRestartButton(ctx) {
            toggleSources.forEach { source ->
                EliteStreams.setSourceEnabled(
                    source.id,
                    switches[source.id]?.isChecked == true
                )
            }
            confirmRestart(ctx, "Sources saved. Restart CloudStream now to apply them?")
        })

        body.addView(TextView(ctx).apply {
            text = "Changes apply after the app restarts"
            textSize = 11f; setTextColor(SUBTEXT); gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 10), 0, 0)
        })

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
        rows.forEach { it.alpha = 0f; it.translationX = 70f }
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
            rows.forEachIndexed { i, v -> slideIn(v, i + 1) }
            close.animate().alpha(1f).setStartDelay(320).setDuration(350)
                .setInterpolator(DecelerateInterpolator()).start()
        }
        dlg.show()
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
