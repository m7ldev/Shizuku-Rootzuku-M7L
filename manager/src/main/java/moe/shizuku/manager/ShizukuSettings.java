package moe.shizuku.manager;

import android.app.ActivityThread;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;

import java.lang.annotation.Retention;
import java.util.Locale;

import moe.shizuku.manager.utils.EmptySharedPreferencesImpl;
import moe.shizuku.manager.utils.EnvironmentUtils;

import static java.lang.annotation.RetentionPolicy.SOURCE;

public class ShizukuSettings {

    public static final String NAME = "settings";
    public static final String NIGHT_MODE = "night_mode";
    public static final String LANGUAGE = "language";
    public static final String KEEP_START_ON_BOOT = "start_on_boot";
    public static final String STATUS_NOTIFICATION = "status_notification";
    public static final String WATCHDOG = "watchdog";
    public static final String ALERT_ON_USE = "alert_on_use";
    public static final String DISABLE_WADB_AFTER_START = "disable_wadb_after_start";
    public static final String APP_LOCK = "app_lock";
    private static final String USER_STOPPED = "user_stopped";
    private static final String APPS_SORT_MODE = "apps_sort_mode";
    private static final String BOOT_START_ATTEMPTS = "boot_start_attempts";

    public static final int SORT_DEFAULT = 0;
    public static final int SORT_NAME = 1;
    public static final int SORT_GRANTED_FIRST = 2;

    private static SharedPreferences sPreferences;

    public static SharedPreferences getPreferences() {
        return sPreferences;
    }

    @NonNull
    private static Context getSettingsStorageContext(@NonNull Context context) {
        Context storageContext;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            storageContext = context.createDeviceProtectedStorageContext();
        } else {
            storageContext = context;
        }

        storageContext = new ContextWrapper(storageContext) {
            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
                try {
                    return super.getSharedPreferences(name, mode);
                } catch (IllegalStateException e) {
                    // SharedPreferences in credential encrypted storage are not available until after user is unlocked
                    return new EmptySharedPreferencesImpl();
                }
            }
        };

        return storageContext;
    }

    public static void initialize(Context context) {
        if (sPreferences == null) {
            sPreferences = getSettingsStorageContext(context)
                    .getSharedPreferences(NAME, Context.MODE_PRIVATE);
        }
    }

    @IntDef({
            LaunchMethod.UNKNOWN,
            LaunchMethod.ROOT,
            LaunchMethod.ADB,
    })
    @Retention(SOURCE)
    public @interface LaunchMethod {
        int UNKNOWN = -1;
        int ROOT = 0;
        int ADB = 1;
    }

    @LaunchMethod
    public static int getLastLaunchMode() {
        return getPreferences().getInt("mode", LaunchMethod.UNKNOWN);
    }

    public static void setLastLaunchMode(@LaunchMethod int method) {
        getPreferences().edit().putInt("mode", method).apply();
    }

    public static boolean isStatusNotificationEnabled() {
        return getPreferences().getBoolean(STATUS_NOTIFICATION, false);
    }

    public static boolean isWatchdogEnabled() {
        return isStatusNotificationEnabled() && getPreferences().getBoolean(WATCHDOG, false);
    }

    /**
     * True when the service was stopped on purpose from this app, so that it must not be
     * restarted automatically.
     */
    public static boolean isUserStopped() {
        return getPreferences().getBoolean(USER_STOPPED, false);
    }

    public static void setUserStopped(boolean stopped) {
        if (isUserStopped() != stopped) {
            getPreferences().edit().putBoolean(USER_STOPPED, stopped).apply();
        }
    }

    public static boolean isAlertOnUseEnabled() {
        return isStatusNotificationEnabled() && getPreferences().getBoolean(ALERT_ON_USE, false);
    }

    public static boolean isDisableWadbAfterStart() {
        return getPreferences().getBoolean(DISABLE_WADB_AFTER_START, false);
    }

    public static void setDisableWadbAfterStart(boolean value) {
        getPreferences().edit().putBoolean(DISABLE_WADB_AFTER_START, value).apply();
    }

    public static boolean isAppLockEnabled() {
        return getPreferences().getBoolean(APP_LOCK, false);
    }

    public static int getAppsSortMode() {
        return getPreferences().getInt(APPS_SORT_MODE, SORT_DEFAULT);
    }

    public static void setAppsSortMode(int mode) {
        getPreferences().edit().putInt(APPS_SORT_MODE, mode).apply();
    }

    public static int getBootStartAttempts() {
        return getPreferences().getInt(BOOT_START_ATTEMPTS, 0);
    }

    public static void setBootStartAttempts(int attempts) {
        getPreferences().edit().putInt(BOOT_START_ATTEMPTS, attempts).apply();
    }

    @AppCompatDelegate.NightMode
    public static int getNightMode() {
        // The neon interface is designed for a dark background
        return AppCompatDelegate.MODE_NIGHT_YES;
    }

    public static Locale getLocale() {
        String tag = getPreferences().getString(LANGUAGE, null);
        if (TextUtils.isEmpty(tag) || "SYSTEM".equals(tag)) {
            return Locale.getDefault();
        }
        return Locale.forLanguageTag(tag);
    }
}
