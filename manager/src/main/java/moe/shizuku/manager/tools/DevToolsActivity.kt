package moe.shizuku.manager.tools

import android.os.Bundle
import moe.shizuku.manager.R

/**
 * System tweaks that normally hide behind Developer options, each a plain on/off switch applied
 * through the service: keep the screen awake while charging, show touches and the pointer
 * position (handy for recording), keep mobile data ready, and stop Wi-Fi scan throttling.
 */
class DevToolsActivity : ToolListActivity() {

    private class State(
        val stayAwake: Boolean,
        val showTouches: Boolean,
        val pointer: Boolean,
        val mobileAlwaysOn: Boolean,
        val wifiThrottle: Boolean,
        val immersive: Boolean,
        val noAnimations: Boolean,
        val location: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ready) return
        showLoading()
        load()
    }

    private fun get(ns: String, key: String) =
        ShizukuShell.exec("settings get $ns $key").out.trim()

    private fun load() {
        runAsync({
            State(
                stayAwake = (get("global", "stay_on_while_plugged_in").toIntOrNull() ?: 0) != 0,
                showTouches = get("system", "show_touches") == "1",
                pointer = get("system", "pointer_location") == "1",
                mobileAlwaysOn = get("global", "mobile_data_always_on") == "1",
                // throttling on by default; our switch means "disabled", so invert
                wifiThrottle = get("global", "wifi_scan_throttle_enabled") == "0",
                immersive = get("global", "policy_control").contains("immersive.full"),
                // animations off when the window scale reads 0
                noAnimations = get("global", "window_animation_scale") == "0",
                location = get("secure", "location_mode") != "0"
            )
        }) { show(it) }
    }

    private fun show(s: State) {
        showRows(
            listOf(
                Row(
                    title = getString(R.string.dev_stay_awake),
                    summary = getString(R.string.dev_stay_awake_summary),
                    checked = s.stayAwake,
                    icon = R.drawable.ic_bolt_24, iconBackground = R.drawable.tool_icon_amber,
                    onClick = { run("settings put global stay_on_while_plugged_in ${if (s.stayAwake) 0 else 3}") }
                ),
                Row(
                    title = getString(R.string.dev_show_touches),
                    summary = getString(R.string.dev_show_touches_summary),
                    checked = s.showTouches,
                    icon = R.drawable.ic_apps_24, iconBackground = R.drawable.tool_icon_green,
                    onClick = { run("settings put system show_touches ${if (s.showTouches) 0 else 1}") }
                ),
                Row(
                    title = getString(R.string.dev_pointer),
                    summary = getString(R.string.dev_pointer_summary),
                    checked = s.pointer,
                    icon = R.drawable.ic_apps_24, iconBackground = R.drawable.tool_icon_teal,
                    onClick = { run("settings put system pointer_location ${if (s.pointer) 0 else 1}") }
                ),
                Row(
                    title = getString(R.string.dev_mobile_always_on),
                    summary = getString(R.string.dev_mobile_always_on_summary),
                    checked = s.mobileAlwaysOn,
                    icon = R.drawable.ic_dns_24, iconBackground = R.drawable.tool_icon_blue,
                    onClick = { run("settings put global mobile_data_always_on ${if (s.mobileAlwaysOn) 0 else 1}") }
                ),
                Row(
                    title = getString(R.string.dev_wifi_throttle),
                    summary = getString(R.string.dev_wifi_throttle_summary),
                    checked = s.wifiThrottle,
                    icon = R.drawable.ic_bolt_24, iconBackground = R.drawable.tool_icon_violet,
                    onClick = { run("settings put global wifi_scan_throttle_enabled ${if (s.wifiThrottle) 1 else 0}") }
                ),
                Row(
                    title = getString(R.string.dev_immersive),
                    summary = getString(R.string.dev_immersive_summary),
                    checked = s.immersive,
                    icon = R.drawable.ic_phone_24, iconBackground = R.drawable.tool_icon_pink,
                    onClick = {
                        if (s.immersive) run("settings put global policy_control null*")
                        else run("settings put global policy_control immersive.full=*")
                    }
                ),
                Row(
                    title = getString(R.string.dev_no_animations),
                    summary = getString(R.string.dev_no_animations_summary),
                    checked = s.noAnimations,
                    icon = R.drawable.ic_bolt_24, iconBackground = R.drawable.tool_icon_green,
                    onClick = {
                        val v = if (s.noAnimations) "1" else "0"
                        run(
                            "settings put global window_animation_scale $v; " +
                                "settings put global transition_animation_scale $v; " +
                                "settings put global animator_duration_scale $v"
                        )
                    }
                ),
                Row(
                    title = getString(R.string.dev_location),
                    summary = getString(R.string.dev_location_summary),
                    checked = s.location,
                    icon = R.drawable.ic_dns_24, iconBackground = R.drawable.tool_icon_teal,
                    onClick = { run("settings put secure location_mode ${if (s.location) 0 else 3}") }
                )
            )
        )
    }

    private fun run(command: String) {
        runAsync({ ShizukuShell.exec(command) }) { result ->
            if (!result.isSuccess) toast(result.message)
            load()
        }
    }
}
