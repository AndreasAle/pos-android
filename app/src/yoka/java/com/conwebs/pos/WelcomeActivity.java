package com.conwebs.pos;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Where a signed-out user starts: create a business account, sign in as the
 * owner, or sign in as a cashier with a PIN. Each opens the matching page of
 * the web app inside the register's WebView, so the server stays the single
 * source of truth for accounts and sessions.
 */
public class WelcomeActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_welcome);
        YokaFlow.hideSystemUi(this);

        // Reaching here means onboarding is behind the user, whichever way.
        YokaFlow.setOnboarded(this);

        // Sign-up is a native form (no scrolling); sign-in and PIN use the site.
        findViewById(R.id.register).setOnClickListener(v ->
            startActivity(new Intent(this, RegisterActivity.class)));
        findViewById(R.id.login).setOnClickListener(v ->
            YokaFlow.openPage(this, YokaFlow.PATH_LOGIN));
        findViewById(R.id.pin).setOnClickListener(v ->
            YokaFlow.openPage(this, YokaFlow.PATH_PIN));

        findViewById(R.id.terms).setOnClickListener(v ->
            YokaFlow.openPage(this, YokaFlow.PATH_TERMS));
        findViewById(R.id.privacy).setOnClickListener(v ->
            YokaFlow.openPage(this, YokaFlow.PATH_PRIVACY));
        findViewById(R.id.deleteAccount).setOnClickListener(v ->
            YokaFlow.openPage(this, YokaFlow.PATH_DELETE));
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) YokaFlow.hideSystemUi(this);
    }
}
