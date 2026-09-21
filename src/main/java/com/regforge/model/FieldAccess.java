package com.regforge.model;

/**
 * Access semantics of a bit field.
 * <ul>
 *   <li>RW: read/write</li>
 *   <li>RO: read-only</li>
 *   <li>RC: read-clear (read consumes / clears the field)</li>
 *   <li>W1C: write-1-clear</li>
 *   <li>W1S: write-1-set</li>
 *   <li>W1: write-only (write pulse), reads as zero / reserved</li>
 *   <li>RESERVED: reserved bits; writes ignored, written back with the read value</li>
 * </ul>
 */
public enum FieldAccess {
    RW,
    RO,
    RC,
    W1C,
    W1S,
    W1,
    RESERVED;

    public static FieldAccess fromString(String raw) {
        if (raw == null) return RW;
        String s = raw.trim().toUpperCase().replace('-', '_');
        return switch (s) {
            case "READONLY", "READ_ONLY" -> RO;
            case "READCLEAR", "READ_CLEAR", "RC" -> RC;
            case "WRITE1CLEAR", "WRITE_1_CLEAR", "W1C" -> W1C;
            case "WRITE1SET", "WRITE_1_SET", "W1S" -> W1S;
            case "WRITEONLY", "WRITE_ONLY", "W1", "WO" -> W1;
            case "RESERVED", "RSVD", "RES" -> RESERVED;
            default -> RW;
        };
    }
}
