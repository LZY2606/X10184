package com.regmold.domain;

public class FieldModel {
    public String name;
    public String description;
    public int bitStart;
    public int bitCount;
    public Access access = Access.RW;
    public long reset;
    public String latchFrom;

    public int bitEnd() {
        return bitStart + bitCount - 1;
    }

    public long linearMask() {
        if (bitCount >= 64) {
            return ~0L;
        }
        return ((1L << bitCount) - 1L) << bitStart;
    }
}
