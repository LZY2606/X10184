package com.regmold.model;

import java.util.List;

public record BitField(String name, int offset, int width, AccessType access,
                       Long reset, List<SideEffect> sideEffects, String doc) {
    public BitField {
        sideEffects = sideEffects == null ? List.of() : List.copyOf(sideEffects);
    }

    /** Bit mask within the parent register (width up to 64 bits). */
    public long mask() {
        long m = width >= 64 ? -1L : (1L << width) - 1L;
        return m << offset;
    }

    public boolean crossesWord() {
        return offset / 32 != (offset + width - 1) / 32;
    }
}
