package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.content.SharedPreferences;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Stores and retrieves per-package randomized device identifiers.
 * All getters auto-generate and persist an ID on first call so the same
 * fake ID is returned consistently until the user explicitly randomizes.
 */
public class DeviceProfileManager {

    private static final String TAG = "DeviceProfileManager";
    private static final String PREFS_NAME = "device_profiles";

    private static final String KEY_ANDROID_ID = "_android_id";
    private static final String KEY_IMEI       = "_imei";
    private static final String KEY_SERIAL     = "_serial";
    private static final String KEY_SUBSCRIBER = "_subscriber_id";
    private static final String KEY_MAC        = "_mac";

    private static DeviceProfileManager sInstance;

    public static DeviceProfileManager get() {
        if (sInstance == null) {
            synchronized (DeviceProfileManager.class) {
                if (sInstance == null) {
                    sInstance = new DeviceProfileManager();
                }
            }
        }
        return sInstance;
    }

    private SharedPreferences prefs() {
        Context ctx = BlackBoxCore.getContext();
        if (ctx == null) return null;
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String getAndroidId(String packageName) {
        return getOrCreate(packageName + KEY_ANDROID_ID, () -> randomHex(16));
    }

    public String getImei(String packageName) {
        return getOrCreate(packageName + KEY_IMEI, () -> randomDecimalString(15));
    }

    public String getSerial(String packageName) {
        return getOrCreate(packageName + KEY_SERIAL, () -> randomHex(8).toUpperCase());
    }

    public String getSubscriberId(String packageName) {
        return getOrCreate(packageName + KEY_SUBSCRIBER, () -> "310260" + randomDecimalString(9));
    }

    public String getMacAddress(String packageName) {
        return getOrCreate(packageName + KEY_MAC, () -> randomMac());
    }

    /**
     * Wipes all stored IDs for the given package so fresh ones are generated
     * on the next launch — making the app see a completely different device.
     */
    public void randomize(String packageName) {
        SharedPreferences sp = prefs();
        if (sp == null) return;
        SharedPreferences.Editor editor = sp.edit();
        editor.remove(packageName + KEY_ANDROID_ID);
        editor.remove(packageName + KEY_IMEI);
        editor.remove(packageName + KEY_SERIAL);
        editor.remove(packageName + KEY_SUBSCRIBER);
        editor.remove(packageName + KEY_MAC);
        editor.apply();
        Slog.d(TAG, "Randomized device profile for: " + packageName);

        getAndroidId(packageName);
        getImei(packageName);
        getSerial(packageName);
        getSubscriberId(packageName);
        getMacAddress(packageName);
    }

    /** Returns a human-readable summary of current IDs for display in UI. */
    public String getSummary(String packageName) {
        return "ANDROID_ID: " + getAndroidId(packageName) + "\n"
             + "IMEI: " + getImei(packageName) + "\n"
             + "Serial: " + getSerial(packageName) + "\n"
             + "MAC: " + getMacAddress(packageName);
    }

    private String getOrCreate(String key, IdGenerator generator) {
        SharedPreferences sp = prefs();
        if (sp == null) return generator.generate();
        String value = sp.getString(key, null);
        if (value == null || value.isEmpty()) {
            value = generator.generate();
            sp.edit().putString(key, value).apply();
            Slog.d(TAG, "Generated new ID for key=" + key + " value=" + value);
        }
        return value;
    }

    private static String randomHex(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(Integer.toHexString((int) (Math.random() * 16)));
        }
        return sb.toString();
    }

    private static String randomDecimalString(int length) {
        StringBuilder sb = new StringBuilder(length);
        sb.append((int) (Math.random() * 9) + 1);
        for (int i = 1; i < length; i++) {
            sb.append((int) (Math.random() * 10));
        }
        return sb.toString();
    }

    private static String randomMac() {
        int[] octets = new int[6];
        for (int i = 0; i < 6; i++) {
            octets[i] = (int) (Math.random() * 256);
        }
        octets[0] = (octets[0] & 0xFC) | 0x02;
        return String.format("%02x:%02x:%02x:%02x:%02x:%02x",
                octets[0], octets[1], octets[2], octets[3], octets[4], octets[5]);
    }

    private interface IdGenerator {
        String generate();
    }
}
