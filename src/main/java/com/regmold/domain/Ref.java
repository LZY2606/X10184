package com.regmold.domain;

import java.util.Objects;

/** Reference to a register or one of its fields. */
public record Ref(String register, String field) {

    public static Ref reg(String name) {
        return new Ref(name, null);
    }

    public static Ref field(String reg, String f) {
        return new Ref(reg, f);
    }

    public boolean isField() {
        return field != null && !field.isBlank();
    }

    public String canonical() {
        return isField() ? register + "." + field : register;
    }

    public static Ref parse(String text) {
        Objects.requireNonNull(text, "ref");
        int dot = text.indexOf('.');
        if (dot < 0) {
            return Ref.reg(text.trim());
        }
        return Ref.field(text.substring(0, dot).trim(), text.substring(dot + 1).trim());
    }

    @Override
    public String toString() {
        return canonical();
    }
}
