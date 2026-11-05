package com.conwebs.pos;

import android.app.admin.DeviceAdminReceiver;
import android.content.ComponentName;
import android.content.Context;

/**
 * The device-owner hook. Provisioning names this class:
 *
 *   adb shell dpm set-device-owner com.conwebs.pos/.KioskAdminReceiver
 *
 * It holds no logic of its own; being device owner is what lets the app pin
 * itself with lock task mode and make itself the home screen without asking.
 */
public class KioskAdminReceiver extends DeviceAdminReceiver {

    static ComponentName component(Context context) {
        return new ComponentName(context, KioskAdminReceiver.class);
    }
}
