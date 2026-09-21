package com.regmold.validate;

import java.util.List;

public record Conflict(String code, String message, List<String> path) {
    public Conflict(String code, String message) {
        this(code, message, List.of());
    }
}
