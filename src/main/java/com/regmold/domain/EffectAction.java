package com.regmold.domain;

public enum EffectAction {
    SET, CLEAR;

    public static EffectAction fromKey(String key) {
        return valueOf(key.trim().toUpperCase());
    }
}
