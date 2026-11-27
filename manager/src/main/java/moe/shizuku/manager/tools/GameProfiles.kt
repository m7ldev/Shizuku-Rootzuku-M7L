package moe.shizuku.manager.tools

import moe.shizuku.manager.ShizukuSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * The per-game settings. A profile belongs to one app and holds what should change while that
 * app is in the foreground. Everything here is applied with adb-level commands through the
 * Shizuku service and put back when the app is left, so nothing stays changed system-wide.
 */
data class GameProfile(
    val pkg: String,
    var refresh: Int = 0,     // forced refresh rate in Hz, 0 = leave as is
    var anim: Int = -1,       // -1 leave, 0 off, 1 fast, 2 normal (smoothness)
    var kill: Boolean = false,      // close background apps on launch (less lag)
    var perf: Boolean = false,      // performance power mode
    var meter: Boolean = false,     // show the floating performance meter
    var hw: Boolean = false,        // force GPU composition (can smooth some games)
    var res: Int = 0,         // render at this percent of the real resolution, 0 = full
    var dnd: Boolean = false,       // do not disturb (silence notifications) while playing
    var net: Boolean = false,       // keep background apps off the network while playing
    var touch: Boolean = false,     // higher touch sensitivity (Samsung)
    var vivid: Boolean = false      // vivid screen colours / saturation (Samsung)
) {
    /** A short line of what this profile changes, for the list. */
    fun summary(): String {
        val on = ArrayList<String>()
        if (refresh > 0) on.add("$refresh Hz")
        if (anim == 0) on.add("0x")
        if (res in 1..99) on.add("$res%")
        if (perf) on.add("PERF")
        if (kill) on.add("CLEAN")
        if (hw) on.add("GPU")
        if (dnd) on.add("DND")
        if (net) on.add("NET")
        if (touch) on.add("TOUCH")
        if (vivid) on.add("VIVID")
        if (meter) on.add("HUD")
        return on.joinToString(" · ")
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("pkg", pkg); put("refresh", refresh); put("anim", anim)
        put("kill", kill); put("perf", perf); put("meter", meter); put("hw", hw); put("res", res)
        put("dnd", dnd); put("net", net); put("touch", touch); put("vivid", vivid)
    }
}

object GameProfiles {

    private const val KEY = "game_profiles"
    const val KEY_WATCH = "game_watch"

    private fun load(): MutableList<GameProfile> {
        val result = ArrayList<GameProfile>()
        try {
            val array = JSONArray(ShizukuSettings.getPreferences().getString(KEY, "[]"))
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                result.add(
                    GameProfile(
                        o.getString("pkg"),
                        o.optInt("refresh", 0), o.optInt("anim", -1),
                        o.optBoolean("kill"), o.optBoolean("perf"), o.optBoolean("meter"),
                        o.optBoolean("hw"), o.optInt("res", 0),
                        o.optBoolean("dnd"), o.optBoolean("net"),
                        o.optBoolean("touch"), o.optBoolean("vivid")
                    )
                )
            }
        } catch (e: Throwable) {
        }
        return result
    }

    private fun save(list: List<GameProfile>) {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        ShizukuSettings.getPreferences().edit().putString(KEY, array.toString()).apply()
    }

    fun all(): List<GameProfile> = load()

    fun packages(): Set<String> = load().map { it.pkg }.toSet()

    fun get(pkg: String): GameProfile? = load().firstOrNull { it.pkg == pkg }

    /** Adds or replaces the profile of its app. */
    fun put(profile: GameProfile) {
        val list = load()
        val index = list.indexOfFirst { it.pkg == profile.pkg }
        if (index >= 0) list[index] = profile else list.add(profile)
        save(list)
    }

    fun remove(pkg: String) {
        save(load().filterNot { it.pkg == pkg })
    }
}
