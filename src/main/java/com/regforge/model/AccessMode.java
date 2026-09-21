package com.regforge.model;

public enum AccessMode {
    RW,
    RO,
    RC,
    W1C,
    W1S,
    RESERVED;

    public static AccessMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            return RW;
        }
        return switch (raw.trim().toUpperCase()) {
            case "RW", "READWRITE", "READ_WRITE" -> RW;
            case "RO", "READONLY", "READ_ONLY" -> RO;
            case "RC", "READ_CLEAR", "READCLEAR", "READ_TO_CLEAR" -> RC;
            case "W1C", "WRITE1CLEAR", "WRITE_1_TO_CLEAR", "WRITE_CLEAR" -> W1C;
            case "W1S", "WRITE1SET", "WRITE_1_TO_SET", "WRITE_SET" -> W1S;
            case "RESERVED", "RESV", "RSVD" -> RESERVED;
            default -> throw new IllegalArgumentException("未知访问模式: " + raw);
        };
    }

    public String description() {
        return switch (this) {
            case RW -> "读写";
            case RO -> "只读";
            case RC -> "读清零（读取后硬件清零）";
            case W1C -> "写一清零";
            case W1S -> "写一置位";
            case RESERVED -> "保留位（写回必须维持读取值）";
        };
    }
}
