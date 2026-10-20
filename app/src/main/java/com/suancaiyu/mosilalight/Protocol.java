package com.suancaiyu.mosilalight;

import java.util.Locale;

/** Reimplemented from the MOSILA 1.0.6 AOT command builders. */
public final class Protocol {
    public static final String SERVICE = "0000eea0-0000-1000-8000-00805f9b34fb";
    public static final String WRITE = "0000ee01-0000-1000-8000-00805f9b34fb";
    // Original home state field_23 at 0x54dbc0 is 255, not zero.
    public static final int DEFAULT_COLOR_INTENSITY = 255;
    private Protocol() {}
    public static byte[] frame(int command, int... parameters) {
        if (parameters.length > 254) throw new IllegalArgumentException("Frame too long");
        byte[] result = new byte[4 + parameters.length];
        result[0] = 0x69; result[1] = (byte) 0x96;
        result[2] = (byte) (parameters.length + 1);
        result[3] = checked(command);
        for (int i = 0; i < parameters.length; i++) result[i + 4] = checked(parameters[i]);
        return result;
    }
    private static byte checked(int value) {
        if (value < 0 || value > 255) throw new IllegalArgumentException("Not a byte: " + value);
        return (byte) value;
    }
    public static byte[] rgb(int color, int intensity) {
        return frame(2, (color >> 16) & 255, (color >> 8) & 255, color & 255, intensity);
    }
    public static byte[] power(boolean enabled, int color, int intensity) {
        return frame(1, enabled ? 1 : 0, (color >> 16) & 255, (color >> 8) & 255, color & 255, intensity);
    }
    public static byte[] mode(int submode, int speed, boolean a, boolean b) {
        if (submode < 0 || submode > 212 || speed < 1 || speed > 10) throw new IllegalArgumentException("Invalid mode");
        return frame(3, 0, submode, 11 - speed, a ? 1 : 0, b ? 1 : 0);
    }
    public static byte[] startup(int mode, int points, int color) {
        if (mode < 0 || mode > 6 || points < 10 || points > 100) throw new IllegalArgumentException("Invalid startup setting");
        return frame(4, mode, points, (color >> 16) & 255, (color >> 8) & 255, color & 255, 255);
    }
    public static byte[] lineOrder(int index) {
        if (index < 1 || index > 6) throw new IllegalArgumentException("Invalid order");
        return new byte[] {(byte) 0x91, (byte) index};
    }
    public static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) { if (out.length() > 0) out.append(' '); out.append(String.format(Locale.ROOT, "%02X", b & 255)); }
        return out.toString();
    }
}
