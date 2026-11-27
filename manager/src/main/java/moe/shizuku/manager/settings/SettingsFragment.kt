package moe.shizuku.manager.settings

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.preference.PreferenceGroupAdapter
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.*
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.KEEP_START_ON_BOOT
import moe.shizuku.manager.app.ThemeHelper
import moe.shizuku.manager.app.ThemeHelper.KEY_BLACK_NIGHT_THEME
import moe.shizuku.manager.app.ThemeHelper.KEY_USE_SYSTEM_COLOR
import moe.shizuku.manager.ktx.isComponentEnabled
import moe.shizuku.manager.ktx.setComponentEnabled
import moe.shizuku.manager.ktx.toHtml
import moe.shizuku.manager.authorization.AuthorizationManager
import moe.shizuku.manager.monitor.MonitorService
import moe.shizuku.manager.security.AppLock
import moe.shizuku.manager.receiver.BootCompleteReceiver
import moe.shizuku.manager.utils.CustomTabsHelper
import rikka.core.util.ResourceUtils
import rikka.material.app.LocaleDelegate
import rikka.recyclerview.addEdgeSpacing
import rikka.recyclerview.fixEdgeEffect
import rikka.shizuku.manager.ShizukuLocales
import rikka.widget.borderview.BorderRecyclerView
import java.util.*
import moe.shizuku.manager.ShizukuSettings.LANGUAGE as KEY_LANGUAGE
import moe.shizuku.manager.ShizukuSettings.NIGHT_MODE as KEY_NIGHT_MODE

class SettingsFragment : PreferenceFragmentCompat() {

    private lateinit var languagePreference: ListPreference
    private lateinit var nightModePreference: IntegerSimpleMenuPreference
    private lateinit var blackNightThemePreference: TwoStatePreference
    private lateinit var startOnBootPreference: TwoStatePreference
    private lateinit var startupPreference: PreferenceCategory
    private lateinit var useSystemColorPreference: TwoStatePreference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()

        preferenceManager.setStorageDeviceProtected()
        preferenceManager.sharedPreferencesName = ShizukuSettings.NAME
        preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
        setPreferencesFromResource(R.xml.settings, null)

        languagePreference = findPreference(KEY_LANGUAGE)!!
        nightModePreference = findPreference(KEY_NIGHT_MODE)!!
        blackNightThemePreference = findPreference(KEY_BLACK_NIGHT_THEME)!!
        startOnBootPreference = findPreference(KEEP_START_ON_BOOT)!!
        startupPreference = findPreference("startup")!!
        useSystemColorPreference = findPreference(KEY_USE_SYSTEM_COLOR)!!

        val componentName = ComponentName(context.packageName, BootCompleteReceiver::class.java.name)

        startOnBootPreference.isChecked = context.packageManager.isComponentEnabled(componentName)
        startOnBootPreference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _: Preference?, newValue: Any ->
                if (newValue is Boolean) {
                    context.packageManager.setComponentEnabled(componentName, newValue)
                    context.packageManager.isComponentEnabled(componentName) == newValue
                } else false
            }
        val statusNotificationPreference: TwoStatePreference = findPreference(ShizukuSettings.STATUS_NOTIFICATION)!!
        val watchdogPreference: TwoStatePreference = findPreference(ShizukuSettings.WATCHDOG)!!
        val syncMonitor = Preference.OnPreferenceChangeListener { preference: Preference, newValue: Any ->
            if (preference === statusNotificationPreference
                && newValue == true
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                @Suppress("DEPRECATION")
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            // The new value is stored only after this listener returns
            val appContext = context.applicationContext
            Handler(Looper.getMainLooper()).post { MonitorService.sync(appContext) }
            true
        }
        statusNotificationPreference.onPreferenceChangeListener = syncMonitor
        watchdogPreference.onPreferenceChangeListener = syncMonitor
        findPreference<Preference>(ShizukuSettings.ALERT_ON_USE)!!.onPreferenceChangeListener = syncMonitor

        val appLockPreference: TwoStatePreference = findPreference(ShizukuSettings.APP_LOCK)!!
        if (!AppLock.isSupported) {
            appLockPreference.isVisible = false
        }
        appLockPreference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _: Preference, newValue: Any ->
                val keyguard = context.getSystemService(KeyguardManager::class.java)
                if (newValue == true && keyguard?.isDeviceSecure != true) {
                    Toast.makeText(context, R.string.settings_app_lock_no_screen_lock, Toast.LENGTH_LONG).show()
                    false
                } else {
                    true
                }
            }

        findPreference<Preference>("auto_revoke")!!.setOnPreferenceClickListener {
            showAutoRevokeDialog(context)
            true
        }

        languagePreference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _: Preference?, newValue: Any ->
                if (newValue is String) {
                    val locale: Locale = if ("SYSTEM" == newValue) {
                        LocaleDelegate.systemLocale
                    } else {
                        Locale.forLanguageTag(newValue)
                    }
                    LocaleDelegate.defaultLocale = locale
                    activity?.recreate()
                }
                true
            }

        setupLocalePreference()

        nightModePreference.value = ShizukuSettings.getNightMode()
        nightModePreference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _: Preference?, value: Any? ->
                if (value is Int) {
                    if (ShizukuSettings.getNightMode() != value) {
                        AppCompatDelegate.setDefaultNightMode(value)
                        activity?.recreate()
                    }
                }
                true
            }
        if (ShizukuSettings.getNightMode() != AppCompatDelegate.MODE_NIGHT_NO) {
            blackNightThemePreference.isChecked = ThemeHelper.isBlackNightTheme(context)
            blackNightThemePreference.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _: Preference?, _: Any? ->
                    if (ResourceUtils.isNightMode(context.resources.configuration)) {
                        activity?.recreate()
                    }
                    true
                }
        } else {
            blackNightThemePreference.isVisible = false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            useSystemColorPreference.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _: Preference?, value: Any? ->
                    if (value is Boolean) {
                        if (ThemeHelper.isUsingSystemColor() != value) {
                            activity?.recreate()
                        }
                    }
                    true
                }
        } else {
            useSystemColorPreference.isVisible = false
        }
        // One fixed neon look: there is nothing to choose
        nightModePreference.isVisible = false
        blackNightThemePreference.isVisible = false
        useSystemColorPreference.isVisible = false
    }

    private fun showAutoRevokeDialog(context: Context) {
        val days = intArrayOf(0, 7, 30, 90)
        val labels = arrayOf<CharSequence>(
            getString(R.string.settings_auto_revoke_off),
            getString(R.string.settings_auto_revoke_days, 7),
            getString(R.string.settings_auto_revoke_days, 30),
            getString(R.string.settings_auto_revoke_days, 90)
        )
        val current = try {
            AuthorizationManager.getAutoRevokeDays()
        } catch (e: Throwable) {
            -1
        }
        if (current < 0) {
            Toast.makeText(context, R.string.extras_need_restart, Toast.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_auto_revoke)
            .setSingleChoiceItems(labels, days.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                dialog.dismiss()
                try {
                    AuthorizationManager.setAutoRevokeDays(days[which])
                } catch (e: Throwable) {
                    Toast.makeText(context, e.toString(), Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onCreateRecyclerView(
        inflater: LayoutInflater,
        parent: ViewGroup,
        savedInstanceState: Bundle?
    ): RecyclerView {
        val recyclerView = super.onCreateRecyclerView(inflater, parent, savedInstanceState) as BorderRecyclerView
        recyclerView.fixEdgeEffect()
        recyclerView.addEdgeSpacing(bottom = 8f, unit = TypedValue.COMPLEX_UNIT_DIP)

        val lp = recyclerView.layoutParams
        if (lp is FrameLayout.LayoutParams) {
            lp.rightMargin = recyclerView.context.resources.getDimension(R.dimen.rd_activity_horizontal_margin).toInt()
            lp.leftMargin = lp.rightMargin
        }

        // Every setting is a neon card, like the rows of the tools
        val gap = (5 * recyclerView.resources.displayMetrics.density).toInt()
        recyclerView.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                val position = recyclerView.getChildAdapterPosition(view)
                val item = try {
                    (recyclerView.adapter as? PreferenceGroupAdapter)?.getItem(position)
                } catch (e: Throwable) {
                    null
                }
                if (item == null || item is PreferenceCategory) return
                view.setBackgroundResource(R.drawable.neon_row_background)
                (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                    it.topMargin = gap
                    it.bottomMargin = gap
                    view.layoutParams = it
                }
            }

            override fun onChildViewDetachedFromWindow(view: View) {
            }
        })

        return recyclerView
    }

    private fun setupLocalePreference() {
        val localeTags = ShizukuLocales.LOCALES
        val displayLocaleTags = ShizukuLocales.DISPLAY_LOCALES

        languagePreference.entries = displayLocaleTags
        languagePreference.entryValues = localeTags

        val currentLocaleTag = languagePreference.value
        val currentLocaleIndex = localeTags.indexOf(currentLocaleTag)
        val currentLocale = ShizukuSettings.getLocale()
        val localizedLocales = mutableListOf<CharSequence>()

        for ((index, displayLocale) in displayLocaleTags.withIndex()) {
            if (index == 0) {
                localizedLocales.add(getString(R.string.follow_system))
                continue
            }

            val locale = Locale.forLanguageTag(displayLocale.toString())
            val localeName = if (!TextUtils.isEmpty(locale.script))
                locale.getDisplayScript(locale)
            else
                locale.getDisplayName(locale)

            val localizedLocaleName = if (!TextUtils.isEmpty(locale.script))
                locale.getDisplayScript(currentLocale)
            else
                locale.getDisplayName(currentLocale)

            localizedLocales.add(
                if (index != currentLocaleIndex) {
                    "$localeName<br><small>$localizedLocaleName<small>".toHtml()
                } else {
                    localizedLocaleName
                }
            )
        }

        languagePreference.entries = localizedLocales.toTypedArray()

        languagePreference.summary = when {
            TextUtils.isEmpty(currentLocaleTag) || "SYSTEM" == currentLocaleTag -> {
                getString(R.string.follow_system)
            }
            currentLocaleIndex != -1 -> {
                val localizedLocale = localizedLocales[currentLocaleIndex]
                val newLineIndex = localizedLocale.indexOf('\n')
                if (newLineIndex == -1) {
                    localizedLocale.toString()
                } else {
                    localizedLocale.subSequence(0, newLineIndex).toString()
                }
            }
            else -> {
                ""
            }
        }
    }
}
