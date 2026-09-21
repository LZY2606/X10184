package com.regforge.validate;

import java.util.List;

/** A single model problem. */
public record Issue(Severity severity, String code, String message, List<String> path) {

    public enum Severity { ERROR, WARNING }

    public static Issue error(String code, String message, List<String> path) {
        return new Issue(Severity.ERROR, code, message, path == null ? List.of() : path);
    }

    public static Issue error(String code, String message) {
        return error(code, message, List.of());
    }

    public static Issue warning(String code, String message) {
        return warning(code, message, List.of());
    }

    public static Issue warning(String code, String message, List<String> path) {
        return new Issue(Severity.WARNING, code, message, path == null ? List.of() : path);
    }

    public String pathString() {
        return String.join(" -> ", path);
    }
}
