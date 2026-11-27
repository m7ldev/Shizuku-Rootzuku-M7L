package moe.shizuku.manager.tools

/**
 * A compact list of the domains that ad and tracker networks use. A lookup is blocked when its
 * host is one of these or ends with ".<one of these>". YouTube's own domains are left out on
 * purpose: its ads ride on the same servers as the videos, so blocking them breaks playback.
 */
object Blocklist {

    private val domains = setOf(
        // Google / AdMob / DoubleClick (not youtube/google video)
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "google-analytics.com", "googletagmanager.com", "googletagservices.com",
        "admob.com", "app-measurement.com", "adservice.google.com",
        "pagead2.googlesyndication.com", "securepubads.g.doubleclick.net",
        // Facebook / Meta ads
        "an.facebook.com", "graph.facebook.com", "ads.facebook.com",
        // Mobile ad SDKs
        "applovin.com", "applvn.com", "unityads.unity3d.com", "unity3dusercontent.com",
        "auction-load.unityads.unity3d.com", "ironsrc.com", "ironsource.com",
        "supersonicads.com", "vungle.com", "chartboost.com", "adcolony.com",
        "inmobi.com", "aerserv.com", "startappservice.com", "startapp.com",
        "tapjoy.com", "fyber.com", "smaato.net", "mopub.com", "mobfox.com",
        "pangle.io", "pangolin-sdk-toutiao.com", "pubmatic.com", "my.com",
        "mytarget.com", "yandexadexchange.net", "adsmoloco.com", "moloco.com",
        "liftoff.io", "mintegral.com", "rayjump.com", "mbridge.com",
        // Trackers / analytics
        "appsflyer.com", "adjust.com", "branch.io", "kochava.com",
        "flurry.com", "crashlytics.com", "amplitude.com", "mixpanel.com",
        "scorecardresearch.com", "chartbeat.com", "segment.io", "bugsnag.com",
        "criteo.com", "taboola.com", "outbrain.com", "amazon-adsystem.com",
        "adsrvr.org", "casalemedia.com", "rubiconproject.com", "openx.net",
        "33across.com", "bidswitch.net", "smartadserver.com", "teads.tv",
        "advertising.com", "adnxs.com", "adform.net", "yieldmo.com",
        "zemanta.com", "mgid.com", "revcontent.com", "propellerads.com",
        "onesignal.com", "pushwoosh.com", "airpush.com"
    )

    fun isBlocked(host: String): Boolean {
        if (domains.contains(host)) return true
        var i = host.indexOf('.')
        while (i >= 0) {
            if (domains.contains(host.substring(i + 1))) return true
            i = host.indexOf('.', i + 1)
        }
        return false
    }
}
