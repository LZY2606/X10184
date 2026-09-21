package com.regforge.model;

import java.util.List;

public record RegisterDef(
        String name,
        long address,
        int width,
        long reset,
        WordOrder wordOrder,
        String aliasOf,
        String lockedBy,
        Long unlockValue,
        String description,
        List<FieldDef> fields,
        List<SideEffect> sideEffects) {

    public enum WordOrder { LOW_FIRST, HIGH_FIRST }

    public boolean isAlias() {
        return aliasOf != null && !aliasOf.isBlank();
    }

    public long widthMask() {
        return width >= 64 ? ~0L : ((1L << width) - 1);
    }

    public FieldDef field(String name) {
        return fields.stream().filter(f -> f.name().equals(name)).findFirst().orElse(null);
    }
}
