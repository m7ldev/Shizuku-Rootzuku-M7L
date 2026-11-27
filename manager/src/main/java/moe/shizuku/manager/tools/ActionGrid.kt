package moe.shizuku.manager.tools

import android.content.Context
import android.view.Gravity
import android.widget.GridLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R

/**
 * A dialog that shows actions as a grid of two columns, each with its icon.
 */
object ActionGrid {

    class Item(val label: CharSequence, val icon: Int, val run: () -> Unit)

    fun show(context: Context, title: CharSequence, items: List<Item>) {
        val density = context.resources.displayMetrics.density
        val accent = context.getColor(R.color.neon_green)
        val grid = GridLayout(context).apply {
            columnCount = 2
            val side = (12 * density).toInt()
            setPadding(side, (8 * density).toInt(), side, 0)
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(ScrollView(context).apply { addView(grid) })
            .show()
        for (item in items) {
            val view = TextView(context).apply {
                text = item.label
                textSize = 13.5f
                maxLines = 2
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(context.getColor(R.color.neon_text))
                val icon = context.getDrawable(item.icon)?.mutate()
                icon?.setTint(accent)
                icon?.setBounds(0, 0, (22 * density).toInt(), (22 * density).toInt())
                setCompoundDrawablesRelative(icon, null, null, null)
                compoundDrawablePadding = (10 * density).toInt()
                val pad = (8 * density).toInt()
                setPadding(pad, pad, pad, pad)
                minHeight = (48 * density).toInt()
                setBackgroundResource(R.drawable.file_row_background)
                setOnClickListener {
                    dialog.dismiss()
                    item.run()
                }
            }
            grid.addView(view, GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply { width = 0 })
        }
    }
}
