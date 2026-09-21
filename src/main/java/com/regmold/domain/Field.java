package com.regmold.domain;

/** A bit field. Bit positions are global LSB indexes inside the register (0 = least significant). */
public record Field(String name, int lsb, int width, Access access, long reset, String description) {

    public Field {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("field name must not be blank");
        }
        if (width <= 0) {
            throw new IllegalArgumentException("field '" + name + "' width must be positive");
        }
        if (lsb < 0) {
            throw new IllegalArgumentException("field '" + name + "' lsb must be >= 0");
        }
    }

    public int msb() {
        return lsb + width - 1;
    }

    public long mask() {
        if (width >= 64) {
            return ~0L;
        }
        return ((1L << width) - 1L) << lsb;
    }
}
