package com.regforge.model;

public record FieldDef(
        String name,
        int lsb,
        int msb,
        AccessMode access,
        String description) {

    public FieldDef {
        if (lsb < 0 || msb < lsb) {
            throw new IllegalArgumentException(
                    "非法位域范围 [" + msb + ":" + lsb + "]: " + name);
        }
    }

    public int width() {
        return msb - lsb + 1;
    }

    public long mask() {
        return lsb >= 64 ? 0L : (lsb + width() >= 64 ? (~0L << lsb) : (((1L << width()) - 1) << lsb));
    }

    public boolean overlaps(FieldDef other) {
        return lsb <= other.msb && other.lsb <= msb;
    }
}
