package com.regmold.model;

import java.util.List;

public record Register(String name, long address, int width, long reset,
                       String aliasOf, AtomicOp atomicOp, String atomicTarget,
                       ReadOrder readOrder, boolean latchOnRead, String lockedBy,
                       List<Field> fields) {

    public enum AtomicOp { NONE, SET, CLEAR }

    public enum ReadOrder { LOW_FIRST, HIGH_FIRST }

    public boolean isAlias() {
        return aliasOf != null;
    }

    public long mask() {
        return width >= 64 ? -1L : (1L << width) - 1L;
    }

    public Field field(String fieldName) {
        for (Field f : fields) {
            if (f.name().equals(fieldName)) {
                return f;
            }
        }
        return null;
    }
}
