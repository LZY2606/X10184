package com.regforge.sim;

public record SimOp(Type type, String register, Long value, String note) {

    public enum Type {
        READ,
        PEEK,
        WRITE
    }

    public static SimOp read(String register) {
        return new SimOp(Type.READ, register, null, null);
    }

    public static SimOp peek(String register) {
        return new SimOp(Type.PEEK, register, null, null);
    }

    public static SimOp write(String register, long value) {
        return new SimOp(Type.WRITE, register, value, null);
    }
}
