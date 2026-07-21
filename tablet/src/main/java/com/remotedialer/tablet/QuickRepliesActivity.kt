package com.remotedialer.tablet

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.remotedialer.tablet.databinding.ActivityQuickRepliesBinding

/**
 * Editor for the reject-with-SMS quick replies ([DevicePrefs.quickReplies]).
 * Ships with 5 common defaults; every message can be edited (tap the row),
 * deleted (trash button), or added (bottom button, up to [MAX]). Edited at home
 * — glove ergonomics don't apply here, only on the call screen that shows them.
 */
class QuickRepliesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQuickRepliesBinding
    private var replies: MutableList<String> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuickRepliesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.addButton.setOnClickListener { editDialog(null) }

        replies = DevicePrefs.quickReplies(this).toMutableList()
        rebuild()
    }

    private fun rebuild() {
        val container = binding.listContainer
        container.removeAllViews()
        replies.forEachIndexed { index, text -> container.addView(replyRow(index, text)) }
        binding.addButton.visibility = if (replies.size >= MAX) View.GONE else View.VISIBLE
    }

    private fun save() {
        DevicePrefs.setQuickReplies(this, replies)
        rebuild()
    }

    private fun replyRow(index: Int, text: String): View {
        val label = TextView(this).apply {
            this.text = text
            textSize = 17f
            setTextColor(ContextCompat.getColor(this@QuickRepliesActivity, R.color.text))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val deleteButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_delete)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundResource(borderlessRipple())
            contentDescription = getString(R.string.delete_message)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener {
                replies.removeAt(index)
                save()
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(16), dp(12), dp(8), dp(12))
            background = ContextCompat.getDrawable(this@QuickRepliesActivity, R.drawable.bg_row)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
            addView(label)
            addView(deleteButton)
            setOnClickListener { editDialog(index) }
        }
    }

    private fun editDialog(index: Int?) {
        val input = EditText(this).apply {
            setText(index?.let { replies[it] } ?: "")
            setSelection(text.length)
            typeface = Typeface.DEFAULT
        }
        val wrapper = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), dp(0))
            addView(
                input,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            )
        }
        AlertDialog.Builder(this)
            .setTitle(if (index == null) R.string.add_message else R.string.edit_message)
            .setView(wrapper)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = input.text.toString().replace('\n', ' ').trim()
                if (text.isEmpty()) return@setPositiveButton
                if (index == null) replies.add(text) else replies[index] = text
                save()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun borderlessRipple(): Int {
        val out = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, out, true)
        return out.resourceId
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val MAX = 8
    }
}
