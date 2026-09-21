package com.regforge.util;

import java.util.Map;

/** Number parsing that tolerates hex strings and values above Long.MAX_VALUE (up to 64-bit unsigned). */
public final class Numbers {
    private Numbers() {}

    public static long parseLong(Object raw, long fallback) {
        if (raw == null) return fallback;
        if (raw instanceof Number n) {
            return n.longValue();
        }
        String s = String.valueOf(raw).trim().toLowerCase();
        try {
            if (s.startsWith("0x") || s.startsWith("0X")) {
                return Long.parseUnsignedLong(s.substring(2), 16);
            }
            if (s.startsWith("0b")) {
                return Long.parseUnsignedLong(s.substring(2), 2);
            }
            return Long.parseUnsignedLong(s, 10);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static int parseInt(Object raw, int fallback) {
        return (int) parseLong(raw, fallback);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    public static String hex64(long v) {
        return "0x" + Long.toUnsignedString(v, 16).toUpperCase();
    }
}
