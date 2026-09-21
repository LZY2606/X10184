package com.regforge.validate;

import java.util.List;

public record Diagnostic(
        Severity severity,
        String code,
        String message,
        List<String> path) {

    public enum Severity { ERROR, WARNING, INFO }

    public static Diagnostic error(String code, String message, List<String> path) {
        return new Diagnostic(Severity.ERROR, code, message, List.copyOf(path));
    }

    public static Diagnostic warning(String code, String message, List<String> path) {
        return new Diagnostic(Severity.WARNING, code, message, List.copyOf(path));
    }

    public static Diagnostic info(String code, String message, List<String> path) {
        return new Diagnostic(Severity.INFO, code, message, List.copyOf(path));
    }
}
