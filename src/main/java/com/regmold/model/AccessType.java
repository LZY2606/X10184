package com.regmold.model;

public enum AccessType {
    RW, RO, WO, W1C, W1S, RC, RESERVED;

    public static AccessType fromString(String s) {
        if (s == null) return RW;
        return switch (s.trim().toLowerCase()) {
            case "rw", "read-write" -> RW;
            case "ro", "read-only" -> RO;
            case "wo", "write-only" -> WO;
            case "w1c", "write-one-clear" -> W1C;
            case "w1s", "write-one-set" -> W1S;
            case "rc", "read-clear" -> RC;
            case "reserved", "rsvd" -> RESERVED;
            default -> throw new IllegalArgumentException("unknown access type: " + s);
        };
    }
}
