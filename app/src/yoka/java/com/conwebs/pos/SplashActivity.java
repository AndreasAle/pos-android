package com.conwebs.pos;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * Launcher. Shows the brand for a moment, then routes:
 *   first run                 -> onboarding
 *   onboarded, signed out     -> welcome (register / sign in)
 *   onboarded, signed in      -> straight to the register
 *   onboarded, paired tablet  -> the PIN pad
 */
public class SplashActivity extends Activity {

    private static final long SPLASH_MS = 1100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        YokaFlow.hideSystemUi(this);

        new Handler(Looper.getMainLooper()).postDelayed(this::route, SPLASH_MS);
    }

    private void route() {
        if (isFinishing()) return;

        Intent next;
        if (!YokaFlow.isOnboarded(this)) {
            next = new Intent(this, OnboardingActivity.class);
        } else if (YokaFlow.isSignedIn(this) || YokaFlow.isPaired()) {
            // No entry path: MainActivity opens /kasir, which forwards a live
            // session to the register. Same behaviour as the client app.
            next = new Intent(this, MainActivity.class);
        } else {
            next = new Intent(this, WelcomeActivity.class);
        }

        startActivity(next);
        finish();
    }
}
