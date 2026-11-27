package moe.shizuku.manager.tools

/**
 * Preinstalled apps that are commonly removed because the phone works without them. The list
 * is kept short on purpose: nothing here is needed for calls, messages, the launcher, the
 * keyboard, updates or the app stores.
 */
object Bloat {

    val PACKAGES: Set<String> = setOf(
        // Bixby
        "com.samsung.android.bixby.agent",
        "com.samsung.android.bixby.wakeup",
        "com.samsung.android.app.spage",
        "com.samsung.android.visionintelligence",
        "com.samsung.android.bixbyvision.framework",
        // Samsung extras
        "com.samsung.android.game.gamehome",
        "com.samsung.android.arzone",
        "com.samsung.android.aremoji",
        "com.samsung.android.aremojieditor",
        "com.sec.android.mimage.avatarstickers",
        "com.samsung.android.ardrawing",
        "com.samsung.android.app.tips",
        "com.samsung.android.kidsinstaller",
        "com.samsung.android.app.watchmanagerstub",
        "com.samsung.android.voc",
        "com.samsung.android.oneconnect",
        "com.samsung.android.spay",
        "com.samsung.sree",
        "com.samsung.android.tvplus",
        "com.sec.android.app.sbrowser",
        "com.samsung.android.email.provider",
        // Partner apps
        "com.facebook.katana",
        "com.facebook.system",
        "com.facebook.appmanager",
        "com.facebook.services",
        "com.microsoft.skydrive",
        "com.microsoft.office.officehubrow",
        "com.microsoft.appmanager",
        "com.linkedin.android",
        "com.netflix.mediaclient",
        "com.netflix.partner.activation",
        // Google apps with replacements
        "com.google.android.apps.tachyon",
        "com.google.android.videos",
        "com.google.android.apps.youtube.music"
    )
}
