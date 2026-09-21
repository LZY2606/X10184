package com.regmold.gen;

public final class Names {
    private Names() {
    }

    public static String cIdent(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String out = sb.toString().replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (!out.isEmpty() && Character.isDigit(out.charAt(0))) {
            out = "r_" + out;
        }
        return out.toLowerCase();
    }

    public static String cType(int bits) {
        if (bits <= 8) {
            return "uint8_t";
        }
        if (bits <= 16) {
            return "uint16_t";
        }
        if (bits <= 32) {
            return "uint32_t";
        }
        return "uint64_t";
    }

    public static String rustType(int bits) {
        int w = 8;
        while (w < bits) {
            w <<= 1;
        }
        return "u" + w;
    }

    public static String snake(String name) {
        return cIdent(name);
    }

    public static String shout(String name) {
        return cIdent(name).toUpperCase();
    }
}
