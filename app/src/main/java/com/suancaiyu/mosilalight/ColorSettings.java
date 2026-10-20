package com.suancaiyu.mosilalight;

import android.content.SharedPreferences;

/** Restores the original app's non-zero default and migrates the 0.1.0 cache. */
final class ColorSettings {
    private ColorSettings() {}
    static int loadIntensity(SharedPreferences preferences) {
        int value = preferences.contains("colorIntensity")
                ? preferences.getInt("colorIntensity", Protocol.DEFAULT_COLOR_INTENSITY)
                : preferences.getInt("white", Protocol.DEFAULT_COLOR_INTENSITY);
        if (value < 1 || value > 255) value = Protocol.DEFAULT_COLOR_INTENSITY;
        preferences.edit().putInt("colorIntensity", value).remove("white").apply();
        return value;
    }
}
