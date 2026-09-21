package com.regmold.domain;

import java.util.List;

/**
 * A register. {@code widthBits} may exceed the bus word size: such registers are
 * multi-word and carry a required {@link ReadOrder}.
 */
public record Register(
        String name,
        long address,
        int widthBits,
        Access access,
        Long reset,
        List<Field> fields,
        String aliasOf,
        ReadOrder readOrder,
        String description) {

    public int bytes() {
        return widthBits / 8;
    }

    public boolean isAlias() {
        return aliasOf != null && !aliasOf.isBlank();
    }

    public Field field(String name) {
        for (Field f : fields) {
            if (f.name().equals(name)) {
                return f;
            }
        }
        return null;
    }
}
