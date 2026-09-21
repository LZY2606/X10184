package com.regmold.validate;

public record ValidationIssue(Severity severity, String code, String message, String path) {

    public enum Severity { ERROR, WARNING }

    public static ValidationIssue error(String code, String message) {
        return new ValidationIssue(Severity.ERROR, code, message, null);
    }

    public static ValidationIssue error(String code, String message, String path) {
        return new ValidationIssue(Severity.ERROR, code, message, path);
    }

    public static ValidationIssue warning(String code, String message) {
        return new ValidationIssue(Severity.WARNING, code, message, null);
    }
}
