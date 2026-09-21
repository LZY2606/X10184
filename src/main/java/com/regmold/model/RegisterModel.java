package com.regmold.model;

import java.util.List;

public record RegisterModel(String name, String endianness, int wordWidth,
                            List<Register> registers, List<SideEffect> sideEffects) {

    public Register reg(String regName) {
        for (Register r : registers) {
            if (r.name().equals(regName)) {
                return r;
            }
        }
        return null;
    }

    public Field field(String regName, String fieldName) {
        Register r = reg(regName);
        return r == null ? null : r.field(fieldName);
    }
}
