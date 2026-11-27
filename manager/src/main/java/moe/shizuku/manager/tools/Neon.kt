package moe.shizuku.manager.tools

import android.content.Context
import moe.shizuku.manager.R

/**
 * The neon colours of the interface. Each one comes with a card background, an icon box and
 * the colour for icons and glowing text.
 */
enum class Neon(val color: Int, val card: Int, val iconBox: Int) {
    VIOLET(R.color.neon_violet, R.drawable.neon_card_violet, R.drawable.tool_icon_violet),
    TEAL(R.color.neon_teal, R.drawable.neon_card_teal, R.drawable.tool_icon_teal),
    AMBER(R.color.neon_amber, R.drawable.neon_card_amber, R.drawable.tool_icon_amber),
    PINK(R.color.neon_pink, R.drawable.neon_card_pink, R.drawable.tool_icon_pink),
    BLUE(R.color.neon_blue, R.drawable.neon_card_blue, R.drawable.tool_icon_blue),
    GREEN(R.color.neon_green, R.drawable.neon_card_green, R.drawable.tool_icon_green),
    RED(R.color.neon_red, R.drawable.neon_card_red, R.drawable.tool_icon_red),
    GRAY(R.color.neon_gray, R.drawable.neon_card_gray, R.drawable.tool_icon_gray);

    fun resolve(context: Context): Int = context.getColor(color)

    companion object {
        /** The neon colour that belongs to an icon box drawable, or null for other drawables. */
        fun ofIconBox(drawable: Int): Neon? = values().firstOrNull { it.iconBox == drawable }
    }
}
