package com.regmold.domain;

public enum Access {
    RO,
    RW,
    WO,
    RC,
    W1C,
    W1S,
    RESERVED;

    public boolean writableByNormalStore() {
        return this == RW || this == WO || this == W1C || this == W1S;
    }
}
