package com.regmold.model;

public record Field(String name, int offset, int width, AccessType access) {

    public long mask() {
        long m = (width >= 64) ? -1L : (1L << width) - 1L;
        return m << offset;
    }

    public boolean overlaps(Field other) {
        return offset < other.offset + other.width && other.offset < offset + width;
    }
}
