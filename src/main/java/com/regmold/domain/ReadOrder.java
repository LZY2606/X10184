package com.regmold.domain;

/** Required software read order for multi-word registers. */
public enum ReadOrder {
    LOW_FIRST("low-first"),
    HIGH_FIRST("high-first");

    private final String key;

    ReadOrder(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static ReadOrder fromKey(String key) {
        for (ReadOrder o : values()) {
            if (o.key.equalsIgnoreCase(key) || o.name().equalsIgnoreCase(key)) {
                return o;
            }
        }
        throw new IllegalArgumentException("unknown read order: " + key);
    }
}
