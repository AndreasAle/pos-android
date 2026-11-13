package com.conwebs.pos;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.webkit.CookieManager;

/**
 * Shared state for the Yoka entry flow: Splash, Onboarding, Welcome.
 *
 * The register itself (MainActivity) is untouched by any of this; the flow only
 * decides which page it opens first.
 */
final class YokaFlow {

    static final String PREFS = "YokaFlow";
    static final String KEY_ONBOARDED = "onboarded";

    /** Paths on yokapos.com, appended to BuildConfig.DEFAULT_URL. */
    static final String PATH_REGISTER = "/register";
    /** Opening the register while signed out sends the user to /login and back. */
    static final String PATH_LOGIN    = "/pos";
    static final String PATH_PIN      = "/kasir";
    static final String PATH_VERIFY   = "/email/verify";
    static final String PATH_TERMS    = "/syarat";
    static final String PATH_PRIVACY  = "/privasi";
    static final String PATH_DELETE   = "/hapus-akun";

    private YokaFlow() {}

    static boolean isOnboarded(Context c) {
        return prefs(c).getBoolean(KEY_ONBOARDED, false);
    }

    static void setOnboarded(Context c) {
        prefs(c).edit().putBoolean(KEY_ONBOARDED, true).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Signed in, going by the last page the register settled on (written by
     * MainActivity.rememberSignedIn). The server has the last word; an expired
     * session just lands on the login page.
     */
    static boolean isSignedIn(Context c) {
        return prefs(c).getBoolean("signed_in", false);
    }

    /** A tablet already paired to an outlet goes straight to the PIN pad. */
    static boolean isPaired() {
        String cookies = CookieManager.getInstance().getCookie(BuildConfig.DEFAULT_URL);
        return cookies != null && cookies.contains("pos_outlet");
    }

    /** Open the register on {@code path}. The caller stays in the back stack. */
    static void openPage(Activity from, String path) {
        Intent i = new Intent(from, MainActivity.class);
        i.putExtra(MainActivity.EXTRA_ENTRY_PATH, path);
        from.startActivity(i);
    }

    static void hideSystemUi(Activity a) {
        a.getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }
}
