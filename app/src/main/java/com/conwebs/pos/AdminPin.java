package com.conwebs.pos;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * The admin PIN that guards the kiosk exit, server address and Wi-Fi.
 *
 * Chosen on the tablet the first time the admin menu opens, so nothing is baked
 * into the APK. Only a salted, stretched hash is stored.
 */
class AdminPin {

    static final int MIN_LENGTH = 4;

    private static final String PREFS     = "POSKioskPin";
    private static final String KEY_SALT  = "salt";
    private static final String KEY_HASH  = "hash";
    private static final int    ROUNDS    = 20000;

    private final SharedPreferences prefs;

    AdminPin(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean isSet() {
        return prefs.contains(KEY_HASH);
    }

    void set(String pin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);

        prefs.edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, hash(pin, salt))
            .apply();
    }

    boolean check(String pin) {
        String salt = prefs.getString(KEY_SALT, null);
        String stored = prefs.getString(KEY_HASH, null);

        if (salt == null || stored == null) return false;

        byte[] a = hash(pin, Base64.decode(salt, Base64.NO_WRAP)).getBytes();
        byte[] b = stored.getBytes();
        return MessageDigest.isEqual(a, b);
    }

    /** Forget the PIN so the next admin visit asks for a new one. Recovery only. */
    void clear() {
        prefs.edit().clear().apply();
    }

    private static String hash(String pin, byte[] salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = pin.getBytes("UTF-8");
            for (int i = 0; i < ROUNDS; i++) {
                md.reset();
                md.update(salt);
                md.update(out);
                out = md.digest();
            }
            return Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
