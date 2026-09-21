package com.regmold.domain;

public enum Endianness {
    LE, BE;

    public static Endianness fromKey(String key) {
        return valueOf(key.trim().toUpperCase());
    }
}
