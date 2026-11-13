package com.livetgtv

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson

private const val SHEET_BG = 0xFF17171B.toInt()
private const val CARD_BG = 0xFF1F1F26.toInt()
private const val STROKE = 0xFF2A2A33.toInt()
private const val ACCENT = 0xFF3DD598.toInt()
private const val ACCENT_DIM = 0xFF5A5A66.toInt()
private const val ACCENT_TEXT = 0xFF0E1713.toInt()
private const val SECONDARY = 0xFF232329.toInt()
private const val TEXT_SUBTLE = 0xFF9E9EA8.toInt()
private const val TEXT_FAINT = 0xFF6E6E78.toInt()

class LiveTgTvSettingsFragment : BottomSheetDialogFragment() {

    private var listContainer: LinearLayout? = null
    private var nameInput: EditText? = null
    private var urlInput: EditText? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun d(v: Int) = (v * dp).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SHEET_BG)
            setPadding(d(22), d(20), d(22), d(18))
        }

        root.addView(View(ctx).apply {
            background = GradientDrawable().apply {
                setColor(ACCENT_DIM)
                cornerRadius = d(2).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(d(36), d(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        })
        root.addView(View(ctx), LinearLayout.LayoutParams(1, d(16)))

        root.addView(TextView(ctx).apply {
            text = "LiveTGTV + M3U PLAYER"
            textSize = 21f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(ctx).apply {
            text = "Playlists"
            textSize = 12.5f
            setTextColor(TEXT_SUBTLE)
            setPadding(0, d(2), 0, 0)
        })
        root.addView(View(ctx), LinearLayout.LayoutParams(1, d(16)))

        val statusCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(CARD_BG)
                cornerRadius = d(16).toFloat()
                setStroke(d(1), STROKE)
            }
            setPadding(d(16), d(14), d(16), d(14))
        }
        statusCard.addView(FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(d(30), d(30))
            addView(View(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(d(10), d(10), Gravity.CENTER)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ACCENT)
                }
            })
        })
        val statusColumn = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                leftMargin = d(14)
            }
        }
        statusColumn.addView(TextView(ctx).apply {
            text = "livetgtv playlist"
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        statusColumn.addView(TextView(ctx).apply {
            text = "By default always on"
            textSize = 12f
            setTextColor(TEXT_SUBTLE)
            setPadding(0, d(2), 0, 0)
        })
        statusCard.addView(statusColumn)
        root.addView(statusCard, LinearLayout.LayoutParams(-1, -2))
        root.addView(View(ctx), LinearLayout.LayoutParams(1, d(14)))

        fun sheetInput(hint: String, singleLine: Boolean): EditText {
            return EditText(ctx).apply {
                this.hint = hint
                setHintTextColor(TEXT_FAINT)
                setTextColor(Color.WHITE)
                isSingleLine = singleLine
                textSize = 14.5f
                background = GradientDrawable().apply {
                    setColor(CARD_BG)
                    cornerRadius = d(12).toFloat()
                    setStroke(d(1), STROKE)
                }
                setPadding(d(14), d(13), d(14), d(13))
                layoutParams = LinearLayout.LayoutParams(-1, -2)
            }
        }

        nameInput = sheetInput("Playlist name", true)
        urlInput = sheetInput("M3U URL", true).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        root.addView(nameInput)
        root.addView(View(ctx), LinearLayout.LayoutParams(1, d(8)))
        root.addView(urlInput)
        root.addView(View(ctx), LinearLayout.LayoutParams(1, d(12)))

        root.addView(Button(ctx).apply {
            text = "Add playlist"
            transformationMethod = null
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ACCENT_TEXT)
            stateListAnimator = null
            background = GradientDrawable().apply {
                setColor(ACCENT)
                cornerRadius = d(26).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(-1, d(52))
            setOnClickListener { addPlaylist() }
        })
        root.addView(View(ctx), LinearLayout.LayoutParams(1, d(14)))

        root.addView(TextView(ctx).apply {
            text = "Registered playlists"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(View(ctx), LinearLayout.LayoutParams(1, d(8)))

        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1.0f)
        }
        listContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(listContainer)
        root.addView(scroll)
        root.addView(View(ctx), LinearLayout.LayoutParams(1, d(12)))

        root.addView(Button(ctx).apply {
            text = "Save and restart app"
            transformationMethod = null
            textSize = 14f
            setTextColor(Color.WHITE)
            stateListAnimator = null
            background = GradientDrawable().apply {
                setColor(SECONDARY)
                cornerRadius = d(26).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(-1, d(48))
            setOnClickListener { confirmSaveAndRestart() }
        })
        root.addView(View(ctx), LinearLayout.LayoutParams(1, d(12)))

        root.addView(TextView(ctx).apply {
            text = "livetgtv is always on from its own playlist url. Every playlist added here shows up as its own extension after saving."
            textSize = 11.5f
            setTextColor(TEXT_FAINT)
            setLineSpacing(d(2).toFloat(), 1f)
        })

        refreshList()
        return root
    }

    private fun currentPlaylists(): MutableList<PlaylistEntry> {
        return try {
            val raw = requireContext()
                .getSharedPreferences(LiveTgTvPlugin.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(LiveTgTvPlugin.PREF_PLAYLISTS, "[]") ?: "[]"
            parseJson<List<PlaylistEntry>>(raw).toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun savePlaylists(list: List<PlaylistEntry>) {
        requireContext().getSharedPreferences(LiveTgTvPlugin.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(LiveTgTvPlugin.PREF_PLAYLISTS, list.toJson())
            .apply()
    }

    private fun addPlaylist() {
        val name = nameInput?.text?.toString()?.trim().orEmpty()
        val url = urlInput?.text?.toString()?.trim().orEmpty()
        if (name.isEmpty() || url.isEmpty() || !url.startsWith("http")) {
            Toast.makeText(requireContext(), "Enter a playlist name and an m3u url.", Toast.LENGTH_SHORT).show()
            return
        }
        savePlaylists(currentPlaylists().apply { add(PlaylistEntry(name, url)) })
        nameInput?.setText("")
        urlInput?.setText("")
        refreshList()
        Toast.makeText(requireContext(), "Playlist added.", Toast.LENGTH_SHORT).show()
        confirmSaveAndRestart()
    }

    private fun refreshList() {
        val ctx = context ?: return
        val dp = resources.displayMetrics.density
        fun d(v: Int) = (v * dp).toInt()
        val container = listContainer ?: return
        container.removeAllViews()
        val playlists = currentPlaylists()

        if (playlists.isEmpty()) {
            container.addView(TextView(ctx).apply {
                text = "No playlists added yet."
                textSize = 12.5f
                setTextColor(TEXT_FAINT)
                gravity = Gravity.CENTER
                setPadding(0, d(14), 0, d(14))
            })
            return
        }

        for (index in playlists.indices) {
            val entry = playlists[index]
            container.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(CARD_BG)
                    cornerRadius = d(16).toFloat()
                    setStroke(d(1), STROKE)
                }
                setPadding(d(14), d(12), d(14), d(12))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = d(8)
                }

                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1.0f)
                    addView(TextView(ctx).apply {
                        text = entry.name
                        textSize = 15f
                        setTextColor(Color.WHITE)
                        typeface = Typeface.DEFAULT_BOLD
                    })
                    addView(TextView(ctx).apply {
                        text = entry.url
                        textSize = 12f
                        setTextColor(TEXT_SUBTLE)
                        isSingleLine = true
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                })

                addView(Button(ctx).apply {
                    text = "Remove"
                    transformationMethod = null
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    stateListAnimator = null
                    background = GradientDrawable().apply {
                        setColor(SECONDARY)
                        cornerRadius = d(18).toFloat()
                    }
                    setPadding(d(16), 0, d(16), 0)
                    layoutParams = LinearLayout.LayoutParams(-2, d(36)).apply {
                        leftMargin = d(12)
                    }
                    setOnClickListener {
                        savePlaylists(currentPlaylists().apply { removeAt(index) })
                        refreshList()
                    }
                })
            })
        }
    }

    private fun confirmSaveAndRestart() {
        AlertDialog.Builder(requireContext())
            .setTitle("Save and restart?")
            .setMessage("The list is stored now and CloudStream restarts so the playlists register as extensions.")
            .setPositiveButton("Save and restart") { _, _ -> saveAndRestart() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveAndRestart() {
        val launch = requireContext().packageManager.getLaunchIntentForPackage(requireContext().packageName)
        val component = launch?.component
        if (component != null) {
            dismiss()
            startActivity(Intent.makeRestartActivityTask(component))
            Runtime.getRuntime().exit(0)
        }
    }
}
