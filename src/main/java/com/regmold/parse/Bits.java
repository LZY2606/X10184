package com.regmold.parse;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Bits {
    private static final Pattern BRACKET = Pattern.compile("^\\s*\\[?\\s*(\\d+)\\s*:\\s*(\\d+)\\s*\\]?\\s*$");

    private Bits() {
    }

    public static int[] range(String text) {
        if (text == null) {
            throw new IllegalArgumentException("missing bit range");
        }
        String t = text.trim();
        if (t.matches("\\d+")) {
            int b = Integer.parseInt(t);
            return new int[]{b, 1};
        }
        Matcher m = BRACKET.matcher(t);
        if (!m.matches()) {
            throw new IllegalArgumentException("invalid bit range: " + text);
        }
        int high = Integer.parseInt(m.group(1));
        int low = Integer.parseInt(m.group(2));
        if (low > high) {
            throw new IllegalArgumentException("invalid bit range (low > high): " + text);
        }
        return new int[]{low, high - low + 1};
    }

    public static long mask(int start, int count) {
        if (count >= 64) {
            return ~0L;
        }
        return ((1L << count) - 1L) << start;
    }

    public static long parseU64(Object raw) {
        if (raw == null) {
            return 0L;
        }
        if (raw instanceof Number n) {
            return n.longValue();
        }
        String s = raw.toString().trim().replace("_", "");
        try {
            if (s.startsWith("0x") || s.startsWith("0X")) {
                return Long.parseUnsignedLong(s.substring(2), 16);
            }
            if (s.startsWith("0b") || s.startsWith("0B")) {
                return Long.parseUnsignedLong(s.substring(2), 2);
            }
            return Long.parseUnsignedLong(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid integer: " + raw);
        }
    }
}
