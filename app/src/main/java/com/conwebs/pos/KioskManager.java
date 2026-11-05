package com.conwebs.pos;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.UserManager;
import android.provider.Settings;

/**
 * Single-app kiosk on a stock tablet, using only what Android gives a device
 * owner: lock task mode plus making the app the home screen.
 *
 * - Lock task blocks Home, Back-out, Recents, the notification shade and every
 *   other app. The device PIN keeps working.
 * - As the home screen, the register is what comes up after unlocking and
 *   after a reboot, with nothing to start at boot.
 *
 * Whether the kiosk is on is the admin's choice and is remembered: turning it
 * off for maintenance stays off until an admin turns it back on.
 *
 * Without device ownership every method here is a no-op, so the same APK runs
 * unchanged on tablets that were never provisioned.
 */
class KioskManager {

    private static final String PREFS       = "POSKiosk";
    private static final String KEY_ENABLED = "enabled";

    private final Context context;
    private final DevicePolicyManager dpm;
    private final ComponentName admin;
    private final ComponentName homeAlias;
    private final SharedPreferences prefs;

    KioskManager(Context context) {
        this.context   = context.getApplicationContext();
        this.dpm       = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        this.admin     = KioskAdminReceiver.component(context);
        this.homeAlias = new ComponentName(context, context.getPackageName() + ".KioskHome");
        this.prefs     = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Provisioned with `dpm set-device-owner`. Nothing below works without it. */
    boolean isDeviceOwner() {
        return dpm != null && dpm.isDeviceOwnerApp(context.getPackageName());
    }

    /** The admin's choice — not whether the screen happens to be pinned right now. */
    boolean isEnabled() {
        return isDeviceOwner() && prefs.getBoolean(KEY_ENABLED, false);
    }

    boolean isLocked(Activity activity) {
        ActivityManager am = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
        return am != null && am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
    }

    /**
     * Called on every resume: put the kiosk back exactly as the admin left it.
     * Also undoes the temporary Settings allowance used for Wi-Fi.
     */
    void enforce(Activity activity) {
        if (!isEnabled()) return;

        applyPolicies();

        if (!isLocked(activity)) {
            try {
                activity.startLockTask();
            } catch (Exception ignored) {
                // Not allowed right now (e.g. a dialog of another app on top);
                // the next resume tries again.
            }
        }
    }

    /** Turn the kiosk on and pin the register now. */
    void enable(Activity activity) {
        if (!isDeviceOwner()) return;

        prefs.edit().putBoolean(KEY_ENABLED, true).apply();
        setHomeScreen(true);
        enforce(activity);
    }

    /**
     * Unpin and hand Home back to the tablet's own launcher. Stays off — nothing
     * turns it back on except an admin choosing to.
     */
    void disable(Activity activity) {
        prefs.edit().putBoolean(KEY_ENABLED, false).apply();

        if (isLocked(activity)) {
            try {
                activity.stopLockTask();
            } catch (Exception ignored) {
            }
        }

        if (!isDeviceOwner()) return;

        dpm.setLockTaskPackages(admin, new String[0]);
        dpm.clearUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT);
        setHomeScreen(false);
    }

    /**
     * Give up device ownership for good, e.g. before handing the tablet back or
     * when the kiosk itself is the problem. Re-provisioning needs ADB again,
     * and usually a factory reset.
     */
    void release(Activity activity) {
        disable(activity);

        if (isDeviceOwner()) {
            dpm.clearDeviceOwnerApp(context.getPackageName());
        }
    }

    /**
     * Open the Wi-Fi picker from inside the kiosk. Lock task only lets
     * allow-listed apps run, so Settings is let in for this one visit;
     * {@link #enforce} takes it back out when the register resumes.
     */
    void openWifi(Activity activity) {
        Intent intent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ? new Intent(Settings.Panel.ACTION_WIFI)
            : new Intent(Settings.ACTION_WIFI_SETTINGS);

        if (activity.getPackageManager().resolveActivity(intent, 0) == null) {
            intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
        }

        if (isEnabled()) {
            ResolveInfo info = activity.getPackageManager().resolveActivity(intent, 0);
            if (info != null) {
                dpm.setLockTaskPackages(admin, new String[]{
                    context.getPackageName(), info.activityInfo.packageName,
                });
            }
        }

        activity.startActivity(intent);
    }

    private void applyPolicies() {
        dpm.setLockTaskPackages(admin, new String[]{context.getPackageName()});

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Keep the battery/Wi-Fi/clock bar, the device PIN, and the power
            // menu so the tablet can still be restarted. Home, Recents and the
            // notification shade stay off.
            dpm.setLockTaskFeatures(admin,
                DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
                    | DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD
                    | DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS);
        }

        // Safe mode starts with third-party apps off, i.e. without the kiosk.
        dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT);

        setHomeScreen(true);
    }

    /**
     * The HOME entry is a separate alias that ships disabled, so a tablet that
     * is not in kiosk mode never sees "Choose a home app".
     */
    private void setHomeScreen(boolean on) {
        PackageManager pm = context.getPackageManager();

        pm.setComponentEnabledSetting(homeAlias,
            on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
               : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP);

        if (!isDeviceOwner()) return;

        if (on) {
            IntentFilter home = new IntentFilter(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addCategory(Intent.CATEGORY_DEFAULT);
            dpm.addPersistentPreferredActivity(admin, home, homeAlias);
        } else {
            dpm.clearPackagePersistentPreferredActivities(admin, context.getPackageName());
        }
    }
}
