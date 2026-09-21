package com.regforge.gen;

final class NameUtil {
    private NameUtil() {}

    static String upper(String s) {
        return s.trim().replaceAll("[^A-Za-z0-9]", "_").toUpperCase();
    }

    static String lower(String s) {
        return s.trim().replaceAll("[^A-Za-z0-9]", "_").toLowerCase();
    }
}
