package moe.shizuku.manager.tools

import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screen and window settings that Android hides: the screen density and the switches for
 * resizable and free-form windows.
 */
class DisplayActivity : ToolListActivity() {

    companion object {
        private const val MIN_DENSITY = 320
        private const val MAX_DENSITY = 750
    }

    private class State(val density: String, val resizable: Boolean, val freeform: Boolean, val animation: String, val autoRotate: Boolean, val rotation: Int, val highRefresh: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ready) return
        showLoading()
        load()
    }

    private fun load() {
        runAsync({
            val density = ShizukuShell.exec("wm density").out.trim()
            val resizable = ShizukuShell.exec("settings get global force_resizable_activities").out.trim() == "1"
            val freeform = ShizukuShell.exec("settings get global enable_freeform_support").out.trim() == "1"
            val animation = ShizukuShell.exec("settings get global window_animation_scale").out.trim()
            val autoRotate = ShizukuShell.exec("settings get system accelerometer_rotation").out.trim() == "1"
            val rotation = ShizukuShell.exec("settings get system user_rotation").out.trim().toIntOrNull() ?: 0
            val highRefresh = (ShizukuShell.exec("settings get system min_refresh_rate").out.trim().toFloatOrNull() ?: 0f) >= 90f
            State(density, resizable, freeform, animation, autoRotate, rotation, highRefresh)
        }) { show(it) }
    }

    private fun show(state: State) {
        showRows(
            listOf(
                Row(
                    title = getString(R.string.display_capture_button),
                    summary = getString(R.string.display_capture_button_summary),
                    checked = ShizukuSettings.getPreferences().getBoolean(CaptureButtonService.KEY_ENABLED, false),
                    icon = R.drawable.ic_camera_24, iconBackground = R.drawable.tool_icon_green,
                    onClick = { toggleCaptureButton() }
                ),
                Row(
                    title = getString(R.string.display_refresh),
                    summary = getString(R.string.display_refresh_summary),
                    checked = state.highRefresh,
                    icon = R.drawable.ic_bolt_24, iconBackground = R.drawable.tool_icon_violet,
                    onClick = {
                        if (state.highRefresh) run("settings put system min_refresh_rate 0")
                        else run("settings put system min_refresh_rate 120; settings put system peak_refresh_rate 120")
                    }
                ),
                Row(
                    title = getString(R.string.display_density),
                    summary = state.density.ifEmpty { null },
                    icon = R.drawable.ic_phone_24, iconBackground = R.drawable.tool_icon_teal,
                    onClick = { askDensity() }
                ),
                Row(
                    title = getString(R.string.display_density_reset),
                    icon = R.drawable.ic_history_24, iconBackground = R.drawable.tool_icon_gray,
                    onClick = { run("wm density reset") }
                ),
                Row(
                    title = getString(R.string.display_resizable),
                    summary = getString(R.string.display_resizable_summary),
                    checked = state.resizable,
                    icon = R.drawable.ic_apps_24, iconBackground = R.drawable.tool_icon_violet,
                    onClick = {
                        run("settings put global force_resizable_activities ${if (state.resizable) 0 else 1}")
                    }
                ),
                Row(
                    title = getString(R.string.display_freeform),
                    summary = getString(R.string.display_freeform_summary),
                    checked = state.freeform,
                    icon = R.drawable.ic_apps_24, iconBackground = R.drawable.tool_icon_blue,
                    onClick = {
                        run("settings put global enable_freeform_support ${if (state.freeform) 0 else 1}")
                    }
                ),
                Row(
                    title = getString(R.string.turbo_animation),
                    summary = animationName(state.animation),
                    icon = R.drawable.ic_movie_24, iconBackground = R.drawable.tool_icon_violet,
                    onClick = { chooseAnimation() }
                ),
                Row(
                    title = getString(R.string.rotation_landscape),
                    summary = getString(R.string.rotation_landscape_summary),
                    checked = !state.autoRotate && (state.rotation == 1 || state.rotation == 3),
                    icon = R.drawable.ic_phone_24, iconBackground = R.drawable.tool_icon_teal,
                    onClick = {
                        if (!state.autoRotate && (state.rotation == 1 || state.rotation == 3)) {
                            run("settings put system accelerometer_rotation 1")
                        } else {
                            run("settings put system accelerometer_rotation 0; settings put system user_rotation 1")
                        }
                    }
                ),
                Row(
                    title = getString(R.string.rotation_portrait),
                    summary = getString(R.string.rotation_portrait_summary),
                    checked = !state.autoRotate && (state.rotation == 0 || state.rotation == 2),
                    icon = R.drawable.ic_phone_24, iconBackground = R.drawable.tool_icon_blue,
                    onClick = {
                        if (!state.autoRotate && (state.rotation == 0 || state.rotation == 2)) {
                            run("settings put system accelerometer_rotation 1")
                        } else {
                            run("settings put system accelerometer_rotation 0; settings put system user_rotation 0")
                        }
                    }
                )
            )
        )
    }

    private fun askDensity() {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "$MIN_DENSITY - $MAX_DENSITY"
            setText(resources.displayMetrics.densityDpi.toString())
        }
        val container = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.display_density)
            .setMessage(R.string.display_density_hint)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = input.text.toString().toIntOrNull()
                // Values far outside this range make the screen unusable
                if (value == null || value < MIN_DENSITY || value > MAX_DENSITY) {
                    toast(getString(R.string.display_density_invalid, MIN_DENSITY, MAX_DENSITY))
                } else {
                    run("wm density $value")
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun animationName(value: String): String {
        return when (value) {
            "0", "0.0" -> getString(R.string.turbo_animation_off)
            "0.5" -> getString(R.string.turbo_animation_fast)
            "", "null", "1", "1.0" -> getString(R.string.turbo_animation_normal)
            else -> value + "x"
        }
    }

    private fun chooseAnimation() {
        val values = arrayOf("0", "0.5", "1")
        val labels = arrayOf<CharSequence>(
            getString(R.string.turbo_animation_off),
            getString(R.string.turbo_animation_fast),
            getString(R.string.turbo_animation_normal)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.turbo_animation)
            .setItems(labels) { _, which ->
                val v = values[which]
                run("for k in window_animation_scale transition_animation_scale animator_duration_scale; do settings put global \$k $v; done")
            }
            .show()
    }

    private fun run(command: String) {
        runAsync({ ShizukuShell.exec(command) }) { result ->
            if (!result.isSuccess) toast(result.message)
            load()
        }
    }

    /**
     * Turns the floating capture button on or off. It needs permission to draw over other apps;
     * without it we send the user to grant it and stop there.
     */
    private fun toggleCaptureButton() {
        val prefs = ShizukuSettings.getPreferences()
        val on = prefs.getBoolean(CaptureButtonService.KEY_ENABLED, false)
        if (on) {
            CaptureButtonService.stop(this)
            prefs.edit().putBoolean(CaptureButtonService.KEY_ENABLED, false).apply()
            load()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.display_capture_button_permission))
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (e: Throwable) {
                toast(e.toString())
            }
            return
        }
        CaptureButtonService.start(this)
        prefs.edit().putBoolean(CaptureButtonService.KEY_ENABLED, true).apply()
        load()
    }

    override fun onResume() {
        super.onResume()
        // The overlay permission may have just been granted; reflect it and start if waiting
        val prefs = ShizukuSettings.getPreferences()
        if (prefs.getBoolean(CaptureButtonService.KEY_ENABLED, false) && !Settings.canDrawOverlays(this)) {
            prefs.edit().putBoolean(CaptureButtonService.KEY_ENABLED, false).apply()
        }
    }

}
