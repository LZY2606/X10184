package com.regmold.gen;

public final class Naming {

    private Naming() {
    }

    public static String snake(String s) {
        String t = s.trim().replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "")
                .toLowerCase();
        return t.isEmpty() ? "dev" : t;
    }

    public static String upper(String s) {
        return snake(s).toUpperCase();
    }
}
