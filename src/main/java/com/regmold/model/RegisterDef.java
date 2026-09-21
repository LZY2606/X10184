package com.regmold.model;

import java.util.List;

public record RegisterDef(String name, long address, int width, long reset,
                          String wordOrder, List<BitField> fields,
                          String aliasOf, String semantic, LockSpec lockedBy, String doc) {
    public RegisterDef {
        fields = fields == null ? List.of() : List.copyOf(fields);
        if (wordOrder == null || wordOrder.isBlank()) wordOrder = "lo-first";
    }

    public boolean isAlias() {
        return aliasOf != null && !aliasOf.isBlank();
    }

    public int words() {
        return (width + 31) / 32;
    }

    public BitField field(String name) {
        for (BitField f : fields) if (f.name().equals(name)) return f;
        return null;
    }
}
