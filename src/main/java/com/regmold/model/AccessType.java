package com.regmold.model;

public enum AccessType {
    RW, RO, WO, W1C, W1S, RC, RESERVED;

    public static AccessType from(String s) {
        return switch (s.toLowerCase().replace("-", "").replace("_", "")) {
            case "rw", "readwrite" -> RW;
            case "ro", "readonly" -> RO;
            case "wo", "writeonly" -> WO;
            case "w1c", "writeonetoclear", "writeoneclear" -> W1C;
            case "w1s", "writeonetoset", "writeoneset" -> W1S;
            case "rc", "readtoclear", "readclear" -> RC;
            case "reserved", "rsv", "res" -> RESERVED;
            default -> throw new IllegalArgumentException("unknown access type: " + s);
        };
    }
}
