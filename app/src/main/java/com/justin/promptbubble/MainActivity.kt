package com.justin.promptbubble

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Setup + the Tray: everything you've collected, ready to turn into one prompt. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var enableBtn: Button
    private lateinit var bubbleBtn: Button
    private lateinit var instruction: EditText
    private lateinit var countText: TextView
    private lateinit var list: LinearLayout
    private val trayListener: () -> Unit = { render() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(col)
        })

        col.addView(TextView(this).apply {
            text = "Prompt Bubble"; textSize = 26f; setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = "Collect text and snips from any app, then send them as one prompt."
            alpha = 0.7f; setPadding(0, dp(4), 0, dp(16))
        })

        status = TextView(this).apply { setPadding(0, 0, 0, dp(8)) }
        col.addView(status)
        enableBtn = button("Turn on in Accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        bubbleBtn = button("Show bubble") {
            val svc = BubbleService.instance ?: return@button
            if (svc.isBubbleShown()) svc.hideBubble() else svc.showBubble()
            refreshStatus()
        }
        col.addView(row(enableBtn, bubbleBtn))

        col.addView(label("Instruction (goes at the top of the prompt)"))
        instruction = EditText(this).apply {
            hint = "e.g. Summarise these and list the action items"
            minLines = 2
            setText(Prefs.instruction(this@MainActivity))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) = Prefs.setInstruction(this@MainActivity, s.toString())
            })
        }
        col.addView(instruction)

        countText = label("")
        val clipBtn = button("+ Clipboard") { addClipboard() }
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(countText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(clipBtn)
        })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)

        col.addView(row(
            button("Copy prompt") { copyPrompt() },
            button("Send to AI…") { sharePrompt() },
            button("Clear") { confirmClear() },
        ).apply { setPadding(0, dp(12), 0, 0) })
    }

    override fun onResume() {
        super.onResume()
        TrayStore.addListener(trayListener)
        refreshStatus()
        render()
    }

    override fun onPause() {
        TrayStore.removeListener(trayListener)
        super.onPause()
    }

    // ---------------------------------------------------------------- UI helpers

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        views.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text; textSize = 13f; setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun refreshStatus() {
        val svc = BubbleService.instance
        if (svc == null) {
            status.text = "⚠️  The Prompt Bubble accessibility service is off. It's needed to float over apps, read on-screen text and take snips."
            enableBtn.visibility = View.VISIBLE
            bubbleBtn.isEnabled = false
            bubbleBtn.text = "Show bubble"
        } else {
            status.text = "✅  Ready. Tap the bubble in any app."
            enableBtn.visibility = View.GONE
            bubbleBtn.isEnabled = true
            bubbleBtn.text = if (svc.isBubbleShown()) "Hide bubble" else "Show bubble"
        }
    }

    private fun render() {
        list.removeAllViews()
        val items = TrayStore.all(this)
        countText.text = "Tray · ${items.size} item${if (items.size == 1) "" else "s"}"
        if (items.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Nothing yet. Open any app, tap the floating bubble, then Grab text or Snip."
                alpha = 0.6f; setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        items.forEach { list.addView(card(it)) }
    }

    private fun card(item: TrayItem): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setStroke(dp(1), 0x44888888)
            }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        val ago = DateUtils.getRelativeTimeSpanString(item.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        card.addView(TextView(this).apply {
            text = "${if (item.isImage) "✂️ Snip" else "📋 Text"} · ${item.source} · $ago"
            textSize = 12f; alpha = 0.6f
        })
        if (item.isImage) {
            card.addView(ImageView(this).apply {
                adjustViewBounds = true; maxHeight = dp(220)
                runCatching { setImageURI(Uri.parse(item.uri)) }
                setPadding(0, dp(6), 0, dp(4))
            })
        } else {
            card.addView(TextView(this).apply {
                text = item.text; maxLines = 6; ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(6), 0, dp(4))
            })
            card.setOnClickListener { editItem(item) }
        }
        card.addView(LinearLayout(this).apply {
            gravity = Gravity.END
            if (!item.isImage) addView(smallAction("Edit") { editItem(item) })
            addView(smallAction("Remove") { TrayStore.remove(this@MainActivity, item.id) })
        })
        return card
    }

    private fun smallAction(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 13f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(0xFF6F6FE8.toInt())
        setPadding(dp(12), dp(8), dp(4), dp(6))
        setOnClickListener { onClick() }
    }

    // ---------------------------------------------------------------- actions

    private fun editItem(item: TrayItem) {
        val field = EditText(this).apply { setText(item.text); setPadding(dp(20), dp(12), dp(20), dp(12)) }
        AlertDialog.Builder(this)
            .setTitle("Edit snippet")
            .setView(field)
            .setPositiveButton("Save") { _, _ -> TrayStore.updateText(this, item.id, field.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addClipboard() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (text.isNullOrEmpty()) toast("Clipboard is empty") else TrayStore.addText(this, "Clipboard", text)
    }

    private fun prompt() = TrayStore.buildPrompt(this, instruction.text.toString())

    private fun copyPrompt() {
        val items = TrayStore.all(this)
        if (items.isEmpty()) { toast("Tray is empty"); return }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Prompt", prompt()))
        val shots = items.count { it.isImage }
        toast(if (shots > 0) "Prompt copied. Use “Send to AI” to include the $shots snip(s)." else "Prompt copied")
    }

    private fun sharePrompt() {
        val items = TrayStore.all(this)
        if (items.isEmpty()) { toast("Tray is empty"); return }
        val text = prompt()
        val images = items.filter { it.isImage }.mapNotNull { it.uri }.map(Uri::parse)
        val intent = when (images.size) {
            0 -> Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
            1 -> Intent(Intent.ACTION_SEND).apply {
                type = "image/*"; putExtra(Intent.EXTRA_STREAM, images[0])
            }
            else -> Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(images))
            }
        }.apply {
            putExtra(Intent.EXTRA_TEXT, text)
            if (images.isNotEmpty()) {
                clipData = ClipData.newRawUri("Snips", images[0]).also { cd ->
                    images.drop(1).forEach { cd.addItem(ClipData.Item(it)) }
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        startActivity(Intent.createChooser(intent, "Send prompt to…"))
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear the tray?")
            .setMessage("Snips stay in your gallery under Pictures/PromptBubble.")
            .setPositiveButton("Clear") { _, _ -> TrayStore.clear(this) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
