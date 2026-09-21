package com.regmold.diff;

import java.util.List;

public record DiffEntry(String kind, String severity, String message,
                        String register, String field, List<String> affectedApis) {

    public static DiffEntry abi(String message, String register, String field, List<String> apis) {
        return new DiffEntry("abi", "breaking", message, register, field, List.copyOf(apis));
    }

    public static DiffEntry behavior(String message, String register, String field, List<String> apis) {
        return new DiffEntry(message.startsWith("address")
                || message.startsWith("endianness") || message.startsWith("bus word")
                ? "abi" : "behavior", "behavior", message, register, field, List.copyOf(apis));
    }
}
