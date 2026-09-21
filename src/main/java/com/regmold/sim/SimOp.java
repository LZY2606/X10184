package com.regmold.sim;

/** One scripted operation: read | write | preview (debug read, never consumes RC). */
public record SimOp(String op, String register, Long value) {
    public static SimOp read(String reg) { return new SimOp("read", reg, null); }
    public static SimOp preview(String reg) { return new SimOp("preview", reg, null); }
    public static SimOp write(String reg, long value) { return new SimOp("write", reg, value); }
}
