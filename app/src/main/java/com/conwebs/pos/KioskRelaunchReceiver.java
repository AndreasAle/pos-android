package com.conwebs.pos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Installing an update kills the register, and with it the pinned task; what
 * shows next can be the tablet's own launcher, unlocked. Bring the register
 * straight back so it re-pins itself. Device owners are exempt from Android's
 * limits on starting activities from the background.
 */
public class KioskRelaunchReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        if (!new KioskManager(context).isEnabled()) return;

        Intent launch = new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(launch);
    }
}
